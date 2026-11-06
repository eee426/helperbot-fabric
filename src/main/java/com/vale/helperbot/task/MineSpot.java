package com.vale.helperbot.task;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.guard.Guard;
import com.vale.helperbot.guard.ProtectionZones;
import com.vale.helperbot.knowledge.DropAnalysis;
import com.vale.helperbot.path.Air;
import com.vale.helperbot.path.BreakTimes;
import com.vale.helperbot.path.Escape;
import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.PathGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Where a block can be mined from, and what mining it takes: the one reckoning for choosing a
 * target (see {@link #cost}) and for walking to it (see {@link #reachGoal}), so the two can't
 * disagree about what's in reach. It holds nothing: gathering keeps track of what it's doing.
 */
public final class MineSpot {
	/** Kept short of the bot's full reach, to be safe of rounding and of standing a bit off the middle. */
	private static final double REACH_MARGIN = 0.3;

	private MineSpot() {
	}

	/** How far it may stand from what it mines. */
	public static double reach(Player player) {
		return player.blockInteractionRange() - REACH_MARGIN;
	}

	/** Is this for the taking from where the bot is: from the sea, or to be mined with the head out of the water? */
	static boolean workable(BotPlayer bot, BlockPos p) {
		return Guard.ofTheSea(bot.level().getBlockState(p)) || headOut(bot.level(), p, bot.getEyePosition(), reach(bot), bot.getEyeHeight());
	}

	/**
	 * Mining it, is the head out of the water? Going by where it would stand, if there's anywhere
	 * near; buried, with nowhere to stand yet, digging a way there is the plan's business. Not for
	 * what's under the sea from where the bot is (see {@link #seaBetween}): a pocket of air under the
	 * sea bed is no spot to stand, however near.
	 */
	public static boolean headOut(ServerLevel level, BlockPos p, Vec3 from, double reach, double eyeHeight) {
		if (seaBetween(level, p, from, reach)) {
			return false;
		}
		boolean mustSee = exposed(level, p);
		Vec3 center = Vec3.atCenterOf(p);
		int r = (int) Math.ceil(reach);
		boolean anySpot = false;
		for (int dy = -r - 1; dy <= r; dy++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos q = p.offset(dx, dy, dz);
					if (isSpot(level, q, p, center, reach, eyeHeight, mustSee)) {
						if (Air.breathable(level, q)) {
							return true;
						}
						anySpot |= stillWater(level, q);
					}
				}
			}
		}
		return !anySpot;
	}

	/**
	 * Is the sea in the way of this block, for someone at {@code from}? It lies under open water,
	 * too deep to reach from the surface, and the straight line to it goes through that water. A
	 * look at the column first, so it costs next to nothing for all of a sea bed, which is what it's
	 * for: asked of each block in turn, it went through 18,000 and never moved off. Source water only:
	 * a waterfall isn't in the way. From the shore or a mine a dry way under there still counts.
	 */
	public static boolean seaBetween(ServerLevel level, BlockPos p, Vec3 from, double reach) {
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ());
		if (level.getHeight(Heightmap.Types.OCEAN_FLOOR, p.getX(), p.getZ()) >= top || p.getY() >= top - Math.ceil(reach)) {
			return false;
		}
		Vec3 to = Vec3.atCenterOf(p);
		double length = from.distanceTo(to);
		for (double d = 0; d < length; d += 0.5) {
			var fluid = level.getFluidState(BlockPos.containing(from.add(to.subtract(from).scale(d / length))));
			if (fluid.is(FluidTags.WATER) && fluid.isSource()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Can it mine {@code p} with its feet in {@code q}? By the same test the walk there goes by (see
	 * reachGoal). Arm's reach alone counted a kelp in a crack as near: it dived for it again and
	 * again, breath after breath, with a planner that could find nowhere to mine it from.
	 */
	private static boolean isSpot(BlockGetter level, BlockPos q, BlockPos p, Vec3 center, double reach, double eyeHeight, boolean mustSee) {
		Vec3 eye = new Vec3(q.getX() + 0.5, q.getY() + eyeHeight, q.getZ() + 0.5);
		return !q.equals(p) && eye.distanceTo(center) <= reach && !Double.isNaN(LocalPathfinder.floorHeight(level, q))
				&& !ProtectionZones.isProtected(level, q) && (!mustSee || blockerOf(level, eye, p) == null);
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
		double reach = reach(bot);
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
		boolean mustSee = exposed(level, p);
		boolean sea = Guard.ofTheSea(state);
		int r = (int) Math.ceil(reach);
		search:
		for (int dy = -r - 1; dy <= r; dy++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos q = p.offset(dx, dy, dz);
					if (!isSpot(level, q, p, center, reach, bot.getEyeHeight(), mustSee)) {
						continue;
					}
					anyStand |= Air.breathable(level, q) || stillWater(level, q);
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
		if (!anyStand && !nearWater(level, p) && (sea || headOut(level, p, bot.getEyePosition(), reach, bot.getEyeHeight()))) {
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

	/** In or under water (as opposed to on dry land, or at the water's edge from the shore). */
	static boolean inWater(ServerLevel level, BlockPos p) {
		return LocalPathfinder.isWater(level, p) || LocalPathfinder.isWater(level, p.above());
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

	private static boolean nearWater(ServerLevel level, BlockPos p) {
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
			if (still(level, p.relative(d))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * With feet in {@code q}, the head would be in water that stays. Falling water isn't that: a
	 * cell in a waterfall is no place to mine from, but what's behind it counts as buried, to be got
	 * at through the rock with the head dry.
	 */
	private static boolean stillWater(BlockGetter level, BlockPos q) {
		return still(level, q) || still(level, q.above());
	}

	private static boolean still(BlockGetter level, BlockPos p) {
		var fluid = level.getFluidState(p);
		return fluid.is(FluidTags.WATER) && fluid.isSource();
	}

	/** With a face open to air or water (not buried in rock). */
	static boolean exposed(BlockGetter level, BlockPos block) {
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
			BlockPos n = block.relative(d);
			if (LocalPathfinder.isPassable(level, n) || LocalPathfinder.isWater(level, n)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A spot to mine {@code block} from: within reach, not in a protected chunk, if {@code mustSee}
	 * with it in sight, and if {@code headOut} with the head out of the water.
	 */
	public static PathGoal reachGoal(BlockPos block, double reach, double eyeHeight, boolean mustSee, boolean headOut) {
		Vec3 center = Vec3.atCenterOf(block);
		return (w, p) -> {
			if (p.equals(block) || ProtectionZones.isProtected(w, p)) {
				return false;
			}
			Vec3 eye = new Vec3(p.getX() + 0.5, p.getY() + eyeHeight, p.getZ() + 0.5);
			return eye.distanceTo(center) <= reach && (!mustSee || blockerOf(w, eye, block) == null)
					&& (!headOut || Air.breathable(w, p));
		};
	}

	/**
	 * What's in the line of sight from {@code eye} to the block, or null if nothing: the one check
	 * for both where to stand (goNear) and what to mine first (mine). They used to look differently
	 * (one at the blocks' collision boxes from the middle of the cell, the other at their outlines from
	 * the real eye), and it chose a spot to stand that the mining then found blocked.
	 */
	static BlockPos blockerOf(net.minecraft.world.level.BlockGetter world, Vec3 eye, BlockPos block) {
		BlockHitResult hit = world.clip(new ClipContext(eye, Vec3.atCenterOf(block), ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
		return hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(block) ? hit.getBlockPos() : null;
	}

}
