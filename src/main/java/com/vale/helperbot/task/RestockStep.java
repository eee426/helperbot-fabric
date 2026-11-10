package com.vale.helperbot.task;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.bot.BotTask;
import com.vale.helperbot.bot.Restock;
import com.vale.helperbot.guard.Guard;
import com.vale.helperbot.knowledge.BlockScanner;
import com.vale.helperbot.knowledge.BuildingSources;
import com.vale.helperbot.knowledge.DropAnalysis;
import com.vale.helperbot.path.Air;
import com.vale.helperbot.path.BuildingBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Tops up building blocks from the ground around, for the restock reflex (see BotPlayer#restock):
 * the gathering task, near here only, from the ground only (see BuildingSources), one kind after
 * another, the nearest first, until there are enough in the bag or what's around runs out. Two kinds
 * about as near get mixed that way. What it finds out of reach goes where the order keeps what's out
 * of reach (see OutOfReach), so it isn't taken for something to dig the next time it looks.
 */
public final class RestockStep implements Restock {
	/** Chunks round it looked through for ground: what gathering scans. */
	private static final int SCAN_CHUNKS = 4;
	/** Out of an order (coming to a player, say): what's out of reach, per bot. */
	private static final Map<BotPlayer, OutOfReach> OWN = new WeakHashMap<>();

	private final int wanted;
	private final int before;
	private final OutOfReach unreachable;
	private final Set<Item> tried = new HashSet<>();
	private GatherTask gather;
	/** The kind being gathered, and the blocks in the bag when it started. */
	private Item gathering;
	private int roundStart;

	private RestockStep(BotPlayer bot, int wanted, OutOfReach unreachable) {
		this.wanted = wanted;
		this.before = BuildingBlocks.count(bot.getInventory());
		this.unreachable = unreachable;
	}

	/** Up to {@code wanted} in the bag, from the nearest ground it can dig; null if there's none around. */
	public static Restock find(BotPlayer bot, int wanted) {
		OutOfReach memory = bot.getTask() instanceof AcquireTask order ? order.outOfReach()
				: OWN.computeIfAbsent(bot, b -> new OutOfReach());
		RestockStep step = new RestockStep(bot, wanted, memory);
		return step.next(bot) ? step : null;
	}

	/** Gather the kind the nearest diggable ground gives, of those that haven't come to nothing yet. False if there's none. */
	private boolean next(BotPlayer bot) {
		ServerLevel level = bot.level();
		Map<Item, Set<Block>> sources = BuildingSources.sources(level.getServer());
		Map<Block, Item> gives = new HashMap<>();
		sources.forEach((item, blocks) -> {
			// Not what the task is after itself: that's the task's, and counted as its progress.
			if (tried.contains(item) || bot.taskWants(new ItemStack(item))) {
				return;
			}
			for (Block block : blocks) {
				if (DropAnalysis.toolSlotFor(level, bot, bot.getInventory(), block, item) != DropAnalysis.NONE) {
					gives.putIfAbsent(block, item);
				}
			}
		});
		if (gives.isEmpty()) {
			return false;
		}
		List<BlockPos> nearest = BlockScanner.find(level, bot.blockPosition(), SCAN_CHUNKS,
				state -> gives.containsKey(state.getBlock()) && DropAnalysis.dropsFrom(gives.get(state.getBlock()), state),
				pos -> !unreachable.covers(pos, Air.fullBudget(bot), bot.blockPosition()) && Guard.canHarvest(level, pos, level.getBlockState(pos))
						&& !bot.movement().hands().laid(pos), // the ground, not its own bridge
				1);
		if (nearest.isEmpty()) {
			return false;
		}
		Item item = gives.get(level.getBlockState(nearest.get(0)).getBlock());
		gathering = item;
		roundStart = BuildingBlocks.count(bot.getInventory());
		int need = wanted - BuildingBlocks.count(bot.getInventory());
		bot.log("补方块：去附近挖" + new ItemStack(item).getHoverName().getString() + "（要 " + need + " 个）");
		gather = new GatherTask(null, item, need, bot.movement().mode(), true, unreachable, new HashMap<>(), sources.get(item));
		return true;
	}

	@Override
	public BotTask.Status tick(BotPlayer bot) {
		BotTask.Status status = gather.tick(bot);
		if (status == BotTask.Status.RUNNING) {
			return status;
		}
		gather.onEnd(bot, status);
		gather = null;
		// Short of what it wanted but more than before (a few laid climbing out of its own pit, say):
		// the same kind again. Nothing more: that kind's done for here.
		if (status != BotTask.Status.DONE || BuildingBlocks.count(bot.getInventory()) <= roundStart) {
			tried.add(gathering);
		}
		if (BuildingBlocks.count(bot.getInventory()) >= wanted || !next(bot)) {
			return BuildingBlocks.count(bot.getInventory()) > before ? BotTask.Status.DONE : BotTask.Status.FAILED;
		}
		return BotTask.Status.RUNNING;
	}

	@Override
	public void end(BotPlayer bot) {
		if (gather != null) {
			gather.onEnd(bot, BotTask.Status.CANCELLED);
			gather = null;
		}
	}
}
