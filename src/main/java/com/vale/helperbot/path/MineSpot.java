package com.vale.helperbot.path;

import com.vale.helperbot.guard.ProtectionZones;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where a block can be mined from: the one reckoning for choosing a target (the planner, and
 * gathering's MineCost) and for walking to it (see {@link #reachGoal}), so they can't disagree
 * about what's in reach. It holds nothing, and knows only the world and the bot's reach.
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

	/**
	 * Mining it, is the head out of the water? Going by where it would stand, if there's anywhere
	 * near; buried, with nowhere to stand yet, digging a way there is the plan's business.
	 */
	public static boolean headOut(ServerLevel level, BlockPos p, double reach, double eyeHeight) {
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
	 * Can it mine {@code p} with its feet in {@code q}? By the same test the walk there goes by (see
	 * reachGoal). Arm's reach alone counted a kelp in a crack as near: it dived for it again and
	 * again, breath after breath, with a planner that could find nowhere to mine it from.
	 */
	public static boolean isSpot(BlockGetter level, BlockPos q, BlockPos p, Vec3 center, double reach, double eyeHeight, boolean mustSee) {
		Vec3 eye = eyeAt(level, q, eyeHeight);
		return !q.equals(p) && eye.distanceTo(center) <= reach && !Double.isNaN(LocalPathfinder.floorHeight(level, q))
				&& !ProtectionZones.isProtected(level, q) && (!mustSee || blockerOf(level, eye, p) == null);
	}

	/** In or under water (as opposed to on dry land, or at the water's edge from the shore). */
	public static boolean inWater(ServerLevel level, BlockPos p) {
		return LocalPathfinder.isWater(level, p) || LocalPathfinder.isWater(level, p.above());
	}

	public static boolean nearWater(ServerLevel level, BlockPos p) {
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
	public static boolean stillWater(BlockGetter level, BlockPos q) {
		return still(level, q) || still(level, q.above());
	}

	private static boolean still(BlockGetter level, BlockPos p) {
		var fluid = level.getFluidState(p);
		return fluid.is(FluidTags.WATER) && fluid.isSource();
	}

	/** With a face open to air or water (not buried in rock). */
	public static boolean exposed(BlockGetter level, BlockPos block) {
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
	/**
	 * Where the eye is, standing with the feet in {@code q}: over the floor it really stands on there,
	 * not the cell's bottom. On a dirt path (its top 15/16 up the cell) the eye is nearly a block higher
	 * than the cell's bottom says: an ore two down and two along was in reach by the plan (3.86) and out
	 * of it for the mining (4.63), which sent it back to the plan, which said it was there, every tick,
	 * for minutes. Where it can't stand (water), the cell's bottom.
	 */
	public static Vec3 eyeAt(BlockGetter level, BlockPos q, double eyeHeight) {
		double floor = LocalPathfinder.floorHeight(level, q);
		return new Vec3(q.getX() + 0.5, (Double.isNaN(floor) ? q.getY() : floor) + eyeHeight, q.getZ() + 0.5);
	}

	public static PathGoal reachGoal(BlockPos block, double reach, double eyeHeight, boolean mustSee, boolean headOut) {
		Vec3 center = Vec3.atCenterOf(block);
		return (w, p) -> {
			if (p.equals(block) || ProtectionZones.isProtected(w, p)) {
				return false;
			}
			Vec3 eye = eyeAt(w, p, eyeHeight);
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
	public static BlockPos blockerOf(net.minecraft.world.level.BlockGetter world, Vec3 eye, BlockPos block) {
		BlockHitResult hit = world.clip(new ClipContext(eye, Vec3.atCenterOf(block), ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
		return hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(block) ? hit.getBlockPos() : null;
	}

}
