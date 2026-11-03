package com.vale.helperbot.path;

import net.minecraft.world.level.BlockGetter;
import com.vale.helperbot.knowledge.Habitat;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Rough ticks left to a far target from some cell, for what the path search can't see: beyond
 * its window. From underground there are two ways on: tunnel the whole way at digging speed, or
 * climb to the surface, run, and dig down again at the far end. The cheaper one counts, so going
 * up pays off by itself on long trips and doesn't on short ones; there's no "go up past N blocks"
 * rule. (It used to assume running speed for the rest of the way wherever it was, so climbing
 * looked like cost for nothing, and it tunnelled a thousand blocks with its pickaxe.)
 *
 * <p>All in real ticks, like path costs, with the same estimates for walking and digging.
 */
public final class TravelEstimate {
	/** Within this of the surface counts as on it (standing on it, or in a shallow dip). */
	private static final int SURFACE_SLACK = 2;
	/** Climbing one block up (or down) through rock: a step dug out of three blocks, and the jump. */
	private static final int CLIMB_BLOCKS = 3;
	private static final double CLIMB_EXTRA = 4;

	private final BlockGetter level;
	private final BlockPos target;
	private final boolean horizontalOnly;
	private final double walk;
	/** Per block of tunnel: walking it plus digging feet and head (a typical stone, best tool). */
	private final double tunnel;
	/** Per block climbed or descended through rock. */
	private final double climb;
	private final Long2IntOpenHashMap surface = new Long2IntOpenHashMap();

	public TravelEstimate(BlockGetter level, Inventory inventory, Mode mode, BlockPos target, boolean horizontalOnly) {
		this.level = level;
		this.target = target;
		this.horizontalOnly = horizontalOnly;
		this.walk = 1.0 / mode.speed();
		BlockState stone = Blocks.STONE.defaultBlockState();
		int ticks = BreakTimes.estimateTicks(inventory, level, target, stone);
		double dig = (ticks == Integer.MAX_VALUE ? 1000 : ticks + LocalPathfinder.BREAK_OVERHEAD) * mode.digCostFactor
				+ BreakTimes.wearCost(inventory, stone);
		this.tunnel = walk + 2 * dig;
		this.climb = walk + CLIMB_EXTRA + CLIMB_BLOCKS * dig;
		surface.defaultReturnValue(Integer.MIN_VALUE);
	}

	/** Running speed the whole way: what the search assumed before (and still does near the target). */
	public double straight(BlockPos from) {
		return distance(from) * walk;
	}

	/**
	 * Ticks left from {@code from}: the cheaper of tunnelling on, or up (if underground), across
	 * on the surface and down to the target (if it's underground). The same sum everywhere, so it
	 * goes down steadily while digging towards a deep target: on the surface it used to count only
	 * the run there, and every block dug down made the estimate jump up instead.
	 */
	public double ticks(BlockPos from) {
		double horizontal = Math.sqrt(sq(from.getX() - target.getX()) + sq(from.getZ() - target.getZ()));
		int depth = underground(from);
		// Going down at the far end only if the target is underground itself (known height).
		int targetDepth = horizontalOnly ? 0 : underground(target);
		double overland = (depth + targetDepth) * climb + horizontal * walk;
		return Math.min(distance(from) * tunnel, overland);
	}

	/** Blocks below the surface, 0 when on it (or close enough: standing in a dip). */
	private int underground(BlockPos p) {
		int depth = depth(p);
		return depth <= SURFACE_SLACK ? 0 : depth;
	}

	private double distance(BlockPos from) {
		double dx = from.getX() - target.getX(), dz = from.getZ() - target.getZ();
		double dy = horizontalOnly ? 0 : from.getY() - target.getY();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Columns this far off are looked at too: see {@link #depth}. */
	private static final int AROUND = 2;

	/**
	 * Blocks below the surface at this column (0 or less: on or above it). The surface is the
	 * highest of this column and a few around it: a shaft it dug itself (or any narrow hole) isn't
	 * the ground level there. Reading the column alone, its own shaft made it "always on the
	 * surface" while digging down.
	 */
	private int depth(BlockPos p) {
		long key = BlockPos.asLong(p.getX(), 0, p.getZ());
		int y = surface.get(key);
		if (y == Integer.MIN_VALUE) {
			y = Worlds.surfaceY(level, p.getX(), p.getZ());
			for (int[] o : new int[][]{{AROUND, 0}, {-AROUND, 0}, {0, AROUND}, {0, -AROUND}}) {
				y = Math.max(y, Worlds.surfaceY(level, p.getX() + o[0], p.getZ() + o[1]));
			}
			surface.put(key, y);
		}
		return y - p.getY();
	}

	private static double sq(double v) {
		return v * v;
	}
}
