package com.vale.helperbot.task;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.bot.BotTask;
import com.vale.helperbot.bot.MovementController;
import com.vale.helperbot.guard.Placement;
import com.vale.helperbot.knowledge.Habitat;
import com.vale.helperbot.path.Air;
import com.vale.helperbot.path.LocalPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The bot's own crafting table or furnace: put down next to it from its inventory, used, then
 * broken and picked back up, leaving nothing behind. It never uses stations other players placed.
 */
final class Station {
	private static final int PICKUP_WAIT = 40;

	private final Item item;
	private final Block block;
	private BlockPos pos;
	private int pickupTicks;
	private String failure = "";
	/** The order's record of what can't be got to (see OutOfReach). */
	private final OutOfReach unreachable;

	Station(Item item, Block block, OutOfReach unreachable) {
		this.item = item;
		this.block = block;
		this.unreachable = unreachable;
	}

	BlockPos pos() {
		return pos;
	}

	String failure() {
		return failure;
	}

	/** What it's walking towards, to find room for the station (null: not walking). */
	private BlockPos heading;
	/** {@link #heading} is only where land may be (see lookForLand). */
	private boolean seeking;
	/** Set once it has to go looking for land. */
	private Explorer land;
	private int walks;
	private static final int MAX_WALKS = 3;

	/**
	 * One tick of putting it down: next to the bot if there's room; if not (in the water, walled in,
	 * in a protected chunk), walk to wherever there is, like a player would, then try there. The
	 * search says where: any spot with room will do, and it takes the one that's the least trouble
	 * to get to, so a cave shut in under the sea bed loses to the shore by what it costs, and
	 * nothing here has to know about water. Spots picked off the map first, it used to go for
	 * one like that a breath at a time, for ever. If the search can't see that far, the nearest dry
	 * ground in what's loaded says which way; with none, wherever the biomes aren't water.
	 */
	BotTask.Status placeTick(BotPlayer bot) {
		if (bot.getInventory().findSlotMatchingItem(new ItemStack(item)) < 0) {
			failure = "背包里没有" + new ItemStack(item).getHoverName().getString();
			return BotTask.Status.FAILED;
		}
		if (land != null) {
			land.tick();
		}
		if (heading == null) {
			return place(bot) ? BotTask.Status.DONE : setOff(bot);
		}
		MovementController.Status status = bot.movement().status();
		if (status == MovementController.Status.ARRIVED) {
			arrived();
		} else if (status == MovementController.Status.FAILED || status == MovementController.Status.IDLE) {
			BlockPos from = bot.movement().provenFrom();
			if (from != null) {
				unreachable.proven(heading, Air.fullBudget(bot), from);
			} else if (!seeking) {
				failure = "去能放" + new ItemStack(item).getHoverName().getString() + "的地方，路走不通（" + bot.movement().failReason() + "）";
				return BotTask.Status.FAILED;
			}
			bot.log("去不了那里（" + bot.movement().failReason() + "），换一处");
			arrived();
		}
		return BotTask.Status.RUNNING;
	}

	private void arrived() {
		if (seeking) {
			land.leavePlace();
		}
		heading = null; // try again from here next tick
		seeking = false;
	}

	private BotTask.Status setOff(BotPlayer bot) {
		BlockPos shore = nearestDryGround(bot);
		// (In the water the flood finds no way to what's there, three times over, say a roof that
		// stands in the sea: what's in sight won't do, so on to where there's land.)
		if (shore == null || (bot.movement().isInWater() && walks >= MAX_WALKS)) {
			return lookForLand(bot);
		}
		String name = new ItemStack(item).getHoverName().getString();
		if (++walks > MAX_WALKS) {
			failure = "附近找不到能放" + name + "的干地方";
			return BotTask.Status.FAILED;
		}
		bot.log("这里放不下" + name + "，去有地方的位置（大致朝 " + shore.toShortString() + "）");
		heading = shore;
		BlockPos here = bot.movement().feet();
		com.vale.helperbot.path.PathGoal room = (w, p) -> !p.equals(here) && roomBeside(w, p);
		if (bot.movement().isInWater()) {
			bot.movement().findWay(List.of(shore), room, com.vale.helperbot.path.Mode.DEFAULT);
		} else {
			bot.movement().goTo(room, shore, com.vale.helperbot.path.Mode.DEFAULT);
		}
		return BotTask.Status.RUNNING;
	}

	/** No dry ground in anything loaded: off to where the biomes aren't water (see Explorer). */
	private BotTask.Status lookForLand(BotPlayer bot) {
		ServerLevel level = bot.level();
		if (land == null) {
			Map<Holder<Biome>, Double> dry = new HashMap<>();
			for (Holder<Biome> b : level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
				if (!b.is(BiomeTags.IS_OCEAN) && !b.is(BiomeTags.IS_RIVER)) {
					dry.put(b, 1.0);
				}
			}
			land = new Explorer(level, bot.blockPosition(), dry, false, true, level.getServer().getPlayerList().getViewDistance() * 16,
					com.vale.helperbot.path.Mode.DEFAULT.speed());
		}
		if (!land.ready()) {
			return BotTask.Status.RUNNING;
		}
		BlockPos next = land.timeUp() ? null : land.next(level, bot.blockPosition(), null, 1);
		if (next == null) {
			failure = "方圆 " + Explorer.RADIUS + " 格内找不到能放" + new ItemStack(item).getHoverName().getString() + "的陆地";
			return BotTask.Status.FAILED;
		}
		bot.log("这里全是水，放不下" + new ItemStack(item).getHoverName().getString() + "，先往 " + next.toShortString() + " 找陆地");
		heading = next;
		seeking = true;
		walks = 0; // somewhere new: what's in sight from there gets its tries
		bot.movement().goTo(Habitat.arrived(next, 16, Habitat.Layer.SURFACE), next, com.vale.helperbot.path.Mode.DEFAULT, true);
		return BotTask.Status.RUNNING;
	}

	/** The nearest spot of dry ground on top of what's loaded, ring by ring of chunks; null if it's all water. */
	private BlockPos nearestDryGround(BotPlayer bot) {
		ServerLevel level = bot.level();
		BlockPos feet = bot.movement().feet();
		int breath = Air.fullBudget(bot);
		int cx = feet.getX() >> 4, cz = feet.getZ() >> 4;
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (int ring = 0; ring <= level.getServer().getPlayerList().getViewDistance() && best == null; ring++) {
			for (int x = cx - ring; x <= cx + ring; x++) {
				for (int z = cz - ring; z <= cz + ring; z++) {
					LevelChunk chunk = Math.max(Math.abs(x - cx), Math.abs(z - cz)) == ring ? level.getChunkSource().getChunkNow(x, z) : null;
					if (chunk == null) {
						continue;
					}
					for (int i = 0; i < 16; i++) {
						for (int j = 0; j < 16; j++) {
							BlockPos q = new BlockPos(x * 16 + i, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, i, j) + 1, z * 16 + j);
							double d = q.distSqr(feet);
							if (d < bestDist && roomBeside(level, q) && !unreachable.covers(q, breath, feet)) {
								best = q;
								bestDist = d;
							}
						}
					}
				}
			}
		}
		return best;
	}

	/** A dry spot to stand, with room next to it for the station. Asked by searches too, of their copy of the world. */
	private static boolean roomBeside(BlockGetter level, BlockPos q) {
		if (LocalPathfinder.isWater(level, q) || LocalPathfinder.isWater(level, q.above())
				|| Double.isNaN(LocalPathfinder.floorHeight(level, q))) {
			return false;
		}
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos spot = q.relative(dir);
			if (Placement.allowed(level, spot, Placement.Kind.STATION) && LocalPathfinder.isSupport(level, spot.below())) {
				return true;
			}
		}
		return false;
	}

	/** Put it down next to the bot, right now. False with {@link #failure()} set if it can't. */
	boolean place(BotPlayer bot) {
		Inventory inv = bot.getInventory();
		int slot = inv.findSlotMatchingItem(new ItemStack(item));
		if (slot < 0) {
			failure = "背包里没有" + new ItemStack(item).getHoverName().getString();
			return false;
		}
		ServerLevel level = bot.level();
		BlockPos feet = bot.movement().feet();
		for (int dy = 0; dy <= 1; dy++) {
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos spot = feet.relative(d).above(dy);
				if (Placement.allowed(level, spot, Placement.Kind.STATION)
						&& (dy == 1 || LocalPathfinder.isSupport(level, spot.below()))
						&& tryPlace(bot, spot, slot)) {
					return true;
				}
			}
		}
		// Walled in on all sides (e.g. at the bottom of a 1x1 shaft): above our head is in reach.
		BlockPos overhead = feet.above(2);
		if (Placement.allowed(level, overhead, Placement.Kind.STATION) && tryPlace(bot, overhead, slot)) {
			return true;
		}
		failure = "身边找不到能放" + new ItemStack(item).getHoverName().getString() + "的地方";
		return false;
	}

	private boolean tryPlace(BotPlayer bot, BlockPos spot, int slot) {
		Placement.Refusal refused = bot.movement().hands().place(spot, slot);
		if (refused == null && bot.level().getBlockState(spot).is(block)) {
			pos = spot;
			return true;
		}
		bot.log(spot.toShortString() + " 放不下" + new ItemStack(item).getHoverName().getString() + "："
				+ (refused != null ? refused.why : Placement.Refusal.NOT_PLACED.why));
		return false;
	}

	/**
	 * Break it and pick it up; call every tick. Returns DONE when it's back in the inventory (or
	 * had to be left behind, which is reported to the player).
	 */
	BotTask.Status retrieve(BotPlayer bot, net.minecraft.server.level.ServerPlayer requester) {
		if (pos == null) {
			return BotTask.Status.DONE;
		}
		ServerLevel level = bot.level();
		BlockState state = level.getBlockState(pos);
		if (state.is(block)) {
			// Some stations (furnace) only drop with the right tool: don't destroy it for nothing.
			if (state.requiresCorrectToolForDrops() && !hasCorrectTool(bot, state)) {
				bot.say(requester, Component.literal("没有能挖回").append(state.getBlock().getName())
						.append("的工具，把它留在了 " + pos.toShortString() + "。"));
				pos = null;
				return BotTask.Status.DONE;
			}
			com.vale.helperbot.bot.Hands.MineResult result = bot.movement().hands().mineOwnBlock(pos, block);
			if (result == com.vale.helperbot.bot.Hands.MineResult.FAILED) {
				bot.say(requester, Component.literal("收不回").append(state.getBlock().getName())
						.append("，留在了 " + pos.toShortString() + "。"));
				pos = null;
				return BotTask.Status.DONE;
			}
			return BotTask.Status.RUNNING;
		}
		// Broken: wait for the drop to be picked up, shuffling towards it if needed.
		List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3),
				e -> e.isAlive() && e.getItem().is(item));
		if (drops.isEmpty() || ++pickupTicks > PICKUP_WAIT) {
			pos = null;
			return BotTask.Status.DONE;
		}
		bot.movement().nudgeToward(drops.get(0).position());
		return BotTask.Status.RUNNING;
	}

	private static boolean hasCorrectTool(BotPlayer bot, BlockState state) {
		Inventory inv = bot.getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			if (inv.getItem(i).isCorrectToolForDrops(state)) {
				return true;
			}
		}
		return false;
	}
}
