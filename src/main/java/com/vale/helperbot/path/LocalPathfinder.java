package com.vale.helperbot.path;

import net.minecraft.world.level.BlockGetter;
import com.vale.helperbot.guard.Guard;
import com.vale.helperbot.guard.ProtectionZones;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

/**
 * A* over foot cells inside a small window around the bot. A node is the block cell that
 * contains the bot's feet; its "floor" is the exact height the feet rest at, so slabs, paths,
 * snow layers and carpets all work as ground.
 *
 * <p>When the goal is outside the window it returns a partial path to the node closest to the
 * target; the caller walks a few steps and plans again, which also picks up world changes.
 *
 * <p>A search is resumable: {@link #step(long)} runs it for a time budget, so large windows can
 * be spread over several ticks instead of stalling the server.
 */
public final class LocalPathfinder {
	/** Search window around the bot and how many nodes may be expanded in it. */
	public record Limits(int radiusXZ, int up, int down, int maxNodes) {
	}

	/** Escalation levels: tried in order when the smaller window finds nothing useful. */
	public static final Limits[] LEVELS = {
			new Limits(16, 8, 16, 40_000),
			new Limits(32, 16, 24, 100_000),
			new Limits(64, 24, 32, 200_000),
	};
	/**
	 * Under water the first try is a small cube: routes there are short anyway (air), the world
	 * changes fast (sand falls, water floods in, kelp breaks off), and the bot re-plans often.
	 * Each node also carries the air left, which multiplies the nodes; a small window keeps it cheap.
	 */
	public static final Limits[] WATER_LEVELS = {
			new Limits(8, 8, 8, 30_000),
			LEVELS[1],
			LEVELS[2],
	};

	public static Limits[] levels(boolean underwater) {
		return underwater ? WATER_LEVELS : LEVELS;
	}
	/** Vanilla auto step-up height. */
	public static final double STEP_HEIGHT = 0.6;
	/** Highest ledge a standing jump clears. */
	public static final double JUMP_HEIGHT = 1.25;
	private static final double PLAYER_HEIGHT = 1.8;
	/** Extra ticks a jump takes over walking. */
	private static final double ASCEND_EXTRA = 4.0;
	static final double BREAK_OVERHEAD = 3.0;
	/** Feet height above the cell bottom when floating at a water surface. */
	public static final double SWIM_FLOOR = 0.1;
	/** Vanilla swimming speed is roughly 0.1-0.15 blocks/tick. */
	public static final double SWIM_SPEED = 0.12;
	/** Deepest {@link #surfaceDistance} looks. (Diving itself is limited by air, see {@link Air}.) */
	public static final int MAX_DIVE_DEPTH = 10;
	/** Air is tracked in steps of this many ticks: two routes to a cell differing by less are the same. */
	private static final int AIR_BUCKET = 30;
	/** Prefer the surface: swimming under water costs more (air). */
	private static final double DIVE_COST_FACTOR = 1.4;
	/** Vanilla right-click interval (4 ticks), plus a small charge for using a block up. */
	private static final double PLACE_COST = 4 + 2;

	/**
	 * Weighted A* for partial searches: leans towards the target, expanding far fewer nodes.
	 * Paths cost at most this factor more than the best one (usually much less).
	 */
	private static final double PARTIAL_HEURISTIC_WEIGHT = 1.4;
	/** Expand at least this many nodes before a partial search may stop at the window edge. */
	private static final int EARLY_STOP_MIN_NODES = 1_500;

	/**
	 * @param ticks for each step, the real time it should take (no preferences): the walker gives
	 *              up on a step that takes far longer than this (see MovementController)
	 */
	public record Result(List<PathStep> steps, boolean reachesGoal, List<Double> ticks) {
		static final Result EMPTY = new Result(List.of(), false, List.of());
	}

	/**
	 * Cost factor for cells the previous path went through, when planning again towards the same
	 * goal: the new plan sticks to the old one unless something else is clearly better, instead of
	 * swinging to a different route at every re-plan (borrowed from Baritone).
	 */
	private static final double FAVOR_PREVIOUS = 0.5;
	/**
	 * Experimental: every step that ends in water (swimming, diving, digging under water) counts
	 * this many times its time. Water is slow, risky (air, drowning, drops floating off) and a
	 * player keeps to dry land when there's a reasonable way round; a much shorter swim still wins.
	 * Only the preference changes: air is still charged by real ticks.
	 */
	public static final double WATER_COST_FACTOR = 2.0;
	/**
	 * A partial path is picked by trading progress against cost at several weights (lowest first),
	 * taking the first that gets at least {@link #MIN_PARTIAL_BLOCKS} away from the start: not
	 * just "closest to the target", which could be an expensive dead end (borrowed from Baritone).
	 */
	private static final double[] PARTIAL_WEIGHTS = {1.5, 2, 2.5, 3, 4, 5, 10};
	private static final double MIN_PARTIAL_BLOCKS = 5;
	/** Long partial paths lose their last tenth: the far end, at the edge of what was searched, is the least sure part. */
	private static final int CUTOFF_MIN_STEPS = 30;
	private static final double CUTOFF_KEEP = 0.9;

	/**
	 * One cell reached with a given air bucket. While it waits in the open set a cheaper way there
	 * updates it in place (see {@link NodeHeap}); once expanded it is fixed, since later nodes
	 * point back to it, and a cheaper way after that makes a new node.
	 */
	private static final class Node {
		final BlockPos pos;
		double floor;
		double g;
		double f;
		Node parent;
		PathStep step;
		/** Air budget left on arrival here (see {@link Air}). */
		int air;
		/** Real ticks of the step that led here. */
		double ticks;
		/** Position in the open set, or -1 when not in it. */
		int heapIndex = -1;

		Node(BlockPos pos, double floor, double g, double h, Node parent, PathStep step, int air) {
			this.pos = pos;
			set(floor, g, h, parent, step, air);
		}

		void set(double floor, double g, double h, Node parent, PathStep step, int air) {
			this.floor = floor;
			this.g = g;
			this.f = g + h;
			this.parent = parent;
			this.step = step;
			this.air = air;
		}
	}

	/**
	 * A possible step. {@code cost} is what the search minimises: real time, plus preferences
	 * (stay near the surface, dig less in safe mode, avoid risky breaks). {@code ticks} is just the
	 * real time it takes, which is what air is charged with: preferences mustn't use up air that
	 * isn't really used (it made the bot think it couldn't get back up when it could).
	 */
	private record Candidate(PathStep step, double floor, double cost, double ticks) {
		Candidate(PathStep step, double floor, double cost) {
			this(step, floor, cost, cost);
		}
	}

	/** Real ticks of the digging priced by the {@link #clearCost} calls since it was last reset. */
	private double digTicks;

	private final BlockGetter level;
	private final Inventory inventory;
	private final Mode mode;
	private final int maxDrop;
	private final BlockPos origin;
	private final BlockPos target;
	/** The target's height is unknown (e.g. a far-away biome): steer by horizontal distance only. */
	private final boolean horizontalOnly;
	private final Limits limits;
	private final PathGoal goal;
	/** Cells the bot found blocked in practice (see MovementController#learnObstacle): treated as walls. */
	private final LongPredicate avoid;
	private final double walkCost;
	private final boolean canBuild;
	/** 0 = unknown, 1 = passable, 2 = blocked. */
	/** What this search has worked out about each cell, and its best nodes there (see {@link Cells}). */
	private final Cells cells = new Cells();

	/**
	 * The target is outside the window, so this search can only return a partial path. Exploring
	 * the whole window for the best possible one is the expensive part, and the partial path gets
	 * re-planned anyway, so these searches lean towards the target and stop early (see below).
	 * Searches whose target is inside the window stay exact.
	 */
	private final boolean partial;
	private final double heuristicWeight;
	/**
	 * What's left beyond the window. Only for partial searches: there it decides which edge of the
	 * window to head for (up to the surface to run, or on through the rock). Searches that can see
	 * the target keep plain running distance, which never overestimates.
	 */
	private final TravelEstimate travel;

	private final NodeHeap open = new NodeHeap();
	private final Air.Profile air;
	private final Escape escape;
	private final int buckets;
	private final Node startNode;
	private Node closest;
	private double closestH;
	private final double[] bestPartialValue = new double[PARTIAL_WEIGHTS.length];
	private final Node[] bestPartial = new Node[PARTIAL_WEIGHTS.length];
	/** Cells of the previous path towards the same goal (see {@link #FAVOR_PREVIOUS}). */
	private final LongPredicate favored;
	private double progress;
	private int expanded;
	private Result result;

	/**
	 * Prepares a search; call {@link #step(long)} until it returns true, then read {@link #result()}.
	 *
	 * @param startFloor the bot's actual feet Y
	 * @param maxDrop    highest drop onto solid ground allowed right now (already limited by health);
	 *                   drops into water are only limited by the search window
	 * @param air        the bot's breath: under water, only routes it can always get back up from
	 */
	public LocalPathfinder(BlockGetter level, Inventory inventory, Mode mode, int maxDrop, BlockPos start, double startFloor,
						   PathGoal goal, BlockPos target, boolean horizontalOnly, Limits limits,
						   LongPredicate avoid, Air.Profile air, LongPredicate favored) {
		this.avoid = avoid;
		this.favored = favored;
		this.air = air;
		this.escape = new Escape(level, inventory, air.waterDigFactor());
		this.buckets = air.limited() ? Math.max(1, Math.min(air.full(), Air.MAX_AIR * 4) / AIR_BUCKET + 1) : 1;
		this.level = level;
		this.inventory = inventory;
		this.mode = mode;
		this.maxDrop = maxDrop;
		this.origin = start;
		this.target = target;
		this.horizontalOnly = horizontalOnly;
		this.limits = limits;
		this.goal = goal;
		this.walkCost = 1.0 / mode.speed();
		this.canBuild = com.vale.helperbot.guard.Rules.open() && BuildingBlocks.count(inventory) > 0;
		this.partial = horizontalOnly
				? Math.max(Math.abs(target.getX() - start.getX()), Math.abs(target.getZ() - start.getZ())) > limits.radiusXZ()
				: !inWindow(target);
		this.heuristicWeight = partial ? PARTIAL_HEURISTIC_WEIGHT : 1.0;
		this.travel = new TravelEstimate(level, inventory, mode, target, horizontalOnly);

		// Starting with the head in air counts as a full breath: the bot tops up before diving.
		int startAir = Air.breathable(level, start) ? air.full() : air.now();
		startNode = new Node(start, startFloor, 0, heuristic(start) * heuristicWeight, null, null, startAir);
		open.add(startNode);
		cell(start)[bucket(startAir)] = startNode;
		closest = startNode;
		closestH = heuristic(start);
		java.util.Arrays.fill(bestPartialValue, closestH);
		java.util.Arrays.fill(bestPartial, startNode);
	}

	/** Runs the search for at most {@code budgetNanos}. Returns true once it has finished. */
	public boolean step(long budgetNanos) {
		if (result != null) {
			return true;
		}
		long deadline = System.nanoTime() + budgetNanos;
		while (!open.isEmpty() && expanded < limits.maxNodes()) {
			// Often enough that a slow stretch (under water, lots to dig) can't run far past the
			// budget: every 128 nodes it overshot to some 35 ms in a tick.
			if ((expanded & 15) == 0 && System.nanoTime() > deadline) {
				return false;
			}
			Node node = open.poll();
			if (goal.test(level, node.pos)) {
				result = reconstruct(node, true);
				progress = (heuristic(origin) - heuristic(node.pos)) / walkCost;
				return true;
			}
			expanded++;
			double h = heuristic(node.pos);
			if (h < closestH) {
				closestH = h;
				closest = node;
			}
			for (int i = 0; i < PARTIAL_WEIGHTS.length; i++) {
				double v = h + node.g / PARTIAL_WEIGHTS[i];
				if (v < bestPartialValue[i] - 0.01) {
					bestPartialValue[i] = v;
					bestPartial[i] = node;
				}
			}
			// A partial search has done its job once it reaches the edge of the window: going
			// further can't get closer to the target, only marginally improve the route here.
			if (partial && expanded >= EARLY_STOP_MIN_NODES && atWindowEdge(closest.pos)) {
				break;
			}
			for (Candidate c : neighbours(node.pos, node.floor)) {
				int airLeft = airAfter(node, c);
				if (airLeft < 0) {
					continue; // couldn't get back up from there in time
				}
				long key = c.step.to().asLong();
				double g = node.g + c.cost * (favored.test(key) ? FAVOR_PREVIOUS : 1)
						* (isWater(level, c.step.to()) ? WATER_COST_FACTOR : 1);
				int b = bucket(airLeft);
				Node[] nodes = cell(c.step.to());
				if (!dominated(nodes, b, g)) {
					double toGoal = heuristic(c.step.to()) * heuristicWeight;
					Node next = nodes[b];
					if (next != null && next.heapIndex >= 0) {
						next.set(c.floor, g, toGoal, node, c.step, airLeft); // still waiting: just cheaper now
						next.ticks = c.ticks;
						open.decreased(next);
					} else {
						next = new Node(c.step.to(), c.floor, g, toGoal, node, c.step, airLeft);
						next.ticks = c.ticks;
						nodes[b] = next;
						open.add(next);
					}
				}
			}
		}
		Node end = partialEnd();
		result = end == startNode ? Result.EMPTY : reconstruct(end, false);
		progress = (heuristic(origin) - heuristic(end.pos)) / walkCost;
		return true;
	}

	/** Where a path that doesn't reach the goal should end (see {@link #PARTIAL_WEIGHTS}). */
	private Node partialEnd() {
		for (Node n : bestPartial) {
			if (n.pos.distSqr(origin) >= MIN_PARTIAL_BLOCKS * MIN_PARTIAL_BLOCKS) {
				return n;
			}
		}
		return closest;
	}

	public Result result() {
		return result;
	}

	private boolean atWindowEdge(BlockPos pos) {
		int r = limits.radiusXZ() - 1;
		if (Math.abs(pos.getX() - origin.getX()) >= r || Math.abs(pos.getZ() - origin.getZ()) >= r) {
			return true;
		}
		int dy = pos.getY() - origin.getY();
		return !horizontalOnly && (dy >= limits.up() - 1 || -dy >= limits.down() - 1);
	}

	/** How many blocks closer to the target the path found gets us. */
	public double progressBlocks() {
		return progress;
	}

	public Limits limits() {
		return limits;
	}

	/**
	 * For the log when a search found nothing: how far it got, and what's around the start (each
	 * neighbouring cell, how many first steps there were and how many the air ruled out). Called
	 * once the search is over.
	 */
	public String diagnose() {
		StringBuilder sb = new StringBuilder();
		sb.append("搜了 ").append(expanded).append(" 格；起点 ").append(origin.toShortString())
				.append(String.format(java.util.Locale.ROOT, "（脚高 %.2f）", startNode.floor));
		if (level instanceof Snapshot snap) {
			sb.append("；").append(snap.describe());
		}
		List<Candidate> first = neighbours(origin, startNode.floor);
		int noAir = 0;
		for (Candidate c : first) {
			if (airAfter(startNode, c) < 0) {
				noAir++;
			}
		}
		sb.append("；起点的下一步 ").append(first.size()).append(" 个，因为气不够排除 ").append(noAir).append(" 个");
		sb.append(String.format(java.util.Locale.ROOT, "（气 %d/%d%s）", air.now(), air.full(), air.limited() ? "" : "，不限"));
		sb.append("；周围：");
		String[] names = {"上", "下", "北", "南", "西", "东"};
		Direction[] dirs = {Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
		for (int i = 0; i < dirs.length; i++) {
			sb.append(i == 0 ? "" : "，").append(names[i]).append(" ").append(describeCell(origin.relative(dirs[i])));
		}
		sb.append("，脚 ").append(describeCell(origin)).append("，头 ").append(describeCell(origin.above()));
		return sb.toString();
	}

	private String describeCell(BlockPos p) {
		if (!Worlds.loaded(level, p)) {
			return "未加载";
		}
		String avoided = avoid.test(p.asLong()) ? "（绕开中）" : "";
		BlockState state = level.getBlockState(p);
		if (isPassable(level, p)) {
			return "空" + avoided;
		}
		if (isWater(level, p)) {
			return "水" + avoided;
		}
		return state.getBlock().getName().getString() + (Guard.canBreak(level, p, state) ? "" : "（不能挖）") + avoided;
	}

	// ---- air ----------------------------------------------------------------------------

	/**
	 * Air budget left after taking this step, or -1 if the step isn't allowed: with the head under
	 * water at the end of it, there must still be enough to get back up from there (see
	 * {@link Air#reserve}). Ending with the head in air is a fresh breath. A step that digs a block
	 * next to water ends under water too, whatever the cell looks like now: the water pours in.
	 */
	private int airAfter(Node from, Candidate c) {
		BlockPos to = c.step.to();
		boolean floods = false;
		for (BlockPos b : c.step.toBreak()) {
			if (Escape.floodsWhenDug(level, b)) {
				floods = true;
				break;
			}
		}
		if (!floods && Air.breathable(level, to)) {
			return air.full();
		}
		if (!air.limited()) {
			return 0;
		}
		// Charged with the real time the step takes, not its cost (which includes preferences).
		int left = from.air - (int) Math.ceil(c.ticks);
		if (left < Air.reserve(escape.ticks(to)) + 20) {
			return -1;
		}
		return left;
	}

	private int bucket(int airLeft) {
		return Math.min(buckets - 1, Math.max(0, airLeft) / AIR_BUCKET);
	}

	/** Some route already reached this cell at no more cost with at least as much air left. */
	private static boolean dominated(Node[] nodes, int b, double g) {
		for (int i = b; i < nodes.length; i++) {
			if (nodes[i] != null && nodes[i].g <= g) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The best node so far for each air bucket of this cell. A route to a cell is only worth
	 * exploring if no other route got there at least as cheaply with at least as much air left.
	 */
	private Node[] cell(BlockPos pos) {
		Cells.Section s = cells.at(pos);
		int i = Cells.index(pos);
		if (s.nodes == null) {
			s.nodes = new Node[Cells.SIZE][];
		}
		Node[] nodes = s.nodes[i];
		if (nodes == null) {
			nodes = new Node[buckets];
			s.nodes[i] = nodes;
		}
		return nodes;
	}

	/**
	 * Per-cell memory of one search, kept in blocks of 16x8x16 cells, one array per thing known:
	 * neighbouring cells sit next to each other, and a lookup is an array index once the block is
	 * found (usually the same block as last time). It replaced a hash map per thing, keyed by cell.
	 * Arrays are made when first written; zero means "not worked out yet" in each.
	 */
	private static final class Cells {
		static final int SIZE = 16 * 8 * 16;

		static final class Section {
			/** 0 = unknown, 1 = passable, 2 = blocked. */
			byte[] passable;
			/** 0 = unknown, 1 = lava near, 2 = none. */
			byte[] lava;
			/** Ticks to break + 1; 0 = unknown, {@link #BREAK_NO} = may not be broken. */
			int[] breakTicks;
			/** Floor height, stored as a bit pattern + 1 so that zero stays "unknown". */
			long[] floor;
			Node[][] nodes;
		}

		/** Stored for "may not be broken": distinct from any real tick count. */
		static final int BREAK_NO = Integer.MIN_VALUE;

		private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
		private long lastKey = Long.MIN_VALUE;
		private Section last;

		Section at(BlockPos pos) {
			long key = ((long) (pos.getX() >> 4) & 0x3FFFFF) << 42
					| ((long) (pos.getZ() >> 4) & 0x3FFFFF) << 20
					| (long) (pos.getY() >> 3) & 0xFFFFF;
			if (key == lastKey) {
				return last;
			}
			Section s = sections.get(key);
			if (s == null) {
				s = new Section();
				sections.put(key, s);
			}
			lastKey = key;
			last = s;
			return s;
		}

		static int index(BlockPos pos) {
			return (pos.getX() & 15) | (pos.getZ() & 15) << 4 | (pos.getY() & 7) << 8;
		}
	}

	/**
	 * The open set: a binary min-heap on f where each node knows its slot, so a cheaper way to a
	 * waiting node moves it up in place. A plain priority queue can't, and kept a stale copy of
	 * the node for every improvement, to be skipped when it came out.
	 */
	private static final class NodeHeap {
		private Node[] heap = new Node[1024];
		private int size;

		boolean isEmpty() {
			return size == 0;
		}

		void add(Node n) {
			if (size == heap.length) {
				heap = java.util.Arrays.copyOf(heap, size * 2);
			}
			n.heapIndex = size;
			heap[size++] = n;
			up(n.heapIndex);
		}

		/** {@code n} is in the heap and its f just went down. */
		void decreased(Node n) {
			up(n.heapIndex);
		}

		Node poll() {
			Node top = heap[0];
			top.heapIndex = -1;
			Node last = heap[--size];
			heap[size] = null;
			if (size > 0) {
				heap[0] = last;
				last.heapIndex = 0;
				down(0);
			}
			return top;
		}

		private void up(int i) {
			Node n = heap[i];
			while (i > 0) {
				int parent = (i - 1) >>> 1;
				Node p = heap[parent];
				if (p.f <= n.f) {
					break;
				}
				heap[i] = p;
				p.heapIndex = i;
				i = parent;
			}
			heap[i] = n;
			n.heapIndex = i;
		}

		private void down(int i) {
			Node n = heap[i];
			int half = size >>> 1;
			while (i < half) {
				int child = 2 * i + 1;
				Node c = heap[child];
				int right = child + 1;
				if (right < size && heap[right].f < c.f) {
					child = right;
					c = heap[child];
				}
				if (n.f <= c.f) {
					break;
				}
				heap[i] = c;
				c.heapIndex = i;
				i = child;
			}
			heap[i] = n;
			n.heapIndex = i;
		}
	}

	/** Mining from feet in {@code p}: slower with the head under water, slower again floating. */
	private double digPenalty(BlockPos p) {
		return Air.digPenalty(level, p, air.waterDigFactor());
	}

	/** Ticks to fall {@code blocks} under vanilla gravity (0.08 blocks/tick²). */
	private static double fallTicks(double blocks) {
		return Math.sqrt(2 * blocks / 0.08);
	}

	private double heuristic(BlockPos pos) {
		return partial ? travel.ticks(pos) : travel.straight(pos);
	}

	private static Result reconstruct(Node node, boolean reachesGoal) {
		List<PathStep> steps = new ArrayList<>();
		List<Double> ticks = new ArrayList<>();
		for (Node n = node; n.step != null; n = n.parent) {
			steps.add(n.step);
			ticks.add(n.ticks);
		}
		Collections.reverse(steps);
		Collections.reverse(ticks);
		if (!reachesGoal && steps.size() >= CUTOFF_MIN_STEPS) {
			int keep = (int) (steps.size() * CUTOFF_KEEP);
			steps = new ArrayList<>(steps.subList(0, keep));
			ticks = new ArrayList<>(ticks.subList(0, keep));
		}
		return new Result(steps, reachesGoal, ticks);
	}

	// ---- neighbours ---------------------------------------------------------------------

	private List<Candidate> neighbours(BlockPos p, double cur) {
		List<Candidate> out = new ArrayList<>(16);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx != 0 || dz != 0) {
					walkInto(p, cur, dx, dz, out);
				}
			}
		}
		double pen = digPenalty(p);
		// In a protected chunk it's a ghost: it can pass through, but not dig or build from there.
		boolean canAct = !ProtectionZones.isProtected(level, p);
		if (isWater(level, p)) {
			swimInto(p, out, pen, canAct);
		}
		// Digging and building assume we stand on a full block (or hold still in water).
		if (canAct && Math.abs(cur - p.getY()) < 1e-3) {
			for (Direction d : Direction.Plane.HORIZONTAL) {
				digInto(p, d, out, pen);
				if (canBuild) {
					bridgeInto(p, d, out);
				}
			}
			if (canBuild) {
				pillarUp(p, out, pen);
			}
			BlockPos below = p.below();
			if (inWindow(below)) {
				List<BlockPos> breaks = new ArrayList<>(1);
				digTicks = 0;
				double dig = clearCost(below, breaks, pen);
				if (dig > 0 && isSupport(level, below.below()) && !nearLava(below)) {
					out.add(new Candidate(new PathStep(PathStep.Type.DESCEND, p, below, breaks), below.getY(),
							fallTicks(1) + dig, fallTicks(1) + digTicks));
				}
			}
		}
		return out;
	}

	/**
	 * From water, swim to any neighbouring water cell: level, diving down, or rising (3D). Also
	 * dig straight up through a ceiling (sand that fell in, the sea floor from below).
	 */
	private void swimInto(BlockPos p, List<Candidate> out, double pen, boolean canDig) {
		BlockPos up = p.above();
		BlockPos overHead = up.above();
		if (canDig && inWindow(overHead) && swimmable(up) && !swimmable(overHead)) {
			List<BlockPos> breaks = new ArrayList<>(1);
			digTicks = 0;
			double dig = clearCost(overHead, breaks, pen);
			if (dig > 0) {
				// Once dug, water pours in (or it's air, if the ceiling was the last thing in the way).
				double f = Escape.floodsWhenDug(level, overHead) || isWater(level, up) ? up.getY() : up.getY() + SWIM_FLOOR;
				out.add(new Candidate(new PathStep(PathStep.Type.WALK, p, up, breaks), f,
						dig + 1 / Air.SWIM_UP_SPEED, digTicks + 1 / Air.SWIM_UP_SPEED));
			}
		}
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					if ((dx == 0 && dy == 0 && dz == 0) || (dx != 0 && dz != 0)) {
						continue;
					}
					BlockPos c = p.offset(dx, dy, dz);
					if (!inWindow(c) || !isWater(level, c)) {
						continue;
					}
					// Moving up/down and sideways at once: both corner cells must be open too.
					if (dy != 0 && (dx != 0 || dz != 0)
							&& !(swimmable(p.offset(dx, 0, dz)) && swimmable(p.offset(0, dy, 0)))) {
						continue;
					}
					double f = floor(c);
					if (Double.isNaN(f)) {
						continue;
					}
					boolean submerged = isWater(level, c.above());
					double ticks = Math.sqrt(dx * dx + dy * dy + dz * dz) / SWIM_SPEED;
					out.add(new Candidate(new PathStep(PathStep.Type.WALK, p, c, List.of()), f,
							ticks * (submerged ? DIVE_COST_FACTOR : 1), ticks));
				}
			}
		}
	}

	private boolean swimmable(BlockPos pos) {
		return passable(pos) || isOpenWater(level, pos);
	}

	/** Moves into a neighbouring column without digging: walk, step up, jump up, or drop. */
	private void walkInto(BlockPos p, double cur, int dx, int dz, List<Candidate> out) {
		boolean diagonal = dx != 0 && dz != 0;
		BlockPos col = p.offset(dx, 0, dz);
		if (diagonal && !(columnOpen(p.offset(dx, 0, 0)) && columnOpen(p.offset(0, 0, dz)))) {
			return;
		}
		boolean entryClear = passable(col) && passable(col.above());
		int top = (int) Math.floor(cur + JUMP_HEIGHT);
		// Scan the whole window down: solid landings are capped by maxDrop below, water isn't.
		for (int y = top; y >= origin.getY() - limits.down(); y--) {
			BlockPos c = new BlockPos(col.getX(), y, col.getZ());
			if (!inWindow(c)) {
				continue;
			}
			double f = floor(c);
			if (!Double.isNaN(f)) {
				double dy = f - cur;
				boolean intoWater = isWater(level, c);
				if (dy > JUMP_HEIGHT || (dy < -STEP_HEIGHT && !entryClear) || (diagonal && Math.abs(dy) > STEP_HEIGHT)
						|| (-dy > maxDrop + 0.01 && !intoWater)) {
					return;
				}
				PathStep.Type type;
				boolean swimming = intoWater || isWater(level, p);
				double cost = (swimming ? 1.0 / SWIM_SPEED : walkCost) * (diagonal ? Math.sqrt(2) : 1);
				if (dy > STEP_HEIGHT) {
					// Going up a stair from its low side is two half steps, no jump: headroom for
					// standing up a block higher is enough. Anything else is jumped, and a jump arcs
					// higher than the ledge. (Stairs used to count as a jump too, so under a low
					// ceiling, a cellar staircase say, it could walk down but never back up.)
					boolean stair = !diagonal && stairStep(level, c, dx, dz) && dy <= 1 + 1e-3;
					int headTop = (int) Math.floor(cur + (stair ? dy : JUMP_HEIGHT) + PLAYER_HEIGHT - 1e-4);
					for (int hy = p.getY() + 1; hy <= headTop; hy++) {
						if (!passable(new BlockPos(p.getX(), hy, p.getZ()))) {
							return;
						}
					}
					type = PathStep.Type.ASCEND;
					cost += stair ? 0 : ASCEND_EXTRA;
				} else if (dy < -STEP_HEIGHT) {
					type = PathStep.Type.DESCEND;
					double drop = -dy;
					// Water breaks the fall, so only solid landings pay for damage.
					cost += fallTicks(drop) + (intoWater ? 0 : Math.max(0, drop - 3) * 10);
				} else {
					type = PathStep.Type.WALK;
				}
				out.add(new Candidate(new PathStep(type, p, c, List.of()), f, cost));
				return;
			}
			if (!passable(c) && y <= p.getY()) {
				return;
			}
		}
	}

	private boolean columnOpen(BlockPos pos) {
		return passable(pos) && passable(pos.above());
	}

	/** Moves that need mining first: tunnel forward, dig a step up, dig a stair down. */
	private void digInto(BlockPos p, Direction d, List<Candidate> out, double pen) {
		BlockPos t = p.relative(d);
		if (!inWindow(t)) {
			return;
		}
		// Tunnel forward.
		List<BlockPos> breaks = new ArrayList<>(2);
		digTicks = 0;
		double dig = clearCost(t, breaks, pen) + clearCost(t.above(), breaks, pen);
		if (dig >= 0 && !breaks.isEmpty() && isSupport(level, t.below()) && !nearLava(t)) {
			out.add(new Candidate(new PathStep(PathStep.Type.WALK, p, t, breaks), t.getY(), walkCost + dig, walkCost + digTicks));
		}

		// Dig out a step and climb onto it.
		BlockPos up = t.above();
		breaks = new ArrayList<>(3);
		digTicks = 0;
		dig = clearCost(p.above(2), breaks, pen) + clearCost(up, breaks, pen) + clearCost(up.above(), breaks, pen);
		if (dig >= 0 && !breaks.isEmpty() && isSupport(level, t) && !nearLava(up)) {
			out.add(new Candidate(new PathStep(PathStep.Type.ASCEND, p, up, breaks), up.getY(),
					walkCost + ASCEND_EXTRA + dig, walkCost + ASCEND_EXTRA + digTicks));
		}

		// Dig a stair down.
		BlockPos down = t.below();
		breaks = new ArrayList<>(3);
		digTicks = 0;
		dig = clearCost(t.above(), breaks, pen) + clearCost(t, breaks, pen) + clearCost(down, breaks, pen);
		if (dig >= 0 && !breaks.isEmpty() && isSupport(level, down.below()) && !nearLava(down)) {
			out.add(new Candidate(new PathStep(PathStep.Type.DESCEND, p, down, breaks), down.getY(),
					walkCost + fallTicks(1) + dig, walkCost + fallTicks(1) + digTicks));
		}
	}

	/**
	 * Place a block in front, below foot level, and walk onto it (over a gap or water).
	 *
	 * <p>No "is there something to place against" check against the current world: we only get
	 * here standing on a full block, and the new block goes right next to it. That block may be
	 * one this same path places a step earlier (a bridge or pillar still in the plan), which the
	 * world doesn't contain yet; checking the world would stop every bridge after its first block.
	 */
	private void bridgeInto(BlockPos p, Direction d, List<Candidate> out) {
		BlockPos t = p.relative(d);
		BlockPos under = t.below();
		if (!inWindow(t) || !passable(t) || !passable(t.above()) || !Double.isNaN(floor(t))
				|| !Guard.canPlace(level, under) || nearLava(t)) {
			return;
		}
		out.add(new Candidate(new PathStep(PathStep.Type.WALK, p, t, List.of(), under), t.getY(), walkCost + PLACE_COST));
	}

	/** Jump and place a block under ourselves. */
	private void pillarUp(BlockPos p, List<Candidate> out, double pen) {
		BlockPos up = p.above();
		if (!inWindow(up) || !Guard.canPlace(level, p) || isWater(level, p)) {
			return;
		}
		List<BlockPos> breaks = new ArrayList<>(1);
		digTicks = 0;
		double dig = clearCost(p.above(2), breaks, pen);
		if (dig >= 0 && !nearLava(up)) {
			out.add(new Candidate(new PathStep(PathStep.Type.PILLAR, p, up, breaks, p), up.getY(),
					ASCEND_EXTRA + PLACE_COST + dig, ASCEND_EXTRA + PLACE_COST + digTicks));
		}
	}

	/**
	 * Is standing in {@code onto} reached by walking up a stair, moving (dx, dz)? The block under
	 * it is a bottom-half stair rising that way: its lower half is a half step, then the top.
	 */
	public static boolean stairStep(BlockGetter level, BlockPos onto, int dx, int dz) {
		BlockState state = level.getBlockState(onto.below());
		if (!(state.getBlock() instanceof net.minecraft.world.level.block.StairBlock)
				|| state.getValue(net.minecraft.world.level.block.StairBlock.HALF) != net.minecraft.world.level.block.state.properties.Half.BOTTOM) {
			return false;
		}
		Direction up = state.getValue(net.minecraft.world.level.block.StairBlock.FACING);
		return up.getStepX() == dx && up.getStepZ() == dz;
	}

	public static boolean isWater(BlockGetter level, BlockPos pos) {
		return level.getFluidState(pos).is(FluidTags.WATER);
	}

	private boolean inWindow(BlockPos pos) {
		return Math.abs(pos.getX() - origin.getX()) <= limits.radiusXZ()
				&& Math.abs(pos.getZ() - origin.getZ()) <= limits.radiusXZ()
				&& pos.getY() - origin.getY() <= limits.up()
				&& origin.getY() - pos.getY() <= limits.down();
	}

	// ---- terrain queries ----------------------------------------------------------------

	/**
	 * Height the feet rest at when standing with feet in cell {@code c}, or NaN if the bot
	 * can't stand there (no ground, no headroom, liquid, hazard, or a block it must not step on).
	 */
	public static double floorHeight(BlockGetter level, BlockPos c) {
		if (!Worlds.loaded(level, c)) {
			return Double.NaN;
		}
		BlockState state = level.getBlockState(c);
		boolean empty = BlockTable.empty(state, level, c);
		double base;
		if (empty && isWater(level, c)) {
			if (BlockTable.dangerous(state)) {
				return Double.NaN;
			}
			if (isWater(level, c.above())) {
				// Fully under water. How deep it may go is up to its air (see Air), not a fixed limit.
				return isOpenWater(level, c.above()) ? c.getY() : Double.NaN;
			}
			// Swimming at the surface, head out of the water.
			base = c.getY() + SWIM_FLOOR;
		} else if (empty) {
			if (!isPassable(level, c) || !isSupport(level, c.below())) {
				return Double.NaN;
			}
			base = c.getY();
		} else {
			// A low block in the feet cell (slab, path, snow layer, carpet) is the ground itself.
			double top = BlockTable.top(state, level, c);
			if (top > 0.9375 + 1e-4 || !BlockTable.clean(state)) {
				return Double.NaN;
			}
			base = c.getY() + top;
		}
		int headTop = (int) Math.floor(base + PLAYER_HEIGHT - 1e-4);
		for (int y = c.getY() + 1; y <= headTop; y++) {
			if (!isPassable(level, new BlockPos(c.getX(), y, c.getZ()))) {
				return Double.NaN;
			}
		}
		return base;
	}

	/** Feet height when swimming in water cell {@code c}: at the surface, or fully under. */
	public static double swimHeight(BlockGetter level, BlockPos c) {
		return isWater(level, c.above()) ? c.getY() : c.getY() + SWIM_FLOOR;
	}

	/** Water the bot can swim through: no collision, no bubble column or other hazard. */
	public static boolean isOpenWater(BlockGetter level, BlockPos pos) {
		if (!Worlds.loaded(level, pos) || !isWater(level, pos)) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		return BlockTable.empty(state, level, pos) && !BlockTable.dangerous(state);
	}

	/**
	 * Blocks of water to swim straight up from {@code pos} before reaching breathable air,
	 * or -1 if that's more than {@link #MAX_DIVE_DEPTH} or blocked.
	 */
	public static int surfaceDistance(BlockGetter level, BlockPos pos) {
		for (int d = 0; d <= MAX_DIVE_DEPTH; d++) {
			BlockPos p = pos.above(d);
			if (isPassable(level, p)) {
				return d;
			}
			if (!isOpenWater(level, p)) {
				return -1;
			}
		}
		return -1;
	}

	public static boolean isPassable(BlockGetter level, BlockPos pos) {
		if (!Worlds.loaded(level, pos)) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		if (!BlockTable.empty(state, level, pos) || !BlockTable.clean(state)) {
			return false;
		}
		// Fences and walls are 1.5 blocks tall: the one below sticks half a block up into this cell.
		BlockPos below = pos.below();
		return BlockTable.top(level.getBlockState(below), level, below) <= 1.0 + 1e-6;
	}

	/** A full-height block that can be stood on. */
	public static boolean isSupport(BlockGetter level, BlockPos pos) {
		if (!Worlds.loaded(level, pos)) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		return !BlockTable.empty(state, level, pos)
				&& BlockTable.top(state, level, pos) >= 0.999
				&& BlockTable.clean(state);
	}

	private double floor(BlockPos c) {
		Cells.Section s = cells.at(c);
		int i = Cells.index(c);
		if (s.floor == null) {
			s.floor = new long[Cells.SIZE];
		}
		long stored = s.floor[i];
		if (stored != 0) {
			return Double.longBitsToDouble(stored - 1);
		}
		double floor = floorHeight(level, c);
		if (!Double.isNaN(floor) && (nearLava(c) || avoid.test(c.asLong()) || avoid.test(c.above().asLong()))) {
			floor = Double.NaN;
		}
		s.floor[i] = Double.doubleToLongBits(floor) + 1;
		return floor;
	}

	private boolean passable(BlockPos pos) {
		Cells.Section s = cells.at(pos);
		int i = Cells.index(pos);
		if (s.passable == null) {
			s.passable = new byte[Cells.SIZE];
		}
		byte cached = s.passable[i];
		if (cached == 0) {
			cached = isPassable(level, pos) && !avoid.test(pos.asLong()) ? (byte) 1 : (byte) 2;
			s.passable[i] = cached;
		}
		return cached == 1;
	}

	/**
	 * Ticks (scaled by mode) to clear this cell, 0 if already open, or a large negative
	 * number if it may not be cleared. Adds the cell to {@code breaks} when mining is needed.
	 */
	private double clearCost(BlockPos pos, List<BlockPos> breaks, double penalty) {
		if (passable(pos)) {
			return 0;
		}
		Cells.Section s = cells.at(pos);
		int cellIndex = Cells.index(pos);
		if (s.breakTicks == null) {
			s.breakTicks = new int[Cells.SIZE];
		}
		int stored = s.breakTicks[cellIndex];
		int ticks = stored == Cells.BREAK_NO ? -1 : stored - 1;
		if (stored == 0) {
			BlockState state = level.getBlockState(pos);
			// Liquids and other non-solid cells can't be mined out of the way.
			boolean allowed = Worlds.loaded(level, pos)
					&& !BlockTable.empty(state, level, pos)
					&& Guard.canBreak(level, pos, state)
					&& !BlockTable.dangerous(state);
			ticks = allowed ? BreakTimes.estimateTicks(inventory, level, pos, state) : -1;
			if (ticks == Integer.MAX_VALUE) {
				ticks = -1;
			}
			s.breakTicks[cellIndex] = ticks < 0 ? Cells.BREAK_NO : ticks + 1;
		}
		if (ticks < 0) {
			return -1e9;
		}
		breaks.add(pos);
		// Sand or gravel overhead falls into the gap, one block after another: each must be dug too.
		double fallers = 0;
		BlockPos above = pos.above();
		for (int i = 0; i < 16 && level.getBlockState(above).getBlock() instanceof FallingBlock; i++) {
			int t = BreakTimes.estimateTicks(inventory, level, above, level.getBlockState(above));
			fallers += t == Integer.MAX_VALUE ? 0 : t + BREAK_OVERHEAD;
			above = above.above();
		}
		double real = (ticks + BREAK_OVERHEAD + fallers) * penalty;
		digTicks += real;
		return real * mode.digCostFactor + wear(pos) + Guard.breakRiskCost(level, pos);
	}

	/** Tool wear of digging this cell (a preference, not time: see {@link BreakTimes#wearCost}). */
	private double wear(BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return wearCache.computeIfAbsent(state, s -> BreakTimes.wearCost(inventory, s));
	}

	private final java.util.Map<BlockState, Double> wearCache = new java.util.HashMap<>();

	private boolean nearLava(BlockPos feet) {
		Cells.Section s = cells.at(feet);
		int i = Cells.index(feet);
		if (s.lava == null) {
			s.lava = new byte[Cells.SIZE];
		}
		int cached = s.lava[i];
		if (cached == 0) {
			cached = 2;
			int m = mode.lavaMargin;
			search:
			for (int dx = -m; dx <= m; dx++) {
				for (int dz = -m; dz <= m; dz++) {
					for (int dy = -1; dy <= 2; dy++) {
						if (level.getFluidState(feet.offset(dx, dy, dz)).is(FluidTags.LAVA)) {
							cached = 1;
							break search;
						}
					}
				}
			}
			s.lava[i] = (byte) cached;
		}
		return cached == 1;
	}
}
