package com.vale.helperbot.path;

import net.minecraft.world.level.BlockGetter;
import com.vale.helperbot.guard.Guard;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * How long from a spot under water until the head is out, swimming: sideways (to a column with a
 * better way up: open water, the hole it came down through) and then straight up. With nothing but
 * a ceiling there is no way up, and that's what the planner goes by: it doesn't go where only
 * digging would get it out. (That's the breathing layer's last resort, for what plans can't foresee.)
 * What those two don't see, a tunnel going up at a slant, say, the cells around are gone through
 * for (see {@link #nearby}).
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
	/** Most cells {@link #nearby} goes through: about what a breath covers in a narrow tunnel. */
	private static final int NEARBY_CELLS = 512;
	private static final double UP_TICKS = 1 / Air.SWIM_UP_SPEED;

	private final BlockGetter level;
	private final Inventory inventory;
	/** How far up it can sprint-swim on its food: the first so many blocks are the fast ones. */
	private final double sprintBlocks;
	/** A block sideways. Only sprinting if the food lasts for any way across a field. */
	private final double sideTicks;
	private final Long2DoubleOpenHashMap upMemo = new Long2DoubleOpenHashMap();
	private final Long2DoubleOpenHashMap memo = new Long2DoubleOpenHashMap();
	/** Per tile and layer: ticks to air from each cell, swimming sideways first (no standing start). */
	private final Long2ObjectOpenHashMap<double[]> fields = new Long2ObjectOpenHashMap<>();

	/** See {@link #fromAbove}. */
	private boolean fromAbove;

	public Escape(BlockGetter level, Inventory inventory, Air.Profile air) {
		this.level = level;
		this.inventory = inventory;
		this.sprintBlocks = air.sprintBlocks();
		this.sideTicks = 1 / (sprintBlocks >= SIZE * 2 ? Air.SPRINT_SWIM_SPEED : LocalPathfinder.SWIM_SPEED);
		upMemo.defaultReturnValue(Double.NaN);
		memo.defaultReturnValue(Double.NaN);
	}

	/**
	 * For judging a job from outside the water: a one-block ceiling (ice on a pond) it would dig on
	 * the way in, standing on top, at the plain speed, and then it's open on the way out.
	 */
	public Escape fromAbove() {
		fromAbove = true;
		return this;
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
			// Straight up from here, or sideways first: from here, or from a neighbour (here may be too
			// tight to swim in).
			best = Math.min(up(feet), field(feet));
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				best = Math.min(best, sideTicks + field(feet.relative(dir)));
			}
			if (Double.isInfinite(best)) {
				best = nearby(feet);
			}
		}
		memo.put(key, best);
		return best;
	}

	/**
	 * With no way by swimming across and up: every cell around that it fits in, a block at a time, up
	 * and down as well, until the head is out. Layer by layer a stairway dug at a slant shows no way
	 * out, with the air a block or two behind it: it was told there was none on a full breath, and
	 * turned back for good. Charged at the slow speed, each block.
	 */
	private double nearby(BlockPos feet) {
		LongOpenHashSet seen = new LongOpenHashSet();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(feet);
		seen.add(feet.asLong());
		for (int steps = 0; !queue.isEmpty() && seen.size() < NEARBY_CELLS; steps++) {
			for (int n = queue.size(); n > 0; n--) {
				BlockPos c = queue.poll();
				for (int dy = -1; dy <= 1; dy++) {
					for (Direction dir : Direction.Plane.HORIZONTAL) {
						visit(c.relative(dir).above(dy), seen, queue);
					}
				}
				visit(c.above(), seen, queue);
				visit(c.below(), seen, queue);
			}
			for (BlockPos c : queue) {
				if (!wet(c.above())) {
					return (steps + 1) * UP_TICKS;
				}
			}
		}
		if (queue.isEmpty()) {
			// All there is, and no way out from any of it: no need to go through it again from each.
			seen.forEach(cell -> memo.put(cell, Double.POSITIVE_INFINITY));
		}
		return Double.POSITIVE_INFINITY;
	}

	private void visit(BlockPos c, LongOpenHashSet seen, ArrayDeque<BlockPos> queue) {
		if (open(c) && open(c.above()) && seen.add(c.asLong())) {
			queue.add(c);
		}
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
				f[idx] = up(c.immutable());
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
				double cost = e[0] + sideTicks;
				if (swim[nIdx] && cost < f[nIdx]) {
					f[nIdx] = cost;
					queue.add(new double[]{cost, nIdx});
				}
			}
		}
		return f;
	}

	/** Straight up from {@code feet}: all open water, but for a lid judged {@link #fromAbove}. */
	private double up(BlockPos feet) {
		long key = feet.asLong();
		double cached = upMemo.get(key);
		if (!Double.isNaN(cached)) {
			return cached;
		}
		double t = 0;
		BlockPos head = feet.above();
		double result = Double.POSITIVE_INFINITY;
		for (int i = 0; i < MAX_UP; i++) {
			if (!wet(head)) {
				result = t;
				break;
			}
			BlockPos next = head.above();
			boolean sprint = i < sprintBlocks && LocalPathfinder.isWater(level, head) && LocalPathfinder.isWater(level, next);
			if (!open(next)) {
				int dig = fromAbove && LocalPathfinder.isPassable(level, next.above()) ? digTicks(next) : -1;
				if (dig < 0) {
					break;
				}
				t += dig;
				sprint = false;
			}
			// Like the swim itself (see Air#submerged): the last block, head coming out, isn't sprinted.
			t += sprint ? 1 / Air.SPRINT_SWIM_SPEED : UP_TICKS;
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
		// Water right above, or above an air pocket over it (see Air#waterComing).
		if (LocalPathfinder.isWater(level, pos.above()) || Air.waterComing(level, pos)) {
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
