package com.vale.helperbot.bot;

import com.vale.helperbot.guard.Guard;
import com.vale.helperbot.guard.Rules;
import com.vale.helperbot.path.Air;
import com.vale.helperbot.path.BreakTimes;
import com.vale.helperbot.path.Escape;
import com.vale.helperbot.path.BuildingBlocks;
import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.PathGoal;
import com.vale.helperbot.path.Snapshot;
import com.vale.helperbot.path.PathJob;
import com.vale.helperbot.path.Mode;
import com.vale.helperbot.path.PathStep;
import com.vale.helperbot.path.TravelEstimate;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Follows paths from {@link LocalPathfinder} by setting the bot's exact position every tick.
 * Speeds, step-up, jump arcs and gravity are capped at vanilla values; mining and placing go
 * through the normal survival flows, so the bot can never move, dig or build faster than a
 * real player.
 */
public final class MovementController {
	public enum Status {
		IDLE, MOVING, ARRIVED, FAILED
	}

	private static final double GRAVITY = 0.08;
	private static final double DRAG = 0.98;
	private static final double JUMP_VELOCITY = 0.42;
	private static final double SWIM_UP_SPEED = 0.06;
	private static final double SWIM_VERTICAL_SPEED = 0.08;
	/** How fast it sinks when idle under water. */
	private static final double SINK_SPEED = 0.02;
	/** Place the pillar block only once the feet are this far above the cell (see {@link #pillar}). */
	private static final double PILLAR_CLEARANCE = 0.05;
	/** Jumps allowed for one pillar step before giving up on it (e.g. something blocks the jump). */
	private static final int MAX_PILLAR_JUMPS = 3;
	private int pillarJumps;
	/** This step has (if it needed to) stepped back to the middle of its starting cell. */
	private boolean centered;
	/** Degrees below the horizon the bot looks while walking. */
	private static final float WALK_PITCH = 20f;
	private static final double EPS = 1e-4;
	/** Start planning the next stretch when this few steps of the current path are left. */
	private static final int LOOKAHEAD_STEPS = 5;
	private static final int MAX_EMPTY_PLANS = 2;
	/**
	 * How long the server thread waits for a search it just started, so a short one (most of
	 * them) is taken in the same tick; a longer one is picked up in a later tick.
	 */
	private static final long PLAN_WAIT_NANOS = 1_000_000;
	/** A partial path must get at least this many blocks closer, or a bigger window is tried. */
	private static final double MIN_USEFUL_PROGRESS = 3.0;
	/** Search bigger (then give up) when the distance to the target hasn't improved for this long. */
	private static final int NO_PROGRESS_TICKS = 400;
	/** Treat the bot as stuck when it hasn't moved at all for this long. */
	private static final int FROZEN_TICKS = 40;
	private static final int MAX_RECOVERIES = 4;
	/** Gather blocks when fewer than this are left and no path exists. */
	private static final int LOW_BLOCKS = 8;
	private static final int GATHER_TARGET = 32;

	private final BotPlayer bot;
	private Status status = Status.IDLE;
	private String failReason = "";
	private PathGoal goal;
	private BlockPos target;
	private Mode mode = Mode.DEFAULT;
	private boolean horizontalOnly;

	/** The search running in the background, if any (see PathJob). */
	private PathJob search;
	private int searchLevel;
	/** Smallest search level to start from; raised when the bot stops making progress. */
	private int minLevel;
	private double escalatedAtDistance;
	/** The next stretch, planned while walking the current one. */
	private PathJob lookahead;
	private boolean pathReachesGoal;
	private boolean replanRequested;

	private List<PathStep> path;
	private int index;
	private int emptyPlans;
	private double bestDistance;
	private int ticksWithoutProgress;

	private int breakIndex;
	private BlockPos breakingPos;
	private float breakProgress;
	private int breakAttempts;
	private boolean stepStarted;
	private boolean placed;
	private double stepFloor;
	private double verticalVelocity;

	private Vec3 lastPosition = Vec3.ZERO;
	private int frozenTicks;
	private int recoveries;
	private double airborneFromY = Double.NaN;


	private boolean gathering;
	private int gatherWait;
	private final Set<BlockPos> gatherRejected = new HashSet<>();

	MovementController(BotPlayer bot) {
		this.bot = bot;
	}

	public void goTo(PathGoal goal, BlockPos target, Mode mode) {
		goTo(goal, target, mode, false);
	}

	/**
	 * @param horizontalOnly the target's Y is unknown (a far-away biome): steer and measure
	 *                       progress by horizontal distance, so the bot doesn't climb or dig
	 *                       towards a made-up height
	 */
	public void goTo(PathGoal goal, BlockPos target, Mode mode, boolean horizontalOnly) {
		stop();
		this.goal = goal;
		this.target = target;
		this.mode = mode;
		this.horizontalOnly = horizontalOnly;
		this.minLevel = 0;
		this.status = Status.MOVING;
		this.bestDistance = Double.MAX_VALUE;
		this.ticksWithoutProgress = 0;
		this.emptyPlans = 0;
		this.recoveries = 0;
		this.frozenTicks = 0;
	}

	/** Changes the destination; the current step finishes, then a new plan is made. */
	public void retarget(PathGoal goal, BlockPos target) {
		this.goal = goal;
		this.target = target;
		this.replanRequested = true;
		cancelSearches();
		this.bestDistance = Double.MAX_VALUE;
	}

	public void stop() {
		abortBreaking();
		paused = false;
		engaged = false;
		ignoreAir = false;
		airRequested = false;
		lastPath.clear(); // a new destination: the old route means nothing
		status = Status.IDLE;
		path = null;
		cancelSearches();
		replanRequested = false;
		goal = null;
		target = null;
		gathering = false;
		resetStep();
	}

	public boolean isMining() {
		return breakingPos != null;
	}

	public Status status() {
		return status;
	}

	public String failReason() {
		return failReason;
	}

	public String describe() {
		return switch (status) {
			case IDLE -> "待命";
			case ARRIVED -> "已到达";
			case FAILED -> "失败：" + failReason;
			case MOVING -> search != null ? "思考中（搜索范围 ±" + search.limits().radiusXZ() + " 格）"
					: gathering ? "方块不够，正在就地收集（现有 " + BuildingBlocks.count(bot.getInventory()) + "）"
					: breakingPos != null ? "正在挖 " + breakingPos.toShortString()
					: path == null ? "规划路线中" : "移动中（第 " + (index + 1) + "/" + path.size() + " 步）";
		};
	}

	/** How far off its cell's centre {@link #nudgeToward} may move the bot. */
	private static final double MAX_NUDGE = 0.45;

	/**
	 * Shuffle a little towards a point without leaving the current cell, like a player leaning
	 * over to grab an item that's just out of reach. Only while not following a path; never past
	 * {@link #MAX_NUDGE} from the cell centre and never off solid ground. Returns false when it
	 * can't get any closer this way (the caller should walk there instead).
	 */
	public boolean nudgeToward(Vec3 point) {
		if (status == Status.MOVING) {
			return false;
		}
		double dx = point.x - bot.getX(), dz = point.z - bot.getZ();
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1e-3) {
			return false;
		}
		double step = Math.min(Mode.WALK_SPEED, len);
		double nx = bot.getX() + dx / len * step, nz = bot.getZ() + dz / len * step;
		BlockPos cell = feet();
		if (Math.abs(nx - (cell.getX() + 0.5)) > MAX_NUDGE || Math.abs(nz - (cell.getZ() + 0.5)) > MAX_NUDGE) {
			return false;
		}
		AABB moved = bot.getBoundingBox().move(nx - bot.getX(), 0, nz - bot.getZ());
		boolean grounded = !bot.level().noCollision(bot, moved.move(0, -0.03, 0));
		if (!bot.level().noCollision(bot, moved) || (!grounded && !isInWater())) {
			return false;
		}
		face(dx, dz);
		setPosition(nx, bot.getY(), nz);
		return true;
	}

	/** Standing (or floating) between steps, so it's safe to pause, e.g. to eat. */
	public boolean atRest() {
		return !stepStarted && breakingPos == null && isSupported();
	}

	public BlockPos feet() {
		return BlockPos.containing(bot.getX(), bot.getY() + 0.01, bot.getZ());
	}

	private boolean paused;

	/**
	 * Freeze whatever it's doing (e.g. to fight back): stop mining, keep the destination. Gravity
	 * still applies. {@link #resume()} picks the route up again from wherever it is then.
	 */
	public void pause() {
		if (!paused) {
			paused = true;
			abortBreaking();
			harvestTarget = null;
		}
	}

	public boolean isPaused() {
		return paused;
	}

	public void resume() {
		if (paused) {
			paused = false;
			if (status == Status.MOVING) {
				replan("战斗结束，继续原来的路线");
			}
		}
	}

	// ---- chasing in a fight, then back to the task -------------------------------------

	private boolean engaged;
	private PathGoal savedGoal;
	private BlockPos savedTarget;
	private Status savedStatus;
	private boolean savedHorizontalOnly;

	/**
	 * Go somewhere for a fight (e.g. to get at a skeleton), remembering the task's own
	 * destination, which {@link #disengage()} restores. Calling it again just re-aims.
	 */
	public void engage(PathGoal chaseGoal, BlockPos chaseTarget) {
		engage(chaseGoal, chaseTarget, "追击");
	}

	/** Same, with what it's going there for (for the log). */
	public void engage(PathGoal chaseGoal, BlockPos chaseTarget, String why) {
		paused = false;
		if (!engaged) {
			engaged = true;
			savedGoal = goal;
			savedTarget = target;
			savedStatus = status;
			savedHorizontalOnly = horizontalOnly;
			lastPath.clear();
			goal = chaseGoal;
			target = chaseTarget;
			horizontalOnly = false;
			status = Status.MOVING;
			bestDistance = Double.MAX_VALUE;
			ticksWithoutProgress = 0;
			replan(why);
		} else if (status != Status.MOVING) {
			goal = chaseGoal;
			target = chaseTarget;
			status = Status.MOVING;
			replan(why);
		} else {
			retarget(chaseGoal, chaseTarget);
		}
	}

	public boolean isEngaged() {
		return engaged;
	}

	// ---- breath -------------------------------------------------------------------------

	/** Planning ignores air (on the way up for air: any way up will do). */
	private boolean ignoreAir;
	/** The planner found nowhere to go on the air left: the breathing layer should take over. */
	private boolean airRequested;

	/** Ticks from here until the head is out of the water (see {@link Escape}). */
	public double escapeTicks() {
		return new Escape(bot.level(), bot.getInventory(), Air.waterDigFactor(bot)).ticks(feet());
	}

	public Escape newEscape() {
		return new Escape(bot.level(), bot.getInventory(), Air.waterDigFactor(bot));
	}

	public boolean takeAirRequest() {
		boolean r = airRequested;
		airRequested = false;
		return r;
	}

	/** Ask the breathing layer for a full breath now (e.g. not enough air left to finish a block). */
	public void requestAir() {
		airRequested = true;
	}

	/** Already hitting this block (some progress made), as opposed to about to start on it. */
	public boolean isBreaking(BlockPos pos) {
		return pos.equals(breakingPos);
	}

	/**
	 * Head for the nearest place to breathe, by the normal planner (so it can dig up, go around an
	 * overhang...), remembering what it was doing; {@link #disengage()} goes back to that.
	 */
	public void headForAir() {
		BlockPos f = feet();
		ServerLevel level = bot.level();
		ignoreAir = true; // read when it plans, next tick
		// A cell whose head space is still a block to dig only counts if it won't be water once dug
		// (the goal sees the world as it is now, not as the path will leave it).
		engage((w, p) -> Air.breathable(w, p)
				&& (LocalPathfinder.isPassable(w, p.above()) || !Escape.floodsWhenDug(w, p.above())),
				f.above(Math.max(2, waterAbove())), "去换气");
	}

	/** Walking somewhere below where it is now (e.g. digging down to a cave under the sea). */
	public boolean headingDown() {
		return status == Status.MOVING && target != null && target.getY() < feet().getY() - 1;
	}

	/**
	 * In a one-wide flooded shaft whose water only comes from above (it dug down through the sea
	 * floor)? Then a block over its head cuts the water off, the water below drains away, and it's
	 * standing in air: what a player does instead of swimming all the way back up.
	 */
	public boolean canSeal() {
		ServerLevel level = bot.level();
		BlockPos f = feet(), head = f.above(), top = head.above();
		if (!headUnderwater() || !LocalPathfinder.isWater(level, top) || !Guard.canPlace(level, top)
				|| BuildingBlocks.bestSlot(bot.getInventory()) < 0) {
			return false;
		}
		for (BlockPos c : new BlockPos[]{f, head}) {
			var fluid = level.getFluidState(c);
			if (!fluid.isEmpty() && fluid.isSource()) {
				return false; // still water here once cut off
			}
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos side = c.relative(d);
				if (!level.getFluidState(side).isEmpty() || LocalPathfinder.isPassable(level, side)) {
					return false; // water (or the way for it) comes in from the side
				}
			}
		}
		// The block needs something to go against: the shaft walls up there.
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (!level.getBlockState(top.relative(d)).getCollisionShape(level, top.relative(d)).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** Put the block over its head (see {@link #canSeal}). */
	public boolean seal() {
		BlockPos top = feet().above(2);
		boolean ok = placeItem(top, BuildingBlocks.bestSlot(bot.getInventory()));
		if (ok) {
			replan("堵住了头顶的井口");
		}
		return ok;
	}

	/** Back to what the task was doing before the chase (re-planning from here if it was walking). */
	public void disengage() {
		if (!engaged) {
			return;
		}
		engaged = false;
		ignoreAir = false;
		lastPath.clear();
		goal = savedGoal;
		target = savedTarget;
		status = savedStatus;
		horizontalOnly = savedHorizontalOnly;
		bestDistance = Double.MAX_VALUE;
		ticksWithoutProgress = 0;
		recoveries = 0;
		replan("战斗结束，回到原来的事");
	}

	void tick() {
		if (paused) {
			bot.setSprinting(false);
			fall();
			trackFallDamage();
			bot.setOnGround(isGrounded());
			return;
		}
		if (status == Status.MOVING) {
			tickMoving();
		} else {
			bot.setSprinting(false);
			fall();
		}
		trackFallDamage();
		// Vanilla mines 5x slower when not on the ground, so this flag must be right even while
		// standing still (it used to only update on movement and could stay stale).
		bot.setOnGround(isGrounded());
	}

	private void tickMoving() {
		// Gravity first, whatever it's busy with (mining, gathering, thinking): only the movement
		// part of a step moves it vertically itself. If the floor went (e.g. a player dug it out
		// mid-mining), drop what it was doing and re-plan from wherever it lands.
		if (!stepStarted && !isSupported()) {
			if (breakingPos != null || breakIndex > 0 || placed) {
				replan("脚下的方块没了，先掉下去");
			}
			fall();
			return;
		}
		if (gathering) {
			gatherTick();
			return;
		}
		if (search != null) {
			// Thinking: standing still while a (possibly large) search runs over several ticks.
			continuePlanning();
			return;
		}
		boolean atStepBoundary = !stepStarted && breakIndex == 0 && breakingPos == null;
		if (atStepBoundary) {
			if (goal.test(bot.level(), feet())) {
				status = Status.ARRIVED;
				path = null;
				return;
			}
			// Not where the next step starts (fell into a hole someone dug, got pushed...): the rest
			// of this path no longer applies.
			if (path != null && index < path.size() && !feet().equals(path.get(index).from())) {
				replan("不在这一步的起点（被推开或掉下去了）");
			}
			// Under water: re-plan every second from what's there now (quietly, it's routine).
			if (path != null && headUnderwater() && bot.tickCount - plannedAt > WATER_REPLAN_TICKS) {
				abortBreaking();
				path = null;
				cancelLookahead();
			}
			// About to go under from where it can breathe: top up first, like a player taking a
			// breath before diving. The plan counted on a full breath.
			if (path != null && index < path.size() && !ignoreAir && !headUnderwater()
					&& diveStep(path.get(index)) && bot.getAirSupply() < bot.getMaxAirSupply()) {
				if (!toppingUp) {
					toppingUp = true;
					bot.log("下水前先换满气");
				}
				bot.setSprinting(false);
				fall();
				lastPosition = bot.position();
				return;
			}
			toppingUp = false;
			if (path == null || index >= path.size() || replanRequested) {
				if (!switchToLookahead()) {
					replanRequested = false;
					startPlanning(minLevel);
					continuePlanning();
					return;
				}
			}
		}
		if (path != null && index < path.size()) {
			if (stepTimedOut(path.get(index)) || aheadChanged()) {
				return;
			}
			executeStep(path.get(index));
			advanceLookahead();
		}
		watchdog();
		checkProgress();
		if (bot.isDebugPath() && bot.tickCount % 10 == 0) {
			showPath();
		}
	}

	private void startPlanning(int levelIndex) {
		if (levelIndex == minLevel) {
			levels = LocalPathfinder.levels(headUnderwater());
		}
		searchLevel = levelIndex;
		if (search != null) {
			search.cancel();
		}
		search = startSearch(feet(), bot.getY(), levels[levelIndex]);
		long started = System.nanoTime();
		search.await(PLAN_WAIT_NANOS);
		bot.perf().add(Perf.Part.PATHFIND, System.nanoTime() - started);
		plannedAt = bot.tickCount;
	}

	/**
	 * Starts a search in the background, on a copy of the blocks around it and of everything else
	 * it reads (tools, cells to avoid, the last route), so it never touches what the server thread
	 * is changing meanwhile. The copying is timed as pathfinding on the server thread.
	 */
	private PathJob startSearch(BlockPos start, double startFloor, LocalPathfinder.Limits limits) {
		long started = System.nanoTime();
		Air.Profile air = airProfile();
		Snapshot world = Snapshot.around(bot.level(), start, limits, air.limited());
		Inventory tools = new Inventory(bot, new net.minecraft.world.entity.EntityEquipment());
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			tools.setItem(slot, bot.getInventory().getItem(slot).copy());
		}
		it.unimi.dsi.fastutil.longs.LongOpenHashSet avoided = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		for (var it = avoidUntil.long2IntEntrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			if (e.getIntValue() < bot.tickCount) {
				it.remove(); // its time is up
			} else {
				avoided.add(e.getLongKey());
			}
		}
		it.unimi.dsi.fastutil.longs.LongOpenHashSet favored = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(lastPath);
		PathJob job = new PathJob(new LocalPathfinder(world, tools, mode, mode.maxDropFor(bot.getHealth()), start, startFloor,
				goal, target, horizontalOnly, limits, avoided::contains, air, favored::contains));
		bot.perf().add(Perf.Part.PATHFIND, System.nanoTime() - started);
		return job;
	}

	/** Has the search finished? Counts its background time once, when it has. */
	private boolean finished(PathJob job) {
		if (!job.done()) {
			return false;
		}
		bot.perf().add(Perf.Part.BACKGROUND, job.takeNanos());
		return true;
	}

	private void cancelSearches() {
		if (search != null) {
			search.cancel();
			search = null;
		}
		cancelLookahead();
	}

	private void cancelLookahead() {
		if (lookahead != null) {
			lookahead.cancel();
			lookahead = null;
		}
	}

	private boolean toppingUp;

	/** Does this step end with the head under water (or dig into water)? */
	private boolean diveStep(PathStep step) {
		ServerLevel level = bot.level();
		if (!Air.breathable(level, step.to())) {
			return true;
		}
		for (BlockPos b : step.toBreak()) {
			if (Escape.floodsWhenDug(level, b)) {
				return true;
			}
		}
		return false;
	}

	/** Search windows in use: a small cube first under water (see LocalPathfinder#WATER_LEVELS). */
	private LocalPathfinder.Limits[] levels = LocalPathfinder.LEVELS;
	private int plannedAt;
	/** Under water, plan again this often: the world there changes fast (sand, flowing water, kelp). */
	private static final int WATER_REPLAN_TICKS = 20;

	private Air.Profile airProfile() {
		return Air.profile(bot, !ignoreAir);
	}

	public boolean headUnderwater() {
		return bot.isEyeInFluid(FluidTags.WATER);
	}

	/**
	 * Runs the current search for this tick's budget. If it finds nothing useful (no path, or a
	 * partial path that barely gets closer), the next bigger window is tried.
	 */
	private void continuePlanning() {
		bot.perf().testWait(search.paceForTests());
		if (!finished(search)) {
			return;
		}
		LocalPathfinder.Result result = search.result();
		boolean useful = result.reachesGoal()
				|| (!result.steps().isEmpty() && search.progressBlocks() >= MIN_USEFUL_PROGRESS);
		if (!useful && searchLevel < levels.length - 1) {
			startPlanning(searchLevel + 1);
			return;
		}
		int radius = search.limits().radiusXZ();
		search = null;
		resetStep();
		if (result.steps().isEmpty()) {
			bot.log("在 ±" + radius + " 格内找不到路");
			onNoPath();
			return;
		}
		bot.log("规划出 " + result.steps().size() + " 步（±" + radius + " 格，"
				+ (result.reachesGoal() ? "能到终点" : "先走一段") + "）");
		adopt(result);
		emptyPlans = 0;
	}

	/**
	 * Think while walking: when the current (partial) path is nearly done, start planning the next
	 * stretch from where it ends, a little each tick, so it's ready on arrival and the bot doesn't
	 * stop to think. Only once the remaining steps won't change the world (no digging or placing
	 * left), since the search sees the world as it is now.
	 */
	private void advanceLookahead() {
		if (lookahead != null) {
			bot.perf().testWait(lookahead.paceForTests());
			finished(lookahead); // only to count its time; it's taken at the end of the path
			return;
		}
		if (path == null || pathReachesGoal || replanRequested || path.size() - index > LOOKAHEAD_STEPS) {
			return;
		}
		for (int i = index; i < path.size(); i++) {
			PathStep step = path.get(i);
			if (!step.toBreak().isEmpty() || step.place() != null) {
				return;
			}
		}
		BlockPos end = path.get(path.size() - 1).to();
		double endFloor = LocalPathfinder.floorHeight(bot.level(), end);
		if (Double.isNaN(endFloor)) {
			return;
		}
		lookahead = startSearch(end, endFloor, levels[minLevel]);
	}

	/**
	 * At the end of the current path: continue straight onto the next stretch if the lookahead
	 * found a good one. Returns false if a normal (standing) re-plan is needed instead.
	 */
	private boolean switchToLookahead() {
		PathJob next = lookahead;
		lookahead = null;
		if (next == null || replanRequested || path == null || !feet().equals(path.get(path.size() - 1).to())) {
			if (next != null) {
				next.cancel();
			}
			return false;
		}
		long started = System.nanoTime();
		next.await(PLAN_WAIT_NANOS);
		bot.perf().add(Perf.Part.PATHFIND, System.nanoTime() - started);
		if (!finished(next)) {
			lookahead = next; // almost always done by now; if not, finish it next tick
			return true;
		}
		LocalPathfinder.Result result = next.result();
		boolean useful = result.reachesGoal()
				|| (!result.steps().isEmpty() && next.progressBlocks() >= MIN_USEFUL_PROGRESS);
		if (!useful) {
			return false; // let the normal planner try (and widen the search if needed)
		}
		adopt(result);
		emptyPlans = 0;
		resetStep();
		return true;
	}

	private void onNoPath() {
		path = null;
		if (!ignoreAir && headUnderwater() && Air.budget(bot) < Air.fullBudget(bot)) {
			// Nowhere to go on the air left: get a full breath and try again (a player diving to
			// dig deeper does the same: down, dig, up, down again).
			bot.log("这口气不够走下去，先去换气");
			airRequested = true;
			return;
		}
		if (Rules.open() && BuildingBlocks.count(bot.getInventory()) < LOW_BLOCKS && !isInWater()) {
			// Maybe a bridge or pillar would work: get some blocks first.
			gathering = true;
			gatherRejected.clear();
			return;
		}
		if (++emptyPlans >= MAX_EMPTY_PLANS) {
			int r = levels[levels.length - 1].radiusXZ();
			// Under water the usual reason is air: nowhere it could go and still get back up in time.
			fail(headUnderwater() && !ignoreAir
					? "在水下，一口气能去又能回来换气的地方里找不到路（离换气的出口太远，或者头顶要挖的太慢）"
					: "方圆 " + r + " 格内都找不到能走的路（被保护区、岩浆、太高的墙挡住，或者没有方块可以搭路）");
		}
	}

	private void checkProgress() {
		if (target == null || status != Status.MOVING || breakingPos != null) {
			return;
		}
		BlockPos here = bot.blockPosition();
		double distance = remaining(here);
		if (distance < bestDistance - 0.5) {
			bestDistance = distance;
			ticksWithoutProgress = 0;
			recoveries = 0;
			if (minLevel > 0 && distance < escalatedAtDistance - 16) {
				minLevel = 0; // well past the obstacle: back to cheap small searches
			}
		} else if (++ticksWithoutProgress > NO_PROGRESS_TICKS) {
			if (minLevel < levels.length - 1) {
				// Probably circling in a dead end the small window can't see out of: think bigger.
				minLevel++;
				escalatedAtDistance = distance;
				ticksWithoutProgress = 0;
				replan("太久没靠近目标，扩大搜索范围");
			} else {
				fail("用最大搜索范围也没能靠近目标，可能在原地绕圈");
			}
		}
	}

	private TravelEstimate travel;
	private BlockPos travelTarget;
	private boolean travelHorizontal;

	/**
	 * How far it still is, in blocks of running, by the same estimate the planner steers by (see
	 * {@link TravelEstimate}): climbing out of a mine on a long trip counts as getting closer. By
	 * straight-line distance it didn't, and a long climb looked like being stuck.
	 */
	private double remaining(BlockPos here) {
		if (travel == null || !target.equals(travelTarget) || horizontalOnly != travelHorizontal) {
			travel = new TravelEstimate(bot.level(), bot.getInventory(), mode, target, horizontalOnly);
			travelTarget = target;
			travelHorizontal = horizontalOnly;
		}
		return travel.ticks(here) * mode.speed();
	}

	/** If the bot hasn't moved at all (and isn't mining), drop to the ground and plan again. */
	private void watchdog() {
		Vec3 now = bot.position();
		if (breakingPos != null || now.distanceToSqr(lastPosition) > 1e-6) {
			lastPosition = now;
			frozenTicks = 0;
			return;
		}
		if (++frozenTicks < FROZEN_TICKS) {
			return;
		}
		frozenTicks = 0;
		if (++recoveries > MAX_RECOVERIES) {
			fail("在 " + feet().toShortString() + " 卡住，动不了");
			return;
		}
		replan("原地 2 秒没动，自我恢复");
		settleToGround();
	}

	private void fail(String reason) {
		bot.log("移动失败：" + reason);
		abortBreaking();
		failReason = reason;
		status = Status.FAILED;
		path = null;
		gathering = false;
		resetStep();
	}

	private void resetStep() {
		stepTicks = 0;
		breakIndex = 0;
		breakAttempts = 0;
		stepStarted = false;
		placed = false;
		centered = false;
		verticalVelocity = 0;
		pillarJumps = 0;
		blockedTicks = 0;
	}

	// ---- learning from collisions -------------------------------------------------------

	/** Ticks of being blocked (not moving at all) before the obstacle is learned and avoided. */
	private static final int BLOCKED_LEARN_TICKS = 6;
	/** How long a learned obstacle is avoided, in ticks (the world may change, e.g. it gets mined). */
	private static final int AVOID_TICKS = 600;
	private int blockedTicks;
	/** Cells that turned out to block movement although the plan thought them open: pos -> expiry tick. */
	private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap avoidUntil = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

	/**
	 * Whatever blocked {@code attempted} (a fence sticking up from below, an odd-shaped block, a
	 * minecart...): mark the cells it occupies inside the attempted box as walls for a while. This
	 * covers any mismatch between the planner's model and real collisions, without special cases.
	 */
	private void learnObstacle(AABB attempted) {
		int until = bot.tickCount + AVOID_TICKS;
		java.util.Set<BlockPos> cells = new java.util.HashSet<>();
		for (net.minecraft.world.phys.shapes.VoxelShape shape : bot.level().getCollisions(bot, attempted)) {
			for (AABB part : shape.toAabbs()) {
				if (!part.intersects(attempted)) {
					continue;
				}
				AABB overlap = part.intersect(attempted);
				for (BlockPos p : BlockPos.betweenClosed(
						(int) Math.floor(overlap.minX), (int) Math.floor(overlap.minY), (int) Math.floor(overlap.minZ),
						(int) Math.floor(overlap.maxX - 1e-7), (int) Math.floor(overlap.maxY - 1e-7), (int) Math.floor(overlap.maxZ - 1e-7))) {
					cells.add(p.immutable());
				}
			}
		}
		for (BlockPos p : cells) {
			avoidUntil.put(p.asLong(), until);
		}
		bot.log("被挡住，记下 " + cells.size() + " 格暂时绕开：" + cells.stream().map(BlockPos::toShortString).limit(4).toList());
	}

	private void nextStep() {
		index++;
		resetStep();
	}

	// ---- borrowed from Baritone: step timeout, checking ahead, sticking to the route ------

	/** Real ticks each step of the path should take. */
	private List<Double> pathTicks = List.of();
	/** Cells of the most recent path towards the current goal: favoured when planning again. */
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet lastPath = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	/** Ticks spent on the current step. */
	private int stepTicks;
	/** A step may take this much longer than its estimate before it's given up on. */
	private static final int STEP_TIMEOUT_EXTRA = 100;
	/** How many steps ahead are checked against the world as it is now. */
	private static final int VERIFY_AHEAD = 5;
	private static final int VERIFY_INTERVAL = 5;

	private void adopt(LocalPathfinder.Result result) {
		path = result.steps();
		pathTicks = result.ticks();
		pathReachesGoal = result.reachesGoal();
		index = 0;
		lastPath.clear();
		for (PathStep s : path) {
			lastPath.add(s.from().asLong());
			lastPath.add(s.to().asLong());
		}
	}

	/**
	 * Whatever the reason a step is stuck (and there are endless ones), it shows as taking far
	 * longer than planned: give up on it, keep away from where it was going for a while, plan
	 * again. A catch-all next to the specific checks (blocked, frozen, fell off the path).
	 */
	private boolean stepTimedOut(PathStep step) {
		double expected = index < pathTicks.size() ? pathTicks.get(index) : 20;
		if (++stepTicks <= expected + STEP_TIMEOUT_EXTRA) {
			return false;
		}
		avoidUntil.put(step.to().asLong(), bot.tickCount + AVOID_TICKS);
		replan("这一步比预计慢太多（预计 " + (int) expected + " 刻，已用 " + stepTicks + " 刻），换条路");
		return true;
	}

	/**
	 * Check the next few steps against the world as it is now: sand fell in, water flowed in,
	 * someone built or dug there. Plan again right away instead of walking up to it first. Only up
	 * to the first step that changes the world itself: the ones after it count on that change.
	 */
	private boolean aheadChanged() {
		if (path == null || bot.tickCount % VERIFY_INTERVAL != 0 || index >= path.size()) {
			return false;
		}
		ServerLevel level = bot.level();
		if (changesWorld(path.get(index))) {
			return false;
		}
		for (int i = index + 1; i < Math.min(path.size(), index + 1 + VERIFY_AHEAD); i++) {
			PathStep s = path.get(i);
			if (changesWorld(s)) {
				for (BlockPos b : s.toBreak()) {
					BlockState state = level.getBlockState(b);
					if (!LocalPathfinder.isPassable(level, b) && !LocalPathfinder.isOpenWater(level, b)
							&& !Guard.canBreak(level, b, state)) {
						replan("前方第 " + (i - index) + " 步要挖的方块现在不能挖了");
						return true;
					}
				}
				return false;
			}
			if (s.type() != PathStep.Type.PILLAR && Double.isNaN(LocalPathfinder.floorHeight(level, s.to()))
					&& !LocalPathfinder.isOpenWater(level, s.to())) {
				replan("前方第 " + (i - index) + " 步走不通了（那里变了）");
				return true;
			}
		}
		return false;
	}

	private static boolean changesWorld(PathStep s) {
		return !s.toBreak().isEmpty() || s.place() != null;
	}

	/** The step that last ended in a replan, how often in a row, and when. */
	private PathStep failedStep;
	private int failedStepCount;
	private int failedStepTick;
	private static final int SAME_STEP_FAILURES = 3;
	/** Failures of the same step further apart than this don't count as "in a row". */
	private static final int SAME_STEP_WINDOW = 600;

	private void replan(String why) {
		bot.log("重新规划：" + why);
		if (path != null && index < path.size()) {
			// Whatever the reason, the same step from the same place going wrong again and again
			// (e.g. in a waterfall: plan one step up, it fails, plan the same step...) means the plan's
			// picture of it is wrong: keep out of where it leads for a while, so the next plan
			// finds another way (or, going for air, the breathing layer swims/digs straight up).
			PathStep step = path.get(index);
			if (step.equals(failedStep) && bot.tickCount - failedStepTick <= SAME_STEP_WINDOW) {
				failedStepCount++;
			} else {
				failedStep = step;
				failedStepCount = 1;
			}
			failedStepTick = bot.tickCount;
			if (failedStepCount >= SAME_STEP_FAILURES) {
				avoidUntil.put(step.to().asLong(), bot.tickCount + AVOID_TICKS);
				bot.log("同一步（" + step.from().toShortString() + " → " + step.to().toShortString() + "）连续失败 "
						+ failedStepCount + " 次，暂时当它走不通");
				failedStep = null;
				failedStepCount = 0;
			}
		}
		abortBreaking();
		path = null;
		cancelSearches();
		replanRequested = false;
		resetStep();
	}

	// ---- gathering building blocks ------------------------------------------------------

	/** Mine solid blocks right around the body (so the drops land on us) until we have enough. */
	private void gatherTick() {
		if (breakingPos != null) {
			mine(breakingPos, bot.level().getBlockState(breakingPos));
			if (breakingPos == null) {
				gatherWait = 15; // item pickup delay
			}
			return;
		}
		if (gatherWait > 0) {
			gatherWait--;
			return;
		}
		if (BuildingBlocks.count(bot.getInventory()) >= GATHER_TARGET) {
			gathering = false;
			emptyPlans = 0;
			return;
		}
		BlockPos next = nextGatherBlock();
		if (next == null) {
			gathering = false;
			if (++emptyPlans >= MAX_EMPTY_PLANS) {
				fail("没有路可走，身边也挖不到可以用来搭路的方块");
			}
			return;
		}
		breakAttempts = 0;
		mine(next, bot.level().getBlockState(next));
	}

	private BlockPos nextGatherBlock() {
		ServerLevel level = bot.level();
		BlockPos f = feet();
		for (Direction d : Direction.Plane.HORIZONTAL) {
			for (BlockPos c : new BlockPos[]{f.relative(d).above(), f.relative(d), f.relative(d).below()}) {
				BlockState state = level.getBlockState(c);
				if (!gatherRejected.contains(c) && state.isCollisionShapeFullBlock(level, c)
						&& Guard.canBreak(level, c, state) && BreakTimes.estimateTicks(bot.getInventory(), level, c, state) < 100) {
					gatherRejected.add(c);
					return c;
				}
			}
		}
		return null;
	}

	// ---- mining and placing -------------------------------------------------------------

	private void executeStep(PathStep step) {
		ServerLevel level = bot.level();
		// Dropped below both ends of this step (the floor was dug out from under us): re-plan
		// from where we are instead of jumping at a ledge that's no longer within reach.
		if (bot.getY() < Math.min(step.from().getY(), step.to().getY()) - 0.6 && !isInWater()) {
			replan("掉到了这一步的起点和终点下面");
			return;
		}
		while (breakIndex < step.toBreak().size()) {
			BlockPos pos = step.toBreak().get(breakIndex);
			if (LocalPathfinder.isPassable(level, pos)) {
				abortBreaking();
				breakIndex++;
				breakAttempts = 0;
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (!Guard.canBreak(level, pos, state)) {
				replan("要挖的方块不允许挖：" + state.getBlock().getName().getString());
				return;
			}
			mine(pos, state);
			return;
		}
		if (step.place() != null && step.type() != PathStep.Type.PILLAR && !placed) {
			if (!placeBlock(step.place())) {
				replan("搭桥放方块失败");
				return;
			}
			placed = true;
			return;
		}
		move(step);
	}

	/**
	 * DONE: it broke the block itself. GONE: the block changed or went without it breaking it (a
	 * kelp stalk falling off when its root went, a kelp stalk turning into a kelp tip, another
	 * player): nothing of its doing, so nothing to collect or check from it.
	 */
	public enum MineResult {
		RUNNING, DONE, GONE, FAILED,
		/** The hand check passed to {@link #mineTarget} said no: nothing was hit. */
		WRONG_HAND
	}

	/** The last block this bot broke itself (the server accepted its break), and the tick it broke. */
	private BlockPos brokeLast;
	private int brokeAt;

	/**
	 * The tick the block last reported DONE actually broke. The task may only notice a tick or
	 * many later (other layers can run first), and what dropped is told apart by that moment.
	 */
	public int lastBreakTick() {
		return brokeAt;
	}

	private MineResult finished(BlockPos pos) {
		breakingPos = null;
		harvestTarget = null;
		boolean mine = pos.equals(brokeLast);
		brokeLast = null;
		return mine ? MineResult.DONE : MineResult.GONE;
	}

	/** Tool choice for {@link #mine}: pick the fastest tool automatically. */
	public static final int AUTO_TOOL = -3;
	/** Tool choice for {@link #mine}: bare hand (an empty hotbar slot). */
	public static final int HAND = -1;

	private BlockPos harvestTarget;

	/**
	 * Mines the block a task wants, with a specific tool (inventory slot, or {@link #HAND}), going
	 * through the normal survival break flow. Call every tick until it returns something other
	 * than RUNNING: DONE once it has broken the block, GONE if the block stopped being
	 * {@code expected} some other way.
	 */
	public MineResult mineTarget(BlockPos pos, net.minecraft.world.level.block.Block expected, int toolSlot) {
		return mineTarget(pos, expected, toolSlot, false);
	}

	/**
	 * @param obstacle the block is in the way (e.g. hiding the target), not the thing being
	 *                 collected: checked against the stricter {@link Guard#canBreak} rules
	 */
	public MineResult mineTarget(BlockPos pos, net.minecraft.world.level.block.Block expected, int toolSlot, boolean obstacle) {
		return mineTarget(pos, expected, toolSlot, obstacle, null);
	}

	/**
	 * @param handOk checked on exactly what is in hand once the tool has been taken out, right
	 *               before the first hit (taking a tool out of the bag swaps it with the hand, so
	 *               checking anywhere else may look at a different item); null: no check
	 */
	public MineResult mineTarget(BlockPos pos, net.minecraft.world.level.block.Block expected, int toolSlot, boolean obstacle,
								 Predicate<ItemStack> handOk) {
		if (!pos.equals(harvestTarget)) {
			abortBreaking();
			harvestTarget = pos;
			breakAttempts = 0;
		}
		BlockState state = bot.level().getBlockState(pos);
		if (!state.is(expected)) {
			return finished(pos);
		}
		boolean allowed = obstacle ? Guard.canBreak(bot.level(), pos, state) : Guard.canHarvest(bot.level(), pos, state);
		if (!allowed) {
			return MineResult.FAILED;
		}
		return mine(pos, state, toolSlot, handOk);
	}

	/**
	 * Breaks a block the bot placed itself (its crafting table or furnace) to take it back. Skips
	 * the terrain rules, which would forbid it under strict rules; protection zones still apply.
	 */
	public MineResult mineOwnBlock(BlockPos pos, net.minecraft.world.level.block.Block expected) {
		if (!pos.equals(harvestTarget)) {
			abortBreaking();
			harvestTarget = pos;
			breakAttempts = 0;
		}
		BlockState state = bot.level().getBlockState(pos);
		if (!state.is(expected)) {
			return finished(pos);
		}
		if (com.vale.helperbot.guard.ProtectionZones.isProtected(bot.level(), pos)) {
			return MineResult.FAILED;
		}
		return mine(pos, state, AUTO_TOOL);
	}

	private void mine(BlockPos pos, BlockState state) {
		if (mine(pos, state, AUTO_TOOL) == MineResult.FAILED && !gathering) {
			replan("挖不动 " + pos.toShortString());
		}
	}

	private MineResult mine(BlockPos pos, BlockState state, int tool) {
		return mine(pos, state, tool, null);
	}

	private MineResult mine(BlockPos pos, BlockState state, int tool, Predicate<ItemStack> handOk) {
		ServerLevel level = bot.level();
		if (bot.isGhost()) {
			return MineResult.FAILED; // in a protected chunk it touches nothing (plans shouldn't ask)
		}
		if (!pos.equals(breakingPos)) {
			abortBreaking();
			if (++breakAttempts > 3) {
				return MineResult.FAILED;
			}
			holdForMining(tool, state);
			if (handOk != null && !handOk.test(bot.getMainHandItem())) {
				breakAttempts--;
				return MineResult.WRONG_HAND;
			}
			lookAt(Vec3.atCenterOf(pos));
			breakingPos = pos;
			breakProgress = 0;
			bot.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
					faceToward(pos), level.getMaxY(), 0);
			bot.swing(InteractionHand.MAIN_HAND);
			// Instant break (kelp, grass...): it's gone already. Not just "is it air": under water
			// a broken block turns into water.
			if (level.getBlockState(pos) != state) {
				breakingPos = null;
				brokeLast = pos;
				brokeAt = bot.tickCount;
			}
			return MineResult.RUNNING;
		}
		lookAt(Vec3.atCenterOf(pos));
		breakProgress += state.getDestroyProgress(bot, level, pos);
		bot.swing(InteractionHand.MAIN_HAND);
		if (breakProgress >= 1f) {
			bot.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
					faceToward(pos), level.getMaxY(), 0);
			level.destroyBlockProgress(bot.getId(), pos, -1);
			// If the server didn't accept it, the next attempt starts over (counted by breakAttempts).
			// If it did, whatever is there next (sand falling in from above) is a fresh block.
			if (level.getBlockState(pos) != state) {
				breakAttempts = 0;
				brokeLast = pos;
				brokeAt = bot.tickCount;
			}
			breakingPos = null;
		} else {
			level.destroyBlockProgress(bot.getId(), pos, (int) (breakProgress * 10));
		}
		return MineResult.RUNNING;
	}

	/**
	 * Bare hand. It matters for drops (clay with a silk-touch pickaxe in hand gives the block, not
	 * the balls), so with a full hotbar it really empties the hand instead of keeping whatever is
	 * held: put that away in the bag, or failing that hold something that acts like a hand.
	 */
	/** Takes out what {@link #mineTarget} would use for this block: {@link #AUTO_TOOL}, {@link #HAND} or a slot. */
	public void holdForMining(int tool, BlockState state) {
		if (tool == AUTO_TOOL) {
			selectTool(state);
		} else if (tool == HAND) {
			selectEmptyHand();
		} else {
			selectSlot(tool);
		}
	}

	private void selectEmptyHand() {
		Inventory inv = bot.getInventory();
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			if (inv.getItem(i).isEmpty()) {
				inv.setSelectedSlot(i);
				return;
			}
		}
		int selected = inv.getSelectedSlot();
		for (int i = Inventory.getSelectionSize(); i < Inventory.INVENTORY_SIZE; i++) {
			if (inv.getItem(i).isEmpty()) {
				inv.setItem(i, inv.getItem(selected));
				inv.setItem(selected, ItemStack.EMPTY);
				return;
			}
		}
		// Bag full too: a plain item (no enchantment, not a tool) breaks blocks like a bare hand.
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEnchanted() && !stack.isDamageableItem()) {
				inv.setSelectedSlot(i);
				return;
			}
		}
	}

	private void abortBreaking() {
		if (breakingPos != null) {
			bot.gameMode.handleBlockBreakAction(breakingPos, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
					Direction.UP, bot.level().getMaxY(), 0);
			bot.level().destroyBlockProgress(bot.getId(), breakingPos, -1);
			breakingPos = null;
		}
	}

	/** Places a building block at {@code pos} through the normal survival "use item on block" flow. */
	private boolean placeBlock(BlockPos pos) {
		ServerLevel level = bot.level();
		if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
			return true;
		}
		if (!Guard.canPlace(level, pos)) {
			return false;
		}
		int slot = BuildingBlocks.bestSlot(bot.getInventory());
		if (slot < 0) {
			return false;
		}
		return placeItem(pos, slot);
	}

	/**
	 * Places the block item in inventory {@code slot} at {@code pos}, against any solid neighbour,
	 * through the normal survival "use item on block" flow. The caller checks the rules.
	 */
	public boolean placeItem(BlockPos pos, int slot) {
		ServerLevel level = bot.level();
		if (bot.isGhost() || com.vale.helperbot.guard.Restrictions.isBlocked(bot.getInventory().getItem(slot).getItem())) {
			return false;
		}
		Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
		for (Direction dir : order) {
			BlockPos against = pos.relative(dir);
			if (level.getBlockState(against).getCollisionShape(level, against).isEmpty()) {
				continue;
			}
			Direction face = dir.getOpposite();
			Vec3 hit = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			selectSlot(slot);
			lookAt(hit);
			// Sneak so clicking a chest or door places against it instead of using it.
			bot.setShiftKeyDown(true);
			bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND,
					new BlockHitResult(hit, face, against, false));
			bot.setShiftKeyDown(false);
			bot.swing(InteractionHand.MAIN_HAND);
			return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
		}
		return false;
	}

	private void selectTool(BlockState state) {
		Inventory inv = bot.getInventory();
		int slot = BreakTimes.bestToolSlot(inv, state);
		if (slot < 0) {
			selectEmptyHand();
			return;
		}
		selectSlot(slot);
	}

	/** Puts the item in {@code slot} into the main hand, swapping it into the hotbar if needed. */
	public void selectSlot(int slot) {
		Inventory inv = bot.getInventory();
		if (Inventory.isHotbarSlot(slot)) {
			inv.setSelectedSlot(slot);
			return;
		}
		int selected = inv.getSelectedSlot();
		ItemStack held = inv.getItem(selected);
		inv.setItem(selected, inv.getItem(slot));
		inv.setItem(slot, held);
	}

	private Direction faceToward(BlockPos pos) {
		Vec3 d = bot.getEyePosition().subtract(Vec3.atCenterOf(pos));
		double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
		if (ay >= ax && ay >= az) {
			return d.y > 0 ? Direction.UP : Direction.DOWN;
		}
		if (ax >= az) {
			return d.x > 0 ? Direction.EAST : Direction.WEST;
		}
		return d.z > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	public void lookAt(Vec3 point) {
		bot.lookAt(EntityAnchorArgument.Anchor.EYES, point);
		bot.setYHeadRot(bot.getYRot());
	}

	// ---- movement -----------------------------------------------------------------------

	/**
	 * One tick of a step, in three parts: get up to the target floor (step up, jump, or swim
	 * up), move horizontally, then come down onto the target floor.
	 */
	private void move(PathStep step) {
		if (!stepStarted) {
			stepFloor = step.type() == PathStep.Type.PILLAR
					? step.from().getY() + 1
					: LocalPathfinder.floorHeight(bot.level(), step.to());
			if (Double.isNaN(stepFloor) && LocalPathfinder.isOpenWater(bot.level(), step.to())) {
				// Deep water, past the dive limit: still fine to swim through (e.g. heading up).
				stepFloor = LocalPathfinder.swimHeight(bot.level(), step.to());
			}
			if (Double.isNaN(stepFloor)) {
				replan("这一步的落脚点没了");
				return;
			}
			stepStarted = true;
		}
		// Steps that go straight up or down were planned for the middle of the cell. Off-centre
		// (after shuffling for an item, being pushed...) half the body is under the next column,
		// whose ceiling may be lower: jumping there just bumps the head. Step back to the middle.
		if (!centered && needsCentre(step) && !isInWater()) {
			if (recentre(step.from())) {
				return;
			}
			centered = true;
		}
		BlockPos to = step.to();
		double tx = to.getX() + 0.5;
		double tz = to.getZ() + 0.5;
		double ty = stepFloor;
		double yAtStart = bot.getY();

		if (isInWater() && LocalPathfinder.isWater(bot.level(), to)) {
			swimTowards(tx, ty, tz);
			return;
		}
		if (step.type() == PathStep.Type.PILLAR) {
			pillar(step, ty);
			return;
		}

		// 1. Up: vanilla step-up for small rises, a real jump arc for bigger ones.
		double rise = ty - bot.getY();
		BlockPos from = step.from();
		boolean stair = LocalPathfinder.stairStep(bot.level(), to, to.getX() - from.getX(), to.getZ() - from.getZ());
		if (rise > EPS) {
			boolean grounded = isSupported();
			if (rise <= LocalPathfinder.STEP_HEIGHT + EPS && grounded) {
				tryMove(0, rise, 0);
			} else if (stair && rise <= 1 + EPS && (grounded || verticalVelocity == 0)) {
				// Up a stair: half a block at a time, like a player walking up it (no jump: under
				// a low ceiling a jump bumps the head).
				tryMove(0, Math.min(rise, 0.5), 0);
			} else if (grounded || verticalVelocity > 0) {
				if (grounded) {
					verticalVelocity = JUMP_VELOCITY;
					bot.causeFoodExhaustion(bot.isSprinting() ? 0.2f : 0.05f); // vanilla jump cost
				}
				tryMove(0, Math.min(verticalVelocity, rise), 0);
				verticalVelocity = (verticalVelocity - GRAVITY) * DRAG;
			} else {
				// Jump peaked below the ledge: fall back down and jump again.
				fall();
			}
		}

		// 2. Across, at vanilla walking/sprinting/swimming speed.
		double hx = tx - bot.getX(), hz = tz - bot.getZ();
		double horizontal = Math.sqrt(hx * hx + hz * hz);
		if (horizontal > EPS) {
			boolean swimming = isInWater();
			// Vanilla: no sprinting with 3 drumsticks (6 food) or less.
			// Vanilla: no sprinting while eating either, and eating slows you to a fifth.
			boolean eating = bot.isUsingItem();
			boolean sprint = !swimming && !eating && mode.sprint && bot.getFoodData().getFoodLevel() > 6;
			bot.setSprinting(sprint);
			double speed = swimming ? LocalPathfinder.SWIM_SPEED : sprint ? Mode.SPRINT_SPEED : Mode.WALK_SPEED;
			if (eating) {
				speed *= 0.2;
			}
			double s = Math.min(speed, horizontal);
			face(hx, hz);
			double mx = hx / horizontal * s, mz = hz / horizontal * s;
			// Like vanilla collision: if the full move is blocked, slide along whichever axis is free
			// (e.g. a block at head height brushing the side of the body when off-centre).
			if (tryMove(mx, 0, mz) || (Math.abs(mx) > EPS && tryMove(mx, 0, 0)) || (Math.abs(mz) > EPS && tryMove(0, 0, mz))) {
				// Vanilla hunger cost per block: 0.1 sprinting, 0.01 swimming, nothing for walking.
				bot.causeFoodExhaustion((float) (s * (swimming ? 0.01 : sprint ? 0.1 : 0.0)));
				blockedTicks = 0;
			} else if (offCentre(step.from()) && recentre(step.from())) {
				// Still stuck while off the middle of the cell: the body pokes into the next column.
				// Step back to the middle, where the plan assumed it would be.
				blockedTicks = 0;
			} else if (Math.abs(bot.getY() - yAtStart) < EPS && ++blockedTicks >= BLOCKED_LEARN_TICKS) {
				// Can't go sideways and not going up either: something the plan thought was open
				// is in the way. Remember what, and route around it.
				learnObstacle(bot.getBoundingBox().move(mx, 0, mz));
				replan("前进方向被挡住");
				return;
			}
		} else {
			bot.setSprinting(false);
		}

		// 3. Down: gravity (or sinking to the surface level in water), landing exactly on the floor.
		if (bot.getY() > ty + EPS && rise <= EPS) {
			verticalVelocity = Math.min(verticalVelocity, 0);
			verticalVelocity = (verticalVelocity - GRAVITY) * DRAG;
			double dy = Math.max(verticalVelocity, ty - bot.getY());
			if (!moveDown(dy)) {
				verticalVelocity = 0;
			}
		}

		if (Math.abs(bot.getX() - tx) < 1e-3 && Math.abs(bot.getZ() - tz) < 1e-3 && Math.abs(bot.getY() - ty) < 1e-3) {
			nextStep();
		}
	}

	private static boolean needsCentre(PathStep step) {
		boolean vertical = step.from().getX() == step.to().getX() && step.from().getZ() == step.to().getZ();
		return step.type() == PathStep.Type.PILLAR || step.type() == PathStep.Type.ASCEND || vertical;
	}

	private boolean offCentre(BlockPos cell) {
		double dx = cell.getX() + 0.5 - bot.getX(), dz = cell.getZ() + 0.5 - bot.getZ();
		return dx * dx + dz * dz > 0.05 * 0.05 && feet().getX() == cell.getX() && feet().getZ() == cell.getZ();
	}

	/** Walk towards the centre of {@code cell}. True while still on the way there. */
	private boolean recentre(BlockPos cell) {
		double dx = cell.getX() + 0.5 - bot.getX(), dz = cell.getZ() + 0.5 - bot.getZ();
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1e-3) {
			return false;
		}
		double s = Math.min(Mode.WALK_SPEED, len);
		// Blocked (shouldn't happen, the cell's centre is where we planned to stand): go on as is.
		return tryMove(dx / len * s, 0, dz / len * s) && len > s;
	}

	/**
	 * Jump and put a block under ourselves, like a player towering up: a full vanilla jump (peak
	 * about 1.25 blocks), the block placed once the feet are clearly above the cell, then land on
	 * it. Placing at exactly one block up could leave the hitbox overlapping the cell by a rounding
	 * error, which vanilla rejects; that used to loop forever (jump, fail, fall, re-plan, jump...).
	 */
	private void pillar(PathStep step, double ty) {
		if (!placed) {
			if (verticalVelocity <= 0 && isSupported()) {
				if (++pillarJumps > MAX_PILLAR_JUMPS) {
					replan("垫柱子连跳 3 次都没放上");
					return;
				}
				verticalVelocity = JUMP_VELOCITY;
				bot.causeFoodExhaustion(0.05f);
			}
			if (verticalVelocity > 0) {
				if (!tryMove(0, verticalVelocity, 0)) {
					// Bumped our head on something the plan thought was open: remember it.
					learnObstacle(bot.getBoundingBox().move(0, verticalVelocity, 0));
					replan("垫柱子时头顶被挡住");
					return;
				} else {
					verticalVelocity = (verticalVelocity - GRAVITY) * DRAG;
				}
			} else {
				fall();
			}
			if (bot.getY() >= step.place().getY() + 1 + PILLAR_CLEARANCE) {
				if (!placeBlock(step.place())) {
					replan("垫柱子放方块失败");
					return;
				}
				placed = true;
			}
			return;
		}
		// Block is in: finish the jump arc and land on it.
		if (verticalVelocity > 0) {
			tryMove(0, verticalVelocity, 0);
			verticalVelocity = (verticalVelocity - GRAVITY) * DRAG;
		} else if (bot.getY() > ty + EPS) {
			verticalVelocity = (verticalVelocity - GRAVITY) * DRAG;
			if (!moveDown(Math.max(verticalVelocity, ty - bot.getY()))) {
				verticalVelocity = 0;
			}
		}
		if (Math.abs(bot.getY() - ty) < 1e-3 && isGrounded()) {
			pillarJumps = 0;
			nextStep();
		}
	}

	/** Water to water: a straight 3D line at swimming speed (rising and sinking a bit slower). */
	private void swimTowards(double tx, double ty, double tz) {
		double dx = tx - bot.getX(), dy = ty - bot.getY(), dz = tz - bot.getZ();
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len > EPS) {
			double s = Math.min(LocalPathfinder.SWIM_SPEED, len) / len;
			double mx = dx * s, my = Math.max(-SWIM_VERTICAL_SPEED, Math.min(SWIM_VERTICAL_SPEED, dy * s)), mz = dz * s;
			if (Math.abs(dx) > EPS || Math.abs(dz) > EPS) {
				face(dx, dz);
			}
			// Same safety net as walking: slide along whatever is free, and if nothing moves at all
			// for a while, remember the obstacle and plan around it.
			boolean moved = tryMove(mx, my, mz);
			if (!moved) {
				boolean across = Math.abs(mx) > EPS || Math.abs(mz) > EPS;
				moved |= Math.abs(my) > EPS && tryMove(0, my, 0);
				moved |= across && (tryMove(mx, 0, mz) || (Math.abs(mx) > EPS && tryMove(mx, 0, 0))
						|| (Math.abs(mz) > EPS && tryMove(0, 0, mz)));
			}
			if (moved) {
				blockedTicks = 0;
			} else if (++blockedTicks >= BLOCKED_LEARN_TICKS) {
				learnObstacle(bot.getBoundingBox().move(mx, my, mz));
				replan("游泳时被挡住");
				return;
			}
		}
		if (Math.abs(bot.getX() - tx) < 1e-3 && Math.abs(bot.getZ() - tz) < 1e-3 && Math.abs(bot.getY() - ty) < 1e-3) {
			nextStep();
		}
	}

	private boolean tryMove(double dx, double dy, double dz) {
		if (dx == 0 && dy == 0 && dz == 0) {
			return true;
		}
		AABB box = bot.getBoundingBox().move(dx, dy, dz);
		if (collides(box)) {
			return false;
		}
		setPosition(bot.getX() + dx, bot.getY() + dy, bot.getZ() + dz);
		return true;
	}

	/**
	 * Would the body at {@code box} run into something? Like vanilla, a block the body is already
	 * inside doesn't count: sand that fell onto its legs mustn't freeze it in place (it used to,
	 * and it drowned there), it can still swim up out of it.
	 */
	private boolean collides(AABB box) {
		AABB now = bot.getBoundingBox();
		for (VoxelShape shape : bot.level().getBlockCollisions(bot, box)) {
			for (AABB part : shape.toAabbs()) {
				if (part.intersects(box) && !part.intersects(now)) {
					return true;
				}
			}
		}
		return !bot.level().getEntityCollisions(bot, box).isEmpty();
	}

	/**
	 * Moves down by {@code dy} (negative), or as far as possible if something is in the way.
	 * Returns false if it landed on something before covering the full distance.
	 */
	private boolean moveDown(double dy) {
		if (tryMove(0, dy, 0)) {
			return true;
		}
		double lo = 0, hi = dy;
		for (int i = 0; i < 12; i++) {
			double mid = (lo + hi) / 2;
			if (!collides(bot.getBoundingBox().move(0, mid, 0))) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		if (lo < 0) {
			setPosition(bot.getX(), bot.getY() + lo, bot.getZ());
		}
		return false;
	}

	private void setPosition(double x, double y, double z) {
		bot.setPos(x, y, z);
		bot.setDeltaMovement(Vec3.ZERO);
		bot.setOnGround(isGrounded());
		bot.resetFallDistance();
	}

	/**
	 * Face the walking direction, eyes slightly down like a player watching their footing. A dead
	 * level gaze lines up with distant endermen's heads, which counts as staring and angers them.
	 */
	private void face(double dx, double dz) {
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		bot.setYRot(yaw);
		bot.setYHeadRot(yaw);
		bot.setXRot(WALK_PITCH);
	}

	private boolean isGrounded() {
		return !bot.level().noCollision(bot, bot.getBoundingBox().move(0, -0.03, 0));
	}

	private boolean isInWater() {
		return LocalPathfinder.isWater(bot.level(), feet());
	}

	/** Floating at a water surface, head in the air. */
	private boolean isFloating() {
		BlockPos f = feet();
		return LocalPathfinder.isWater(bot.level(), f)
				&& !LocalPathfinder.isWater(bot.level(), f.above())
				&& Math.abs(bot.getY() - (f.getY() + LocalPathfinder.SWIM_FLOOR)) < 0.05;
	}

	/** Standing, or in water (where it can hover between steps, like a player treading water). */
	private boolean isSupported() {
		return isGrounded() || isInWater();
	}

	/** Gravity when not following a step: land exactly on the surface, or float up in water. */
	private void fall() {
		if (isGrounded()) {
			verticalVelocity = 0;
			return;
		}
		BlockPos f = feet();
		if (LocalPathfinder.isWater(bot.level(), f)) {
			verticalVelocity = 0;
			if (LocalPathfinder.isWater(bot.level(), f.above())) {
				// Fully under water and idle: sink slowly to the bottom, like a player not swimming.
				// (Floating up used to lift it away from whatever it was mining, over and over.)
				// Running out of air is handled separately, see BotPlayer#breathe.
				// While working at a spot (e.g. mining), only settle if the bottom is right there:
				// sinking further could take it out of reach of the work, and back up again, forever.
				boolean bottomClose = !bot.level().noCollision(bot, bot.getBoundingBox().move(0, -1.0, 0));
				if (status != Status.ARRIVED || bottomClose) {
					moveDown(-SINK_SPEED);
				}
				return;
			}
			double surface = f.getY() + LocalPathfinder.SWIM_FLOOR;
			double dy = Math.max(-SWIM_UP_SPEED, Math.min(SWIM_UP_SPEED, surface - bot.getY()));
			if (dy < 0) {
				moveDown(dy);
			} else {
				tryMove(0, dy, 0);
			}
			return;
		}
		verticalVelocity = Math.min(verticalVelocity, 0);
		verticalVelocity = (verticalVelocity - GRAVITY) * DRAG;
		if (!moveDown(verticalVelocity)) {
			verticalVelocity = 0;
		}
	}

	/** Water cells straight above the head, up to 64: how far it is to air. */
	public int waterAbove() {
		BlockPos head = BlockPos.containing(bot.getEyePosition());
		int n = 0;
		while (n < 64 && LocalPathfinder.isWater(bot.level(), head.above(n))) {
			n++;
		}
		return n;
	}

	/** Off a column's centre by at most this (per axis), the 0.6 wide body fits inside it. */
	private static final double IN_COLUMN = 0.18;

	/**
	 * One tick of getting to air: swim straight up; under a ceiling, sidestep towards the nearest
	 * column that's open upwards (its own one too: off its centre, the body is partly under the
	 * next column's ceiling, e.g. the ice beside the hole it just made); with none, dig through
	 * the ceiling. Works while paused. Returns true once the head is out of the water (then it
	 * just floats at the surface).
	 */
	public boolean ascendStep() {
		ServerLevel level = bot.level();
		if (!bot.isEyeInFluid(FluidTags.WATER)) {
			fall();
			return true;
		}
		if (tryMove(0, SWIM_VERTICAL_SPEED, 0)) {
			return false;
		}
		BlockPos f = feet();
		BlockPos best = null;
		for (int r = 0; r <= 4 && best == null; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					BlockPos c = f.offset(dx, 0, dz);
					if (swimmable(level, c) && swimmable(level, c.above()) && swimmable(level, c.above(2))
							&& (best == null || c.distSqr(f) < best.distSqr(f))) {
						best = c;
					}
				}
			}
		}
		if (best != null) {
			double dx = best.getX() + 0.5 - bot.getX(), dz = best.getZ() + 0.5 - bot.getZ();
			double len = Math.sqrt(dx * dx + dz * dz);
			if (Math.abs(dx) > IN_COLUMN || Math.abs(dz) > IN_COLUMN) {
				double s = Math.min(LocalPathfinder.SWIM_SPEED, len);
				// Can't get over there (something in between): dig up instead of trying forever.
				if (tryMove(dx / len * s, 0, dz / len * s) || tryMove(dx / len * s, 0, 0) || tryMove(0, 0, dz / len * s)) {
					return false;
				}
			}
		}
		// The ceiling over its own column: the first cell above the head that isn't open.
		for (int k = 1; k <= 3; k++) {
			BlockPos c = f.above(k);
			if (!swimmable(level, c)) {
				mineTarget(c, level.getBlockState(c).getBlock(), AUTO_TOOL, true);
				return false;
			}
		}
		return false;
	}

	private static boolean swimmable(ServerLevel level, BlockPos pos) {
		return LocalPathfinder.isOpenWater(level, pos) || LocalPathfinder.isPassable(level, pos);
	}

	private void settleToGround() {
		for (int i = 0; i < 40 && !isSupported(); i++) {
			if (LocalPathfinder.isWater(bot.level(), feet())) {
				fall();
			} else {
				moveDown(-0.5);
			}
		}
	}

	/** Vanilla-style fall damage, measured from the highest point since leaving the ground. Water breaks the fall. */
	private void trackFallDamage() {
		double y = bot.getY();
		if (LocalPathfinder.isWater(bot.level(), feet())) {
			airborneFromY = Double.NaN;
			return;
		}
		if (!isGrounded()) {
			airborneFromY = Double.isNaN(airborneFromY) ? y : Math.max(airborneFromY, y);
			return;
		}
		if (!Double.isNaN(airborneFromY)) {
			double fallen = airborneFromY - y;
			if (fallen > 3.0) {
				bot.hurtServer(bot.level(), bot.damageSources().fall(), (float) Math.ceil(fallen - 3.0));
			}
		}
		airborneFromY = Double.NaN;
	}

	private void showPath() {
		if (path == null) {
			return;
		}
		for (int i = index; i < path.size(); i++) {
			PathStep step = path.get(i);
			BlockPos p = step.to();
			bot.level().sendParticles(ParticleTypes.END_ROD, p.getX() + 0.5, p.getY() + 0.2, p.getZ() + 0.5, 1, 0, 0, 0, 0);
			for (BlockPos b : step.toBreak()) {
				bot.level().sendParticles(ParticleTypes.FLAME, b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, 1, 0, 0, 0, 0);
			}
			if (step.place() != null) {
				BlockPos b = step.place();
				bot.level().sendParticles(ParticleTypes.HAPPY_VILLAGER, b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, 2, 0.2, 0.2, 0.2, 0);
			}
		}
	}
}
