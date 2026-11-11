package com.vale.helperbot.path;

import net.minecraft.world.level.BlockGetter;
import com.vale.helperbot.guard.Guard;
import com.vale.helperbot.guard.Placement;
import com.vale.helperbot.guard.ProtectionZones;
import com.vale.helperbot.path.moves.Move;
import com.vale.helperbot.path.moves.Moves;
import com.vale.helperbot.path.moves.Planner;
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
import java.util.function.ToDoubleFunction;

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
public final class LocalPathfinder implements Planner {
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
	 * Under water the first try is a cube of 16 each way: about as far as one breath goes (sprint-
	 * swimming), and tall enough to take in the surface from most depths, so the plan can route up for
	 * a breath itself (see breathe). It was 8: from 10 down the surface was outside, and only the
	 * breathing layer could take it up. Each node also carries the air left, which multiplies the
	 * nodes; searches run in the background, so the main thread pays only for copying the blocks.
	 */
	public static final Limits[] WATER_LEVELS = {
			new Limits(16, 16, 16, 40_000),
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
	public static final double PLAYER_HEIGHT = 1.8;
	/** Extra ticks a jump takes over walking. */
	public static final double ASCEND_EXTRA = 4.0;
	public static final double BREAK_OVERHEAD = 3.0;
	/** Within this above a floor it is on it: the one measure, for the body (see Body#isGrounded) and the search alike. */
	public static final double ON_GROUND = 0.03;
	/** Feet height above the cell bottom when floating at a water surface. */
	public static final double SWIM_FLOOR = 0.1;
	/** Vanilla swimming speed is roughly 0.1-0.15 blocks/tick. */
	public static final double SWIM_SPEED = Air.SWIM_SPEED;
	/** Air is tracked in steps of this many ticks: two routes to a cell differing by less are the same. */
	private static final int AIR_BUCKET = 30;
	/**
	 * A {@link #reachOnly} search goes on from whatever has the most air left, a tick of air outweighing
	 * any cost. So a cell first comes up with the best it will get, and is gone through once: going
	 * by cost, a wide sea took seven goes at each cell, each with a little more air than the last.
	 */
	private static final double MOST_AIR_FIRST = 1e6;
	/** Vanilla right-click interval (4 ticks), plus a small charge for using a block up. */
	public static final double PLACE_COST = 4 + 2;

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
	/** {@code air}: the breath budget the plan expects on arriving at the end of each step (see Air). */
	public record Result(List<PathStep> steps, boolean reachesGoal, List<Double> ticks, List<Integer> air) {
		static final Result EMPTY = new Result(List.of(), false, List.of(), List.of());
	}

	/**
	 * Cost factor for cells the previous path went through, when planning again towards the same
	 * goal: the new plan sticks to the old one unless something else is clearly better, instead of
	 * swinging to a different route at every re-plan (borrowed from Baritone).
	 */
	private static final double FAVOR_PREVIOUS = 0.5;
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
		/** The floor this route dug through into water, if it did (see {@link #wayUp}). */
		BlockPos hole;
		/** Building blocks left when it gets here: a route can't lay more than it has. */
		int blocks;
		/** Cells near here this route dug that are water by now (see {@link Wet}). */
		Wet wet;
		/** Its last step is the work in place the goal asked for (see {@link #workHere}): reaching it is the end. */
		boolean finished;

		Node(BlockPos pos, double floor, double g, double h, Node parent, PathStep step, int air) {
			this.pos = pos;
			set(floor, g, h, parent, step, air);
		}

		void set(double floor, double g, double h, Node parent, PathStep step, int air) {
			this.floor = floor;
			this.g = g;
			this.f = g + h;
			this.hole = parent == null ? null : holeAfter(parent, step);
			this.parent = parent;
			this.step = step;
			this.air = air;
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
	 * Goes through the window to the end, however far: no stopping at its edge, no leaning. If the
	 * goal isn't in it, the path ends at the cell that was best by {@link #ahead}. For places that
	 * can't be dug through, where the way that looks best is what ends in a dead end.
	 */
	private final boolean whole;
	/** What's left to go from a cell, if the caller knows better than the distance to the target. */
	private final ToDoubleFunction<BlockPos> ahead;
	/**
	 * Can it get there at all, with the way as it is: it digs and builds nothing (but for ice over
	 * water, a lid and not the lie of the land), and of the routes to a cell only the one with the
	 * most air goes on, whatever it cost. With nothing to dig the cells are few and it can finish.
	 */
	private final boolean reachOnly;
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
	private int blocksShort;
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
		this(level, inventory, mode, maxDrop, start, startFloor, goal, target, horizontalOnly, limits, avoid, air, favored, false, null, false);
	}

	/** @param whole see {@link #whole}; @param ahead see {@link #ahead}, or null; @param reachOnly see {@link #reachOnly} */
	public LocalPathfinder(BlockGetter level, Inventory inventory, Mode mode, int maxDrop, BlockPos start, double startFloor,
						   PathGoal goal, BlockPos target, boolean horizontalOnly, Limits limits,
						   LongPredicate avoid, Air.Profile air, LongPredicate favored,
						   boolean whole, ToDoubleFunction<BlockPos> ahead, boolean reachOnly) {
		this.whole = whole;
		this.reachOnly = reachOnly;
		this.ahead = ahead;
		this.avoid = avoid;
		this.favored = favored;
		this.air = air;
		this.escape = new Escape(level, inventory, air);
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
		this.canBuild = !reachOnly && com.vale.helperbot.guard.Rules.open() && BuildingBlocks.count(inventory) > 0;
		this.partial = !whole && (horizontalOnly
				? Math.max(Math.abs(target.getX() - start.getX()), Math.abs(target.getZ() - start.getZ())) > limits.radiusXZ()
				: !inWindow(target));
		this.heuristicWeight = partial ? PARTIAL_HEURISTIC_WEIGHT : 1.0;
		this.travel = new TravelEstimate(level, inventory, mode, target, horizontalOnly);

		// The breath it really has: where it needs more before going under, the plan says so (see breathe).
		int startAir = air.now();
		startNode = new Node(start, startFloor, 0, heuristic(start) * heuristicWeight, null, null, startAir);
		startNode.blocks = BuildingBlocks.count(inventory);
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
			if (node.finished) {
				if (airForWork(node)) {
					result = reconstruct(node, true);
					progress = (heuristic(origin) - heuristic(node.pos)) / walkCost;
					return true;
				}
				continue;
			}
			if (reachOnly && cell(node.pos)[bucket(node.air)] != node) {
				continue; // a route with more air came along while this one waited
			}
			if (goal.test(level, node.pos, node.floor) && airForWork(node)) {
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
			List<Candidate> steps = neighbours(node);
			if (!reachOnly) {
				List<BlockPos> clear = goal.toClear(level, node.pos, node.floor);
				if (clear != null && !clear.isEmpty()) {
					workHere(node, clear);
				}
			}
			Candidate breath = breathe(node);
			if (breath != null) {
				steps.add(breath);
			}
			for (Candidate c : steps) {
				Wet wet = wetAfter(node, c.step());
				int airLeft = airAfter(node, c, wet);
				if (airLeft < 0) {
					continue; // couldn't get back up from there in time
				}
				int blocks = node.blocks - (c.step().place() != null ? 1 : 0);
				if (blocks < 0) {
					blocksShort++; // none left to lay
					continue;
				}
				long key = c.step().to().asLong();
				double g = node.g + c.cost() * (favored.test(key) ? FAVOR_PREVIOUS : 1);
				int b = bucket(airLeft);
				Node[] nodes = cell(c.step().to());
				if (reachOnly ? !lessAir(nodes, b, airLeft) : !dominated(nodes, b, g)) {
					double toGoal = heuristic(c.step().to()) * heuristicWeight;
					Node next = nodes[b];
					if (next != null && next.heapIndex >= 0 && !reachOnly) {
						next.set(c.floor(), g, toGoal, node, c.step(), airLeft); // still waiting: just cheaper now
						next.ticks = c.ticks();
						next.blocks = blocks;
						next.wet = wet;
						open.decreased(next);
					} else {
						next = new Node(c.step().to(), c.floor(), g, toGoal, node, c.step(), airLeft);
						next.ticks = c.ticks();
						next.blocks = blocks;
						next.wet = wet;
						if (reachOnly) {
							next.f += (air.full() - airLeft) * MOST_AIR_FIRST;
						}
						nodes[b] = next;
						open.add(next);
					}
				}
			}
		}
		Node end = partialEnd();
		result = end == startNode ? Result.EMPTY : beforeBuilding(reconstruct(end, false), end.blocks);
		// What walking the steps it hands back gains: they may stop short of the node they were made
		// for (its last tenth, or the bridge it hasn't the blocks to finish). Measured at that node, a
		// few steps along the edge of a lava lake it couldn't bridge counted as getting well closer, and
		// were walked again and again for two minutes.
		List<PathStep> walked = result.steps();
		BlockPos reached = walked.isEmpty() ? origin : walked.get(walked.size() - 1).to();
		progress = (heuristic(origin) - heuristic(reached)) / walkCost;
		return true;
	}

	/** Where a path that doesn't reach the goal should end (see {@link #PARTIAL_WEIGHTS}). */
	private Node partialEnd() {
		if (whole) {
			return closest;
		}
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

	/**
	 * The answer is no: the target was in the window, and it went through every cell it could get
	 * to, breaths included, without coming to the goal. A partial path out of such a search leads
	 * nowhere; one that stopped at {@link #hitNodeLimit} or at the window's edge doesn't know yet.
	 */
	public boolean provenUnreachable() {
		return result != null && !result.reachesGoal() && !partial && open.isEmpty();
	}

	/** Routes it turned down for want of blocks to lay. */
	public int blocksShort() {
		return blocksShort;
	}

	/** Cells it has been through. */
	public int nodes() {
		return expanded;
	}

	/** It stopped for its node limit, with cells still to look at. */
	public boolean hitNodeLimit() {
		return expanded >= limits.maxNodes();
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

	/** Steps it turned down for want of air (see {@link #airAfter}): 0 means air wasn't why it found nothing. */
	public int airRejected() {
		return airRejected;
	}

	private int airRejected;

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
		List<Candidate> first = neighbours(startNode);
		int noAir = 0;
		for (Candidate c : first) {
			if (airAfter(startNode, c, wetAfter(startNode, c.step())) < 0) {
				noAir++;
			}
		}
		sb.append("；起点的下一步 ").append(first.size()).append(" 个，因为气不够排除 ").append(noAir).append(" 个");
		sb.append(String.format(java.util.Locale.ROOT, "（气 %d/%d%s）", air.now(), air.full(), air.limited() ? "" : "，不限"));
		sb.append("；方块 ").append(startNode.blocks).append(" 个，因为不够排除过 ").append(blocksShort).append(" 次");
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
	/** The hole a route has opened once it's taken this step: dropped through a floor into the water two down (see DigDownMove). */
	private static BlockPos holeAfter(Node from, PathStep step) {
		return step.type() == PathStep.Type.DESCEND && !step.toBreak().isEmpty() && step.from().getY() - step.to().getY() == 2
				? step.toBreak().get(0) : from.hole;
	}

	/**
	 * Cells a route dug that the water has come into by the time it's further on: the world it plans
	 * on still has the block (ice over a shallow sea bed, the bank of a lake), and taken for the dry
	 * hole it would be anywhere else, the plan dug on down from there with its head "out of the water",
	 * into what the ice had turned into, where its next dig had the head under and the hand refused it,
	 * and it tried the next patch of ice, over and over (player's report; with the steps' own way of
	 * taking water for a block still there, see MovementController#executeStep). The air rules already knew (see {@link #airAfter}); the cells around
	 * a node, what it may dig from there and how fast, didn't. Only the cells near the route's end are
	 * kept: further back they don't touch what it does next.
	 */
	record Wet(BlockPos pos, Wet next) {
		static boolean has(Wet w, BlockPos pos) {
			for (; w != null; w = w.next) {
				if (w.pos.equals(pos)) {
					return true;
				}
			}
			return false;
		}
	}

	/** Further than this from where a route is, what it flooded is forgotten (see {@link Wet}). */
	private static final int WET_KEPT = 4;

	/** {@code from}'s flooded cells near where {@code step} ends, and those of its own that flood. */
	private Wet wetAfter(Node from, PathStep step) {
		if (step.toBreak().isEmpty() && from.wet == null) {
			return null;
		}
		BlockPos to = step.to();
		Wet kept = null;
		boolean all = true;
		for (Wet w = from.wet; w != null; w = w.next) {
			if (Math.max(Math.abs(w.pos.getX() - to.getX()), Math.max(Math.abs(w.pos.getY() - to.getY()),
					Math.abs(w.pos.getZ() - to.getZ()))) <= WET_KEPT) {
				kept = new Wet(w.pos, kept);
			} else {
				all = false;
			}
		}
		if (all) {
			kept = from.wet; // nothing dropped: share it as it is
		}
		for (BlockPos b : step.toBreak()) {
			if (floodsWhenDug(b, kept)) {
				kept = new Wet(b, kept);
			}
		}
		return kept;
	}

	/** {@link Escape#floodsWhenDug}, with what the route flooded itself as the water it is by then. */
	private boolean floodsWhenDug(BlockPos b, Wet wet) {
		if (Escape.floodsWhenDug(level, b) || Wet.has(wet, b.above())) {
			return true;
		}
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			if (Wet.has(wet, b.relative(dir))) {
				return true;
			}
		}
		return false;
	}

	/** Water there, in the world or because the route let it in (see {@link Wet}). */
	private boolean wet(BlockPos pos, Wet wet) {
		return isWater(level, pos) || Wet.has(wet, pos);
	}

	/** {@link Air#breathable}, with what the route flooded itself. */
	private boolean breathable(BlockPos feet, Wet wet) {
		return Air.breathable(level, feet) && !Wet.has(wet, feet.above());
	}

	/**
	 * Ticks to air from {@code feet}, as things stand, or by the hole the route itself opened: the
	 * world it plans on still has that floor, and {@link Escape} would have it dug from below.
	 */
	private double wayUp(BlockPos feet, BlockPos hole) {
		double way = escape.ticks(feet);
		if (hole == null) {
			return way;
		}
		int across = Math.abs(feet.getX() - hole.getX()) + Math.abs(feet.getZ() - hole.getZ());
		return Math.min(way, Air.swimTicks(across, hole.getY() - feet.getY(), 0));
	}

	/** @param wet the flooded cells once the step is taken (see {@link #wetAfter}) */
	private int airAfter(Node from, Candidate c, Wet wet) {
		BlockPos to = c.step().to();
		boolean floods = false;
		for (BlockPos b : c.step().toBreak()) {
			if (Wet.has(wet, b)) {
				floods = true;
				break;
			}
		}
		if (!air.limited()) {
			return 0;
		}
		if (!floods && breathable(to, wet)) {
			// Head out of the water: the breath comes back as it goes, at vanilla's pace, not all at once.
			return (int) Math.min(air.full(), Math.max(0, from.air) + air.refill() * c.ticks());
		}
		// Charged with the real time the step takes, not its cost (which includes preferences).
		int left = from.air - (int) Math.ceil(c.ticks());
		// Kept all through the step, not just at its end: mining first, it stays where it set off
		// meanwhile, maybe further from air than where it ends up (a long dig down there ran out of
		// breath half way through, by the start's reckoning, on a step that ended fine).
		BlockPos hole = holeAfter(from, c.step());
		double way = c.step().toBreak().isEmpty() ? wayUp(to, hole) : Math.max(wayUp(to, hole), wayUp(from.pos, from.hole));
		if (!c.step().toBreak().isEmpty() && breathable(from.pos, from.wet)) {
			// Digging from where it can breathe (out of a dry mine, into the sea above it): if the water
			// comes, that's the way back, which the world it plans on doesn't show as open yet.
			way = Math.min(way, c.ticks());
		}
		if (!Air.enough(left, way, Air.PLAN_SLACK)) {
			airRejected++;
			return -1;
		}
		return left;
	}

	/**
	 * Stay here, head out of the water, until the breath is back: the plan's own way of taking a
	 * breath, at the time it takes (vanilla gives 4 air a tick), wherever on the route it's best. It
	 * used to be the breathing layer's alone, deciding as it went, and where its sums and the plan's
	 * differed the bot turned back half way along a planned route.
	 */
	private Candidate breathe(Node node) {
		// (A block for a head isn't air: it landed under ice just dug through, which the world here still has.)
		if (!air.limited() || node.air >= air.full() || !breathable(node.pos, node.wet) || !passable(node.pos.above())) {
			return null;
		}
		double ticks = Math.ceil((air.full() - node.air) / air.refill());
		return new Candidate(new PathStep(PathStep.Type.WAIT, node.pos, node.pos, List.of()), node.floor, ticks);
	}

	/**
	 * What it does on arrival takes breath too (see {@link Air.Profile#workTicks}): a goal that
	 * doesn't leave that isn't one, and the search goes on. This is what says if prey is too deep:
	 * how deep it lies says nothing of how long the way is from where the bot is.
	 */
	private boolean airForWork(Node node) {
		return !air.limited() || air.workTicks() <= 0 || breathable(node.pos, node.wet)
				|| Air.enough(node.air, wayUp(node.pos, node.hole), air.workTicks() + Air.PLAN_SLACK);
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

	/** For {@link #reachOnly}: some route already reached this cell with at least this much air, whatever it cost. */
	private static boolean lessAir(Node[] nodes, int b, int air) {
		if (nodes[b] != null && nodes[b].air >= air) {
			return true;
		}
		for (int i = b + 1; i < nodes.length; i++) {
			if (nodes[i] != null) {
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

	/** Ticks to fall {@code blocks} under vanilla gravity (0.08 blocks/tick²). */
	public static double fallTicks(double blocks) {
		return Math.sqrt(2 * blocks / 0.08);
	}

	private double heuristic(BlockPos pos) {
		return ahead != null ? ahead.applyAsDouble(pos) : partial ? travel.ticks(pos) : travel.straight(pos);
	}

	/**
	 * Out of blocks on the way, a path that doesn't get there stops short of the first one laid: out
	 * on a bridge it can't finish, it would stand over the drop with nothing left to go on with. Only
	 * this path's own blocks count: that other routes of the search ran short says nothing of this
	 * one (it was cut to nothing in a house for a step up it had the blocks for, and stood there).
	 */
	private Result beforeBuilding(Result partial, int blocksLeft) {
		if (blocksLeft > 0) {
			return partial;
		}
		for (int i = 0; i < partial.steps().size(); i++) {
			if (partial.steps().get(i).place() != null) {
				return i == 0 ? Result.EMPTY : new Result(new ArrayList<>(partial.steps().subList(0, i)), false,
						new ArrayList<>(partial.ticks().subList(0, i)), new ArrayList<>(partial.air().subList(0, i)));
			}
		}
		return partial;
	}

	private static Result reconstruct(Node node, boolean reachesGoal) {
		List<PathStep> steps = new ArrayList<>();
		List<Double> ticks = new ArrayList<>();
		List<Integer> air = new ArrayList<>();
		for (Node n = node; n.step != null; n = n.parent) {
			steps.add(n.step);
			ticks.add(n.ticks);
			air.add(n.air);
		}
		Collections.reverse(steps);
		Collections.reverse(ticks);
		Collections.reverse(air);
		if (!reachesGoal && steps.size() >= CUTOFF_MIN_STEPS) {
			int keep = (int) (steps.size() * CUTOFF_KEEP);
			steps = new ArrayList<>(steps.subList(0, keep));
			ticks = new ArrayList<>(ticks.subList(0, keep));
			air = new ArrayList<>(air.subList(0, keep));
		}
		return new Result(steps, reachesGoal, ticks, air);
	}

	// ---- neighbours ---------------------------------------------------------------------

	/** The head is out of the water where the cell being gone through would dig from (see {@link #clearCost}). */
	private boolean digsDry;

	/** Every step out of {@code node}: each move the bot knows adds its own (see {@link Moves}). */
	private List<Candidate> neighbours(Node node) {
		List<Candidate> out = new ArrayList<>(16);
		Move.From from = stance(node);
		for (Move move : Moves.ALL) {
			move.addSteps(this, node.pos, node.floor, from, out);
		}
		return out;
	}

	/**
	 * What it can do from {@code node}, standing or afloat there: dig (and how fast), build. Sets
	 * {@link #digsDry} for it.
	 */
	private Move.From stance(Node node) {
		BlockPos p = node.pos;
		double cur = node.floor;
		// Dug by the route and flooded since: water, whatever the world it plans on still has there (see Wet).
		boolean flooded = Wet.has(node.wet, p);
		boolean feetWet = flooded || isWater(level, p);
		boolean headWet = wet(p.above(), node.wet);
		// In a protected chunk it's a ghost: it can pass through, but not dig or build from there.
		// Digging and building assume we stand on a full block (or hold still in water).
		// (On top of a fence it's standing too, half a block up: from there digging may be the only way off.)
		// On the ground of this cell: its bottom, or the top of a low block in it (a path, mud, soul
		// sand: the ground itself, see floorHeight). Only the bottom counted, so on a path at the
		// bottom of a pit it wasn't standing, couldn't dig or build, and had no first step at all.
		BlockState in = level.getBlockState(p);
		boolean empty = flooded || BlockTable.empty(in, level, p);
		double ground = empty ? p.getY() : p.getY() + BlockTable.top(in, level, p);
		boolean standing = Math.abs(cur - ground) < ON_GROUND || Math.abs(cur - p.getY()) < ON_GROUND
				|| (!feetWet && BlockTable.top(level.getBlockState(p.below()), level, p.below()) > 1 + 1e-6);
		// Where it really is, a height alone isn't a floor: sinking through water it passed whole
		// heights, within ON_GROUND of each it was "standing", and bridged out of the water over a
		// drop, against nothing, over and over till it drowned. So there it needs one under it.
		// The same in the water, where nothing the plan puts down is ever under it. (Elsewhere further
		// on, the heights are the plan's own: on what the plan stands, a block it puts down a step
		// earlier among them, which the world doesn't have yet.)
		boolean atStart = p.equals(origin) && cur == startNode.floor;
		if ((atStart || feetWet) && standing && empty) {
			BlockState under = level.getBlockState(p.below());
			double top = BlockTable.empty(under, level, p.below()) ? 0 : BlockTable.top(under, level, p.below());
			standing = top >= 1 - 1e-3 || (!feetWet && top > 1 + 1e-6);
		}
		digsDry = !headWet;
		// Still in the water where it is, it may dig sideways as it always could from there (what
		// it may mine is the hand's gate's to say: see Guard); only building and digging down need the floor.
		// Mining in the water is five times slower unless it stands on something (vanilla).
		return new Move.From(feetWet && !standing ? 5 : 1, !ProtectionZones.isProtected(level, p), standing, feetWet);
	}

	/**
	 * The goal's work in place from {@code node} (see PathGoal#toClear), as the route's last step,
	 * held to every rule a dig on the way is: allowed, not by lava, with the head out of the water
	 * when each block is mined (what's dug before it may have let the water in: see Wet), and in one
	 * breath. Not its own floor: digging that out is a way down (DigDownMove), and it would no longer
	 * stand where it reckoned to mine from. If it holds, the node with that step goes in the open set
	 * like any other, at what it costs, and reaching it ends the search.
	 *
	 * <p>It was the gathering task's, after the route: each block in the way a job of its own, judged
	 * on the world as it was then. Standing in shallow water over a buried block, it dug out its own
	 * floor as "in the way", the shaft below filled with water, and the block was out of reach (IC2).
	 */
	private void workHere(Node node, List<BlockPos> clear) {
		Move.From from = stance(node);
		if (!from.canAct() || !(from.standing() || from.afloat())) {
			return;
		}
		BlockPos p = node.pos;
		BlockPos head = p.above();
		Wet wet = node.wet;
		List<BlockPos> breaks = new ArrayList<>(clear.size() + 2);
		resetDig();
		double cost = 0;
		for (BlockPos b : clear) {
			if (b.equals(p.below()) || b.equals(p) || b.equals(head) || nearLava(b)) {
				return;
			}
			digsDry = !wet(head, wet);
			if (passable(b)) {
				// A flower, tall grass: nothing to walking, but in the way of the eye. Gone at a touch.
				BlockState state = level.getBlockState(b);
				if (!Guard.canBreak(level, b, state) || !digsDry && !Guard.ofTheSea(state)) {
					return;
				}
				breaks.add(b);
				continue;
			}
			int before = breaks.size();
			double c = clearCost(b, breaks, from.digPenalty());
			if (c < 0) {
				return;
			}
			cost += c;
			for (int i = before; i < breaks.size(); i++) {
				BlockPos x = breaks.get(i);
				if (floodsWhenDug(x, wet)) {
					wet = new Wet(x, wet);
					// Water spreads into the open cell of the head beside it, and falls into it from above.
					if (x.equals(head.above()) || x.getY() == head.getY() && x.distManhattan(head) == 1) {
						wet = new Wet(head, wet);
					}
				}
			}
		}
		if (breaks.isEmpty()) {
			return;
		}
		PathStep step = new PathStep(PathStep.Type.MINE, p, p, List.copyOf(breaks));
		Candidate c = new Candidate(step, node.floor, cost, dugTicks());
		int airLeft = airAfter(node, c, wet);
		if (airLeft < 0) {
			return;
		}
		Node done = new Node(p, node.floor, node.g + cost, 0, node, step, airLeft);
		done.ticks = c.ticks();
		done.blocks = node.blocks;
		done.wet = wet;
		done.finished = true;
		open.add(done);
	}

	// ---- what moves ask of the search (see Planner) --------------------------------------

	@Override
	public BlockGetter world() {
		return level;
	}

	@Override
	public BlockPos origin() {
		return origin;
	}

	@Override
	public boolean swimmable(BlockPos pos) {
		return passable(pos) || isOpenWater(level, pos);
	}

	@Override
	public boolean water(BlockPos pos) {
		return isWater(level, pos);
	}

	@Override
	public boolean support(BlockPos pos) {
		return isSupport(level, pos);
	}

	@Override
	public boolean canPlace(BlockPos pos) {
		return Placement.allowed(level, pos, Placement.Kind.BUILD);
	}

	@Override
	public void resetDig() {
		digTicks = 0;
	}

	@Override
	public double dugTicks() {
		return digTicks;
	}

	@Override
	public double walkCost() {
		return walkCost;
	}

	@Override
	public int maxDrop() {
		return maxDrop;
	}

	@Override
	public boolean canBuild() {
		return canBuild;
	}

	@Override
	public boolean sprintSwim() {
		return air.sprintBlocks() > 0;
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

	@Override
	public boolean inWindow(BlockPos pos) {
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
			// Head out of the water: on the bottom of water a block deep, or swimming at the surface.
			base = surfaceFeet(level, c);
		} else if (empty) {
			double under = BlockTable.top(level.getBlockState(c.below()), level, c.below());
			if (under > 1 + 1e-6 && BlockTable.clean(state)) {
				// On top of a fence or a wall, half a block up in this cell: where it is if it dropped
				// onto one, the props of a mineshaft say. Not standable, it was stuck there for good,
				// with no first step to take. (Only got to from above: it's higher than a jump.)
				base = c.getY() + under - 1;
			} else if (!isPassable(level, c) || !isSupport(level, c.below())) {
				return Double.NaN;
			} else {
				base = c.getY();
			}
		} else {
			// A low block in the feet cell (slab, path, snow layer, carpet) is the ground itself.
			double top = BlockTable.top(state, level, c);
			if (top > 0.9375 + 1e-4 || !ground(state)) {
				return Double.NaN;
			}
			base = c.getY() + top;
		}
		// Headroom: air, or water it can be in (feet in an air pocket under water, say: a block just
		// dug out before the water flows in, where its drop lies). Breath is the air rules' business
		// (see Air), not whether it fits.
		int headTop = (int) Math.floor(base + PLAYER_HEIGHT - 1e-4);
		for (int y = c.getY() + 1; y <= headTop; y++) {
			BlockPos h = new BlockPos(c.getX(), y, c.getZ());
			if (!isPassable(level, h) && !isOpenWater(level, h)) {
				return Double.NaN;
			}
		}
		return base;
	}

	/** Feet height when swimming in water cell {@code c}: at the surface, or fully under. */
	public static double swimHeight(BlockGetter level, BlockPos c) {
		return isWater(level, c.above()) ? c.getY() : surfaceFeet(level, c);
	}

	/**
	 * Feet height in water cell {@code c} with the head out of it: on what's under it if that holds
	 * the feet in this cell (a full block, or a fence or wall half a block up into it), as a player
	 * stands in water a block deep, else afloat. It used to be afloat whatever was under: a little
	 * over the bottom of a shallow sea, never on the ground, it dug five times slower than planned and
	 * couldn't dig down or build from there. Under something lower (a slab, a chest) the feet are down in that cell, not this one.
	 */
	static double surfaceFeet(BlockGetter level, BlockPos c) {
		BlockState in = level.getBlockState(c);
		if (!BlockTable.empty(in, level, c)) {
			// A block in the water here (waterlogged): on it, if it's low enough to be the ground.
			double top = BlockTable.top(in, level, c);
			return top <= 0.9375 + 1e-4 && ground(in) ? c.getY() + top : c.getY() + SWIM_FLOOR;
		}
		double ground = groundUnder(level, c);
		return Double.isNaN(ground) ? c.getY() + SWIM_FLOOR : ground;
	}

	/** Where the feet rest in {@code c} on the block under it, or NaN if that doesn't reach up into it. */
	public static double groundUnder(BlockGetter level, BlockPos c) {
		BlockPos b = c.below();
		BlockState under = level.getBlockState(b);
		if (!Worlds.loaded(level, b) || BlockTable.empty(under, level, b) || !ground(under)) {
			return Double.NaN;
		}
		double top = BlockTable.top(under, level, b);
		return top > 1 + 1e-6 ? c.getY() + top - 1 : top >= 1 - 1e-3 ? c.getY() : Double.NaN;
	}

	/**
	 * May be stood on: nothing that hurts or that it must not step on. Water in it is no reason: a
	 * chest in a wreck, a slab or stairs in the sea are waterlogged, and were taken for nothing to
	 * stand on while the body stood on them all the same. (Lava never fills a block.)
	 */
	static boolean ground(BlockState state) {
		if (BlockTable.clean(state)) {
			return true;
		}
		return state.getFluidState().is(FluidTags.WATER) && !BlockTable.dangerous(state) && !Guard.isNoStep(state);
	}

	/** Water the bot can swim through: no collision, no bubble column or other hazard. */
	public static boolean isOpenWater(BlockGetter level, BlockPos pos) {
		if (!Worlds.loaded(level, pos) || !isWater(level, pos)) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		return BlockTable.empty(state, level, pos) && !BlockTable.dangerous(state);
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

	@Override
	public double floor(BlockPos c) {
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

	@Override
	public boolean passable(BlockPos pos) {
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
	@Override
	public double clearCost(BlockPos pos, List<BlockPos> breaks, double penalty) {
		if (passable(pos)) {
			return 0;
		}
		// The gate (see Guard#ofTheSea): a route never digs with its head under water, whatever for.
		if (!digsDry && !Guard.ofTheSea(level.getBlockState(pos))) {
			return -1e9;
		}
		if (reachOnly && !level.getBlockState(pos).is(net.minecraft.tags.BlockTags.ICE)) {
			return -1e9;
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

	@Override
	public boolean nearLava(BlockPos feet) {
		Cells.Section s = cells.at(feet);
		int i = Cells.index(feet);
		if (s.lava == null) {
			s.lava = new byte[Cells.SIZE];
		}
		int cached = s.lava[i];
		if (cached == 0) {
			cached = 2;
			int m = mode.lavaMargin;
			// Lava a level down, beside the block underfoot, can't come up, and nothing knocks the
			// bot about: the shore of a lava pool is somewhere to walk. Beside it at its own height
			// or above, it flows in (and a cell dug out is asked about too: digging down on the shore
			// is still out). The careful mode keeps away from it all.
			int lowest = mode == Mode.SAFE ? -1 : 0;
			search:
			for (int dx = -m; dx <= m; dx++) {
				for (int dz = -m; dz <= m; dz++) {
					for (int dy = lowest; dy <= 2; dy++) {
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
