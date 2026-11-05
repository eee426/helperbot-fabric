package com.vale.helperbot.task;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.bot.MovementController;
import com.vale.helperbot.guard.Guard;
import com.vale.helperbot.knowledge.Habitat;
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

	Station(Item item, Block block) {
		this.item = item;
		this.block = block;
	}

	BlockPos pos() {
		return pos;
	}

	String failure() {
		return failure;
	}

	/** Where it's walking to, to find room for the station (null: not walking). */
	private BlockPos dryGround;
	private int walks;
	private static final int MAX_WALKS = 2;
	private static final int DRY_SEARCH_RADIUS = 16;

	/**
	 * One tick of putting it down: next to the bot if there's room; if not (floating in water,
	 * walled in), walk to the nearest dry ground first, like a player would, then try there. It
	 * used to just try three times where it was, in the water, and give up. Out at sea that's the
	 * nearest shore in what's loaded, or failing that, a place whose biome isn't water.
	 */
	BotTask.Status placeTick(BotPlayer bot) {
		if (bot.getInventory().findSlotMatchingItem(new ItemStack(item)) < 0) {
			failure = "背包里没有" + new ItemStack(item).getHoverName().getString();
			return BotTask.Status.FAILED;
		}
		if (land != null) {
			land.tick();
		}
		if (dryGround == null) {
			if (place(bot)) {
				return BotTask.Status.DONE;
			}
			// In the water, the shore first: the nearest dry cell may well be a cave shut in under the
			// bed, right there on the map and no way to it but through the rock, a breath at a time.
			boolean inWater = LocalPathfinder.isWater(bot.level(), bot.movement().feet());
			BlockPos shore = inWater ? nearestShore(bot) : null;
			dryGround = shore != null ? shore : findDryGround(bot);
			if (dryGround == null) {
				dryGround = nearestShore(bot);
			}
			if (dryGround == null) {
				return lookForLand(bot);
			}
			if (++walks > MAX_WALKS) {
				dryGround = null;
				failure = "附近找不到能放" + new ItemStack(item).getHoverName().getString() + "的干地方";
				return BotTask.Status.FAILED;
			}
			bot.log("这里放不下" + new ItemStack(item).getHoverName().getString() + "，先去 " + dryGround.toShortString());
			BlockPos t = dryGround;
			bot.movement().goTo((w, p) -> p.equals(t), t, com.vale.helperbot.path.Mode.DEFAULT);
			return BotTask.Status.RUNNING;
		}
		MovementController.Status status = bot.movement().status();
		if (status == MovementController.Status.ARRIVED) {
			dryGround = null; // try again from here next tick
			if (seeking) {
				seeking = false;
				land.leavePlace();
			}
		} else if (status == MovementController.Status.FAILED || status == MovementController.Status.IDLE) {
			if (seeking) {
				bot.log("去不了那里（" + bot.movement().failReason() + "），换一处");
				dryGround = null;
				seeking = false;
				land.leavePlace();
				return BotTask.Status.RUNNING;
			}
			failure = "去干地方的路走不通（" + bot.movement().failReason() + "）";
			return BotTask.Status.FAILED;
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
			land = new Explorer(level, bot.blockPosition(), dry, false, level.getServer().getPlayerList().getViewDistance() * 16,
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
		dryGround = next;
		seeking = true;
		bot.movement().goTo(Habitat.arrived(next, 16, Habitat.Layer.SURFACE), next, com.vale.helperbot.path.Mode.DEFAULT, true);
		return BotTask.Status.RUNNING;
	}

	/** Set once it has to go looking for land. */
	private Explorer land;
	/** {@link #dryGround} is only where land may be, not a spot it knows. */
	private boolean seeking;

	/** The nearest spot of dry ground on top of what's loaded, ring by ring of chunks; null if it's all water. */
	private static BlockPos nearestShore(BotPlayer bot) {
		ServerLevel level = bot.level();
		BlockPos feet = bot.movement().feet();
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
							BlockPos q = new BlockPos(x * 16 + i, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, i, j) + 1, z * 16 + j);
							double d = q.distSqr(feet);
							if (d < bestDist && roomBeside(level, q)) {
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

	/** A dry spot to stand, with room next to it for the station. */
	private static boolean roomBeside(ServerLevel level, BlockPos q) {
		if (LocalPathfinder.isWater(level, q) || LocalPathfinder.isWater(level, q.above())
				|| Double.isNaN(LocalPathfinder.floorHeight(level, q))) {
			return false;
		}
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos spot = q.relative(dir);
			if (Guard.canPlaceStation(level, spot) && LocalPathfinder.isSupport(level, spot.below())) {
				return true;
			}
		}
		return false;
	}

	/** Nearest spot to stand, out of the water, with room next to it for the station. */
	private BlockPos findDryGround(BotPlayer bot) {
		ServerLevel level = bot.level();
		BlockPos feet = bot.movement().feet();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (int dx = -DRY_SEARCH_RADIUS; dx <= DRY_SEARCH_RADIUS; dx++) {
			for (int dz = -DRY_SEARCH_RADIUS; dz <= DRY_SEARCH_RADIUS; dz++) {
				for (int dy = -6; dy <= 6; dy++) {
					BlockPos q = feet.offset(dx, dy, dz);
					double d = q.distSqr(feet);
					if (d < bestDist && roomBeside(level, q)) {
						best = q;
						bestDist = d;
					}
				}
			}
		}
		return best;
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
				if (Guard.canPlaceStation(level, spot)
						&& (dy == 1 || LocalPathfinder.isSupport(level, spot.below()))
						&& tryPlace(bot, spot, slot)) {
					return true;
				}
			}
		}
		// Walled in on all sides (e.g. at the bottom of a 1x1 shaft): above our head is in reach.
		BlockPos overhead = feet.above(2);
		if (Guard.canPlaceStation(level, overhead) && tryPlace(bot, overhead, slot)) {
			return true;
		}
		failure = "身边找不到能放" + new ItemStack(item).getHoverName().getString() + "的地方";
		return false;
	}

	private boolean tryPlace(BotPlayer bot, BlockPos spot, int slot) {
		if (bot.movement().placeItem(spot, slot) && bot.level().getBlockState(spot).is(block)) {
			pos = spot;
			return true;
		}
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
			MovementController.MineResult result = bot.movement().mineOwnBlock(pos, block);
			if (result == MovementController.MineResult.FAILED) {
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
