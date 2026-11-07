package com.vale.helperbot.task;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.bot.BotTask;
import com.vale.helperbot.bot.MovementController;
import com.vale.helperbot.guard.ProtectionZones;
import com.vale.helperbot.knowledge.CraftPlanner;
import com.vale.helperbot.knowledge.Habitat;
import com.vale.helperbot.knowledge.StructureIndex;
import com.vale.helperbot.path.MineSpot;
import com.vale.helperbot.path.Mode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gets something out of the chests of a structure: to the nearest one that may have it, then
 * from chest to chest. Only chests nobody has opened yet, with a loot table that may hold it:
 * those are the world's, not anyone's, and that's what keeps it out of the players' own chests.
 * What's in one it sees when it opens it, like anyone. It takes only what it came for.
 */
public final class LootTask implements BotTask {
	/** Chests are looked for this many chunks around it. */
	private static final int SCAN_CHUNKS = 6;
	/** Structures tried before it stops. */
	private static final int MAX_VISITS = 3;
	private static final int MAX_WALK_FAILURES = 4;

	private enum Phase {
		SCAN, TRAVEL, WALK
	}

	private final ServerPlayer requester;
	private final Item item;
	private final int wanted;
	private final Mode mode;
	private final Set<Holder<Structure>> where;
	private final Set<ResourceKey<LootTable>> tables;
	private final List<BlockPos> visited = new ArrayList<>();
	private final Set<BlockPos> unreachable = new HashSet<>();
	private Phase phase = Phase.SCAN;
	private BlockPos chest;
	private int startCount = -1;
	private int walkFailures;
	private String failure;

	public LootTask(ServerPlayer requester, Item item, int wanted, Mode mode, Set<Holder<Structure>> where, Set<ResourceKey<LootTable>> tables) {
		this.requester = requester;
		this.item = item;
		this.wanted = wanted;
		this.mode = mode;
		this.where = where;
		this.tables = tables;
	}

	public String failure() {
		return failure;
	}

	@Override
	public Status tick(BotPlayer bot) {
		if (startCount < 0) {
			startCount = bot.getInventory().countItem(item);
		}
		if (bot.getInventory().countItem(item) - startCount >= wanted) {
			bot.movement().stop();
			return Status.DONE;
		}
		return switch (phase) {
			case SCAN -> scan(bot);
			case TRAVEL -> travel(bot);
			case WALK -> walk(bot);
		};
	}

	private Status scan(BotPlayer bot) {
		chest = nearestChest(bot);
		if (chest != null) {
			bot.log("去开 " + chest.toShortString() + " 的箱子");
			walkTo(bot);
			phase = Phase.WALK;
			return Status.RUNNING;
		}
		return setOff(bot);
	}

	private void walkTo(BotPlayer bot) {
		bot.movement().goTo(MineSpot.reachGoal(chest, MineSpot.reach(bot), bot.getEyeHeight(), false, true), chest, mode);
	}

	/** On to the next structure, if there's one to try. */
	private Status setOff(BotPlayer bot) {
		ServerLevel level = bot.level();
		BlockPos at = visited.size() >= MAX_VISITS ? null : StructureIndex.nearest(level, where, bot.blockPosition(), visited);
		if (at == null) {
			return fail("附近的结构里没找到" + CraftPlanner.name(item).getString());
		}
		visited.add(at);
		int distance = (int) Math.sqrt(bot.blockPosition().distSqr(at.atY(bot.blockPosition().getY())));
		bot.say(requester, Component.literal("去 " + at.getX() + ", " + at.getZ() + " 的结构里找 ").append(CraftPlanner.name(item))
				.append("，约 " + distance + " 格。"));
		bot.movement().goTo(Habitat.arrived(at, 32, Habitat.Layer.ANY), at, mode, true);
		phase = Phase.TRAVEL;
		return Status.RUNNING;
	}

	private Status travel(BotPlayer bot) {
		MovementController.Status status = bot.movement().status();
		if (status == MovementController.Status.ARRIVED) {
			bot.movement().stop();
			phase = Phase.SCAN;
		} else if (status == MovementController.Status.FAILED || status == MovementController.Status.IDLE) {
			String why = bot.movement().failReason();
			bot.log("去不了那里（" + why + "），换一处");
			bot.movement().stop();
			phase = Phase.SCAN;
		} else if (bot.tickCount % 40 == 0 && nearestChest(bot) != null) {
			bot.movement().stop(); // the chests are in reach of a scan before it's at the middle of the place
			phase = Phase.SCAN;
		}
		return Status.RUNNING;
	}

	private Status walk(BotPlayer bot) {
		MovementController.Status status = bot.movement().status();
		if (status == MovementController.Status.MOVING) {
			return Status.RUNNING;
		}
		bot.movement().stop();
		if (status == MovementController.Status.ARRIVED) {
			open(bot);
		} else {
			bot.log("到不了 " + chest.toShortString() + " 的箱子（" + bot.movement().failReason() + "）");
			unreachable.add(chest);
			if (++walkFailures >= MAX_WALK_FAILURES) {
				return fail("够不到结构里的箱子");
			}
		}
		phase = Phase.SCAN;
		return Status.RUNNING;
	}

	private void open(BotPlayer bot) {
		if (!(bot.level().getBlockEntity(chest) instanceof RandomizableContainerBlockEntity container)) {
			return;
		}
		bot.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		container.unpackLootTable(bot);
		Inventory inv = bot.getInventory();
		int got = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.is(item)) {
				int before = stack.getCount();
				inv.add(stack);
				got += before - stack.getCount();
			}
		}
		container.setChanged();
		bot.log("开了 " + chest.toShortString() + " 的箱子，拿到 " + got + " 个" + CraftPlanner.name(item).getString());
	}

	/** The nearest chest it may open: never opened, a table that may have it, reach-able so far. */
	private BlockPos nearestChest(BotPlayer bot) {
		ServerLevel level = bot.level();
		BlockPos from = bot.blockPosition();
		BlockPos best = null;
		for (int cx = (from.getX() >> 4) - SCAN_CHUNKS; cx <= (from.getX() >> 4) + SCAN_CHUNKS; cx++) {
			for (int cz = (from.getZ() >> 4) - SCAN_CHUNKS; cz <= (from.getZ() >> 4) + SCAN_CHUNKS; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity entity : chunk.getBlockEntities().values()) {
					BlockPos p = entity.getBlockPos();
					if (entity instanceof RandomizableContainerBlockEntity container && container.getLootTable() != null
							&& tables.contains(container.getLootTable()) && !unreachable.contains(p)
							&& !ProtectionZones.isProtected(level, p) && (best == null || p.distSqr(from) < best.distSqr(from))) {
						best = p;
					}
				}
			}
		}
		return best;
	}

	private Status fail(String why) {
		failure = why;
		return Status.FAILED;
	}

	@Override
	public void onEnd(BotPlayer bot, Status status) {
		bot.movement().stop();
	}

	@Override
	public String describe() {
		return "去结构的箱子里找 " + CraftPlanner.name(item).getString();
	}
}
