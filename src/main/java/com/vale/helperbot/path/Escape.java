package com.vale.helperbot.path;

import com.vale.helperbot.guard.Guard;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * How long from a spot under water until the head is out: swim a little sideways (to a better
 * column) and then straight up, digging through whatever is overhead at the real under-water
 * mining speed. A block dug under water lets the water in, so the way up stays wet until it
 * reaches air; a column of sand or gravel counts block by block (each falls into the gap).
 *
 * <p>One instance memoises per column, so it's cheap to ask about many cells (the planner does).
 */
public final class Escape {
	/** How far sideways it looks for a better way up. */
	private static final int SIDE_RADIUS = 3;
	/** Give up looking up after this many blocks (deeper than any ocean). */
	private static final int MAX_UP = 64;
	private static final double UP_TICKS = 1 / Air.SWIM_UP_SPEED;
	private static final double SIDE_TICKS = 1 / LocalPathfinder.SWIM_SPEED;

	private final Level level;
	private final Inventory inventory;
	private final double waterDigFactor;
	private final Long2DoubleOpenHashMap upMemo = new Long2DoubleOpenHashMap();
	private final Long2DoubleOpenHashMap memo = new Long2DoubleOpenHashMap();

	public Escape(Level level, Inventory inventory, double waterDigFactor) {
		this.level = level;
		this.inventory = inventory;
		this.waterDigFactor = waterDigFactor;
		upMemo.defaultReturnValue(Double.NaN);
		memo.defaultReturnValue(Double.NaN);
	}

	/** Ticks to get the head into air with feet starting in {@code feet}; 0 if it already is, infinity if stuck. */
	public double ticks(BlockPos feet) {
		long key = feet.asLong();
		double cached = memo.get(key);
		if (!Double.isNaN(cached)) {
			return cached;
		}
		double best = Double.POSITIVE_INFINITY;
		if (!wet(feet.above())) {
			best = 0;
		} else {
			// Breadth-first through open water at this level, then up from each column reached.
			Map<BlockPos, Integer> dist = new HashMap<>();
			ArrayDeque<BlockPos> queue = new ArrayDeque<>();
			dist.put(feet, 0);
			queue.add(feet);
			while (!queue.isEmpty()) {
				BlockPos c = queue.poll();
				int d = dist.get(c);
				best = Math.min(best, d * SIDE_TICKS + up(c, d == 0));
				if (d * SIDE_TICKS >= best) {
					continue;
				}
				for (Direction dir : Direction.Plane.HORIZONTAL) {
					BlockPos n = c.relative(dir);
					if (Math.max(Math.abs(n.getX() - feet.getX()), Math.abs(n.getZ() - feet.getZ())) > SIDE_RADIUS
							|| dist.containsKey(n) || !open(n) || !open(n.above())) {
						continue;
					}
					dist.put(n, d + 1);
					queue.add(n);
				}
			}
		}
		memo.put(key, best);
		return best;
	}

	/** Straight up from feet in {@code feet}. {@code start}: still where it started (maybe standing on something). */
	private double up(BlockPos feet, boolean start) {
		long key = feet.asLong() * 2 + (start ? 1 : 0);
		double cached = upMemo.get(key);
		if (!Double.isNaN(cached)) {
			return cached;
		}
		boolean grounded = start && LocalPathfinder.isSupport(level, feet.below());
		double t = 0;
		BlockPos head = feet.above();
		double result = Double.POSITIVE_INFINITY;
		for (int i = 0; i < MAX_UP; i++) {
			if (!wet(head)) {
				result = t;
				break;
			}
			BlockPos next = head.above();
			if (!open(next)) {
				int dig = digTicks(next);
				if (dig < 0) {
					break;
				}
				// Under water it mines 5x slower, and 5x again once floating (vanilla).
				t += dig * waterDigFactor * (grounded ? 1 : 5);
				grounded = false;
			}
			t += UP_TICKS;
			head = next;
		}
		upMemo.put(key, result);
		return result;
	}

	/** Under water now, or would be once dug out (water next to it pours in). */
	private boolean wet(BlockPos pos) {
		if (LocalPathfinder.isWater(level, pos)) {
			return true;
		}
		return !LocalPathfinder.isPassable(level, pos) && floodsWhenDug(level, pos);
	}

	private boolean open(BlockPos pos) {
		return LocalPathfinder.isOpenWater(level, pos) || LocalPathfinder.isPassable(level, pos);
	}

	/** Ticks to dig this cell with the best tool (no penalties), or -1 if it can't or mustn't. */
	private int digTicks(BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!level.hasChunkAt(pos) || !Guard.canBreak(level, pos, state) || Guard.isDangerous(state)) {
			return -1;
		}
		int ticks = BreakTimes.estimateTicks(inventory, level, pos, state);
		return ticks == Integer.MAX_VALUE ? -1 : ticks;
	}

	/** Would water pour into this cell once it's dug out (water above or beside it)? */
	public static boolean floodsWhenDug(Level level, BlockPos pos) {
		if (LocalPathfinder.isWater(level, pos.above())) {
			return true;
		}
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			if (LocalPathfinder.isWater(level, pos.relative(dir))) {
				return true;
			}
		}
		return false;
	}
}
