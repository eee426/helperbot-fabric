package com.vale.helperbot.task;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.guard.Guard;
import com.vale.helperbot.knowledge.DropAnalysis;
import com.vale.helperbot.path.Air;
import com.vale.helperbot.path.BreakTimes;
import com.vale.helperbot.path.Escape;
import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.MineSpot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * What mining a block takes this bot, with what it's holding and the air it has: how gathering
 * ranks its targets. Where it could stand to do it is {@link MineSpot}'s.
 */
final class MineCost {
	private MineCost() {
	}

	/**
	 * Rough ticks to go and mine {@code p}, or NaN if it can't: there must be a spot to stand (or
	 * swim) within reach of it; under water each block must fit in one breath, and it pays for all
	 * the blocks still wanted from such a spot, dives and breaths included (see Air#diving): what's
	 * near but under water loses to dry land further off, unless it's only a block or two more.
	 * Under water that's only for what the sea alone has (see Guard#ofTheSea): anything else needs
	 * a spot with the head out, or it's NaN.
	 */
	static double cost(BotPlayer bot, BlockPos p, Item item, int slot, int left, Escape escape) {
		ServerLevel level = bot.level();
		double reach = MineSpot.reach(bot);
		Vec3 center = Vec3.atCenterOf(p);
		BlockState state = level.getBlockState(p);
		// With the tool it will really use for this block (not just the fastest in the bag).
		ItemStack tool = slot >= 0 ? bot.getInventory().getItem(slot) : ItemStack.EMPTY;
		int dig = BreakTimes.estimateTicksWith(tool, level, p, state);
		if (dig == Integer.MAX_VALUE) {
			return Double.NaN;
		}
		Air.Profile air = bot.movement().airProfile();
		int blocks = (int) Math.ceil(Math.max(1, left) / Math.max(0.01, DropAnalysis.rate(item, state.getBlock())));
		double bestNeed = Double.NaN;
		boolean anyStand = false;
		boolean mustSee = MineSpot.exposed(level, p);
		boolean sea = Guard.ofTheSea(state);
		int r = (int) Math.ceil(reach);
		search:
		for (int dy = -r - 1; dy <= r; dy++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos q = p.offset(dx, dy, dz);
					if (!MineSpot.isSpot(level, q, p, center, reach, bot.getEyeHeight(), mustSee)) {
						continue;
					}
					anyStand |= Air.breathable(level, q) || MineSpot.stillWater(level, q);
					if (!sea && !Air.breathable(level, q)) {
						continue;
					}
					double penalty = Air.digPenalty(level, q);
					if (Air.breathable(level, q) && penalty == 1) {
						bestNeed = 0; // on the ground, head out: nothing extra to pay
						break search;
					}
					// Floating at the surface it needs no dives, but it does mine that much slower.
					double work = Air.workUnderWater(dig, penalty);
					// (Out of reach, by the estimate? A way round it is the flood's to find: see GatherTask#findWay.)
					double up = escape.ticks(q);
					double need = Air.breathable(level, q) ? blocks * work : Air.diving(air, blocks, work, Double.isInfinite(up) ? 0 : up);
					if (Double.isInfinite(need)) {
						continue; // not on one breath
					}
					if (Double.isNaN(bestNeed) || need < bestNeed) {
						bestNeed = need;
					}
				}
			}
		}
		if (!anyStand && !MineSpot.nearWater(level, p) && (sea || MineSpot.headOut(level, p, reach, bot.getEyeHeight()))) {
			// Buried (ore in solid rock, stone under the dirt): nowhere to stand next to it yet, but
			// digging a way there makes one, like a player would. Costed by what's in the way. (It used
			// to count as unreachable, so with no stone in the open it never got any cobblestone.)
			// Under water this isn't done: a dug tunnel floods, and air is a separate question.
			bestNeed = tunnelCost(bot, p);
		}
		if (Double.isNaN(bestNeed)) {
			return Double.NaN;
		}
		// Per item it gives, not per block: a dead bush (about a stick each) before leaves (1 in 50).
		double perBlock = Math.max(0.01, Math.min(1.0, DropAnalysis.rate(item, state.getBlock())));
		return (Math.sqrt(p.distSqr(bot.blockPosition())) * 5 + bestNeed) / perBlock;
	}

	/** Most cells looked at along a tunnel (beyond that it's too far to dig anyway). */
	private static final int MAX_TUNNEL = 96;

	/**
	 * Rough ticks to dig straight from the bot to {@code p}: every solid block on the line, at the
	 * speed of the best tool in the bag. NaN if something on the way can't be mined (bedrock).
	 */
	private static double tunnelCost(BotPlayer bot, BlockPos p) {
		ServerLevel level = bot.level();
		Vec3 from = bot.getEyePosition();
		Vec3 to = Vec3.atCenterOf(p);
		double length = from.distanceTo(to);
		Set<BlockPos> seen = new HashSet<>();
		double ticks = 0;
		for (double d = 0; d < length && seen.size() < MAX_TUNNEL; d += 0.5) {
			BlockPos c = BlockPos.containing(from.add(to.subtract(from).scale(d / length)));
			if (c.equals(p) || !seen.add(c) || LocalPathfinder.isPassable(level, c)) {
				continue;
			}
			BlockState s = level.getBlockState(c);
			int t = BreakTimes.estimateTicks(bot.getInventory(), level, c, s);
			if (t == Integer.MAX_VALUE || !Guard.canBreak(level, c, s)) {
				return Double.NaN;
			}
			ticks += t;
		}
		return ticks;
	}
}
