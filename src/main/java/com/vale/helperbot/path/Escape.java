package com.vale.helperbot.path;

import net.minecraft.world.level.BlockGetter;
import com.vale.helperbot.guard.Guard;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * How long from a spot under water until the head is out: swim sideways (to a column with a
 * better way up: open water, the hole it came down through) and then straight up, digging through
 * whatever is overhead at the real under-water mining speed. A block dug under water lets the
 * water in, so the way up stays wet until it reaches air; a column of sand or gravel counts block
 * by block (each falls into the gap).
 *
 * <p>The sideways part is a shortest-path field per layer, built for a tile of columns at a time
 * (plus a margin, so ways up just across the tile's edge count too): under ice or a cave ceiling
 * the nearest opening can be well away, and a player swims back to it rather than digging up.
 * One instance memoises everything, so it's cheap to ask about many cells (the planner does).
 */
public final class Escape {
	/** Columns per tile side, and how far past its edges a tile's field looks for ways up. */
	private static final int TILE = 16, MARGIN = 12;
	private static final int SIZE = TILE + 2 * MARGIN;
	/** How far from a cell an estimate may look: sideways (a tile and its margin) and up. */
	public static final int REACH_SIDEWAYS = TILE + MARGIN, REACH_UP = 64 + 2;
	/** Give up looking up after this many blocks (deeper than any ocean). */
	private static final int MAX_UP = 64;
	private static final double UP_TICKS = 1 / Air.SWIM_UP_SPEED;
	private static final double SIDE_TICKS = 1 / LocalPathfinder.SWIM_SPEED;

	private final BlockGetter level;
	private final Inventory inventory;
	private final double waterDigFactor;
	private final Long2DoubleOpenHashMap upMemo = new Long2DoubleOpenHashMap();
	private final Long2DoubleOpenHashMap memo = new Long2DoubleOpenHashMap();
	/** Per tile and layer: ticks to air from each cell, swimming sideways first (no standing start). */
	private final Long2ObjectOpenHashMap<double[]> fields = new Long2ObjectOpenHashMap<>();

	public Escape(BlockGetter level, Inventory inventory, double waterDigFactor) {
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
		double best = 0;
		if (wet(feet.above())) {
			// Straight up from here (maybe standing on something while digging the first block),
			// or sideways first: from here, or from a neighbour (here may be too tight to swim in).
			best = Math.min(up(feet, true), field(feet));
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				best = Math.min(best, SIDE_TICKS + field(feet.relative(dir)));
			}
		}
		memo.put(key, best);
		return best;
	}

	private double field(BlockPos pos) {
		int tx = Math.floorDiv(pos.getX(), TILE), tz = Math.floorDiv(pos.getZ(), TILE);
		long key = BlockPos.asLong(tx, pos.getY(), tz);
		double[] f = fields.get(key);
		if (f == null) {
			f = buildField(tx, pos.getY(), tz);
			fields.put(key, f);
		}
		int x0 = tx * TILE - MARGIN, z0 = tz * TILE - MARGIN;
		return f[(pos.getX() - x0) * SIZE + (pos.getZ() - z0)];
	}

	/** Dijkstra over the open water of one layer, seeded with each column's way straight up. */
	private double[] buildField(int tx, int y, int tz) {
		int x0 = tx * TILE - MARGIN, z0 = tz * TILE - MARGIN;
		double[] f = new double[SIZE * SIZE];
		Arrays.fill(f, Double.POSITIVE_INFINITY);
		boolean[] swim = new boolean[SIZE * SIZE];
		PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
		BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
		for (int i = 0; i < SIZE; i++) {
			for (int j = 0; j < SIZE; j++) {
				c.set(x0 + i, y, z0 + j);
				if (!open(c) || !open(c.above())) {
					continue;
				}
				int idx = i * SIZE + j;
				swim[idx] = true;
				f[idx] = up(c.immutable(), false);
				if (f[idx] < Double.POSITIVE_INFINITY) {
					queue.add(new double[]{f[idx], idx});
				}
			}
		}
		while (!queue.isEmpty()) {
			double[] e = queue.poll();
			int idx = (int) e[1];
			if (e[0] > f[idx]) {
				continue;
			}
			int i = idx / SIZE, j = idx % SIZE;
			int[][] next = {{i + 1, j}, {i - 1, j}, {i, j + 1}, {i, j - 1}};
			for (int[] n : next) {
				if (n[0] < 0 || n[0] >= SIZE || n[1] < 0 || n[1] >= SIZE) {
					continue;
				}
				int nIdx = n[0] * SIZE + n[1];
				double cost = e[0] + SIDE_TICKS;
				if (swim[nIdx] && cost < f[nIdx]) {
					f[nIdx] = cost;
					queue.add(new double[]{cost, nIdx});
				}
			}
		}
		return f;
	}

	/** Straight up from feet in {@code feet}. {@code start}: still where it started (maybe standing on something). */
	private double up(BlockPos feet, boolean start) {
		long key = feet.asLong() * 2 + (start ? 1 : 0);
		double cached = upMemo.get(key);
		if (!Double.isNaN(cached)) {
			return cached;
		}
		// Only a block right over its head is dug standing: once it swims up, it's floating.
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
			}
			grounded = false;
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
		if (!Worlds.loaded(level, pos) || !Guard.canBreak(level, pos, state) || Guard.isDangerous(state)) {
			return -1;
		}
		int ticks = BreakTimes.estimateTicks(inventory, level, pos, state);
		return ticks == Integer.MAX_VALUE ? -1 : ticks;
	}

	/**
	 * Would this cell be water once it's dug out: water pouring in from above or beside, or ice,
	 * which turns into water when broken over anything solid or liquid (vanilla). Ice over a pond
	 * dug from below used to count as "head in the air"; it drowned digging it, floating.
	 */
	public static boolean floodsWhenDug(BlockGetter level, BlockPos pos) {
		if (LocalPathfinder.isWater(level, pos.above())) {
			return true;
		}
		if (level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.IceBlock) {
			BlockPos below = pos.below();
			if (level.getBlockState(below).blocksMotion() || !level.getFluidState(below).isEmpty()) {
				return true;
			}
		}
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			if (LocalPathfinder.isWater(level, pos.relative(dir))) {
				return true;
			}
		}
		return false;
	}
}
