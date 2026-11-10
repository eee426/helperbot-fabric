package com.vale.helperbot.bot;

import com.vale.helperbot.guard.Guard;
import com.vale.helperbot.guard.Placement;
import com.vale.helperbot.guard.ProtectionZones;
import com.vale.helperbot.guard.Rules;
import com.vale.helperbot.guard.ZoneExit;
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

	/** Place the pillar block only once the feet are this far above the cell (see {@link #pillar}). */
	static final double PILLAR_CLEARANCE = 0.05;
	/** Jumps allowed for one pillar step before giving up on it (e.g. something blocks the jump). */
	static final int MAX_PILLAR_JUMPS = 3;
	int pillarJumps;
	/** This step has (if it needed to) stepped back to the middle of its starting cell. */
	private boolean centered;
	/** Start planning the next stretch when this few steps of the current path are left. */
	static final int LOOKAHEAD_STEPS = 5;
	static final int MAX_EMPTY_PLANS = 2;
	/** Search bigger (then give up) when the distance to the target hasn't improved for this long. */
	private static final int NO_PROGRESS_TICKS = 400;
	/** Treat the bot as stuck when it hasn't moved at all for this long. */
	private static final int FROZEN_TICKS = 40;
	private static final int MAX_RECOVERIES = 4;
	/** Gather blocks when fewer than this are left and no path exists. */
	static final int LOW_BLOCKS = 8;
	private static final int GATHER_TARGET = 32;

	final BotPlayer bot;
	/** Which search to run, and what to make of it (see RouteSearch). */
	final RouteSearch routes;
	/** Moving the body by vanilla's rules (see Body). */
	final Body body;
	/** Mining and placing, the hands of the bot (see Hands). */
	final Hands hands;
	Status status = Status.IDLE;
	private String failReason = "";
	PathGoal goal;
	BlockPos target;
	Mode mode = Mode.DEFAULT;
	boolean horizontalOnly;

	boolean pathReachesGoal;
	boolean replanRequested;

	List<PathStep> path;
	int index;
	int emptyPlans;
	private double bestDistance;
	private int ticksWithoutProgress;

	private int breakIndex;
	private boolean stepStarted;
	boolean placed;
	private double stepFloor;

	private Vec3 lastPosition = Vec3.ZERO;
	private int frozenTicks;
	private int recoveries;


	boolean gathering;
	private int gatherWait;
	final Set<BlockPos> gatherRejected = new HashSet<>();

	MovementController(BotPlayer bot) {
		this.bot = bot;
		this.routes = new RouteSearch(this);
		this.body = new Body(bot);
		this.hands = new Hands(bot);
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
		this.leaving = null;
		this.provenFrom = null;
		this.goal = goal;
		this.target = target;
		this.mode = mode;
		this.locked = !horizontalOnly && inSight(target);
		routes.lockNodes = LOCK_NODES;
		routes.searchedNodes = 0;
		// Where it can't see yet it goes by the map, not towards a depth that may lie under the sea.
		this.horizontalOnly = !locked;
		routes.minLevel = 0;
		this.status = Status.MOVING;
		this.bestDistance = Double.MAX_VALUE;
		this.ticksWithoutProgress = 0;
		this.emptyPlans = 0;
		this.recoveries = 0;
		this.frozenTicks = 0;
		this.goalWork = 0;
	}

	/** What it will do on arrival, in ticks: set right after going there (see Air.Profile#workTicks). */
	public void workAtGoal(double ticks) {
		goalWork = ticks;
	}

	private double goalWork;

	/**
	 * Going to something it knows the place of, and can see all the way to: a block, an item, an
	 * animal, a player nearby. It doesn't set off without the whole way (see {@link #startLocked}):
	 * yes or no, before the first step. Heading off a bit at a time towards a place it could not see
	 * took it to the sea bed, nearest the target by the crow, and nothing to dig there.
	 */
	boolean locked;
	/** Past this it goes by the windows (LocalPathfinder#LEVELS), like anywhere it can't see. */
	private static final int LOCK_RANGE = 128;
	/** About 15 seconds in the background: no way found by then counts as none. */
	public static final int LOCK_NODES = 1_000_000;

	/**
	 * Hold the search of the {@link #goTo} just called to what's left of what the caller has to spend
	 * here: one budget for all it tries in one place, so a place with nothing to be had costs the same
	 * however many targets there are.
	 */
	public void limitSearch(int nodes) {
		routes.lockNodes = Math.max(1, nodes);
	}

	/** Nodes the last whole search went through. */
	public int searchedNodes() {
		return routes.searchedNodes;
	}

	private boolean inSight(BlockPos at) {
		BlockPos from = feet();
		return Math.abs(at.getX() - from.getX()) <= LOCK_RANGE && Math.abs(at.getZ() - from.getZ()) <= LOCK_RANGE
				&& bot.level().getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) != null;
	}

	/**
	 * Keep up with something that moves, close by, once it knows it can get there (see {@link #locked}):
	 * the small windows, again and again, as it drifts or runs. A whole search each time would have
	 * it stand and think while the thing got away.
	 */
	public void follow(PathGoal goal, BlockPos target, Mode mode) {
		goTo(goal, target, mode);
		locked = false;
		horizontalOnly = false;
	}

	/**
	 * Where it stood when a whole search found the target out of reach, null if it hasn't. Only ever
	 * from a search: it may run short of breath on a way that's there, and that says nothing.
	 */
	public BlockPos provenFrom() {
		return provenFrom;
	}

	/**
	 * Is there a way to any of these, with the way as it is? One search through all of it, from the
	 * bot's chunk to the targets' and everything between, top to bottom, breaths included, with
	 * nothing dug on the way (see LocalPathfinder#reachOnly). It takes the first it comes to; if it
	 * comes to none, that's known at once, for all of them: it's what diving to find out, a target
	 * at a time, took minutes and most of its air to learn. Started with the head out of the water,
	 * where thinking costs nothing. Its route is then the way, and is walked without thinking again
	 * unless the world or its breath turn out otherwise; {@link #wayEnd} says where it comes out, and
	 * {@link #provenFrom} is set if there's none.
	 *
	 * @param anyOf where each target can be got at from
	 */
	public void findWay(List<BlockPos> targets, PathGoal anyOf, Mode mode) {
		BlockPos from = feet();
		goTo(anyOf, targets.stream().min(java.util.Comparator.comparingDouble(from::distSqr)).orElse(from), mode);
		routes.wayEnd = null;
		routes.wayTargets = targets;
		routes.wayOneSide = true;
		routes.wayDug = false;
	}

	public boolean findingWay() {
		return routes.wayTargets != null;
	}

	/** Where the route {@link #findWay} found comes out. */
	public BlockPos wayEnd() {
		return routes.wayEnd;
	}

	/** Of what {@link #findWay} looked for, the one the route leads to: the rest is no longer the goal. */
	public void settle(PathGoal goal, BlockPos target) {
		this.goal = goal;
		this.target = target;
	}

	/** Walk out of the protected area it's in, by the nearest way that leads out. */
	public void leaveZone(ZoneExit exit, Mode mode) {
		goTo((w, p) -> exit.outside(w, p), exit.nearestOutside(feet()), mode);
		locked = false;
		horizontalOnly = false;
		leaving = exit;
	}

	/** Changes the destination; the current step finishes, then a new plan is made. */
	public void retarget(PathGoal goal, BlockPos target) {
		this.goal = goal;
		this.target = target;
		this.locked = false;
		this.horizontalOnly = false;
		this.replanRequested = true;
		routes.cancelSearches();
		this.bestDistance = Double.MAX_VALUE;
	}

	/** Within this it follows what moves by the small windows; further off it keeps to the whole way. */
	public static final double FOLLOW_RANGE = 16;

	/** Same, for somewhere new it hasn't made sure of yet (a player who moved on): see {@link #locked}. */
	public void redirect(PathGoal goal, BlockPos target) {
		retarget(goal, target);
		locked = inSight(target);
		horizontalOnly = !locked;
	}

	public void stop() {
		hands.abortBreaking();
		paused = false;
		locked = false;
		engaged = false;
		ignoreAir = false;
		airRequested = false;
		lastPath.clear(); // a new destination: the old route means nothing
		status = Status.IDLE;
		path = null;
		routes.cancelSearches();
		replanRequested = false;
		routes.wayTargets = null;
		goal = null;
		target = null;
		gathering = false;
		resetStep();
	}

	public boolean isMining() {
		return hands.breaking() != null;
	}

	/** Mining and placing. */
	public Hands hands() {
		return hands;
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
			case MOVING -> routes.search != null ? "思考中（搜索范围 ±" + routes.search.limits().radiusXZ() + " 格）"
					: gathering ? "方块不够，正在就地收集（现有 " + BuildingBlocks.count(bot.getInventory()) + "）"
					: hands.breaking() != null ? "正在挖 " + hands.breaking().toShortString()
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
		if (!bot.level().noCollision(bot, moved) || (!grounded && !body.isInWater())) {
			return false;
		}
		body.face(dx, dz);
		body.setPosition(nx, bot.getY(), nz);
		return true;
	}

	/** Standing (or floating) between steps, so it's safe to pause, e.g. to eat. */
	public boolean atRest() {
		return !stepStarted && hands.breaking() == null && body.isSupported();
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
			hands.abortBreaking();
			hands.forgetTarget();
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

	boolean engaged;
	private PathGoal savedGoal;
	private BlockPos savedTarget;
	private Status savedStatus;
	private boolean savedHorizontalOnly;
	private boolean savedLocked;

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
			savedLocked = locked;
			locked = false;
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
	boolean airRequested;

	/** Ticks from here until the head is out of the water (see {@link Escape}). */
	public double escapeTicks() {
		return newEscape().ticks(feet());
	}

	public Escape newEscape() {
		Escape escape = new Escape(bot.level(), bot.getInventory(), airProfile());
		return headUnderwater() ? escape : escape.fromAbove();
	}

	public boolean takeAirRequest() {
		boolean r = airRequested;
		airRequested = false;
		return r;
	}

	/**
	 * Head for the nearest place to breathe, by the normal planner (so it can dig up, go around an
	 * overhang...), remembering what it was doing; {@link #disengage()} goes back to that.
	 */
	public void headForAir() {
		BlockPos f = feet();
		ignoreAir = true; // read when it plans, next tick
		// A cell whose head space is still a block to dig only counts if it won't be water once dug
		// (the goal sees the world as it is now, not as the path will leave it).
		engage((w, p) -> Air.breathable(w, p)
				&& (LocalPathfinder.isPassable(w, p.above()) || !Escape.floodsWhenDug(w, p.above())),
				f.above(Math.max(2, body.waterAbove())), "去换气");
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
		locked = savedLocked;
		bestDistance = Double.MAX_VALUE;
		ticksWithoutProgress = 0;
		recoveries = 0;
		replan("战斗结束，回到原来的事");
	}

	void tick() {
		if (paused) {
			bot.setSprinting(false);
			body.fall(status == Status.ARRIVED);
			body.trackFallDamage();
			bot.setOnGround(body.isGrounded());
			return;
		}
		if (status == Status.MOVING) {
			double x = bot.getX(), y = bot.getY(), z = bot.getZ();
			tickMoving();
			// Not going anywhere this tick (thinking, mining, placing): not sprinting either. Under
			// water it would stay in the swimming pose, lying still with its eyes low.
			if (bot.getX() == x && bot.getY() == y && bot.getZ() == z) {
				bot.setSprinting(false);
			}
		} else {
			bot.setSprinting(false);
			body.fall(status == Status.ARRIVED);
		}
		body.trackFallDamage();
		// Vanilla mines 5x slower when not on the ground, so this flag must be right even while
		// standing still (it used to only update on movement and could stay stale).
		bot.setOnGround(body.isGrounded());
	}

	private void tickMoving() {
		// Gravity first, whatever it's busy with (mining, gathering, thinking): only the movement
		// part of a step moves it vertically itself. If the floor went (e.g. a player dug it out
		// mid-mining), drop what it was doing and re-plan from wherever it lands.
		if (!stepStarted && !body.isSupported()) {
			if (hands.breaking() != null || breakIndex > 0 || placed) {
				replan("脚下的方块没了，先掉下去");
			}
			body.fall(status == Status.ARRIVED);
			return;
		}
		if (gathering) {
			gatherTick();
			return;
		}
		if (routes.wayTargets != null && routes.search == null && !engaged) {
			if (headUnderwater() && Air.budget(bot) < Air.UNLIMITED) {
				airRequested = true; // up first (see findWay)
			} else {
				routes.startWay();
			}
			return;
		}
		if (routes.search != null) {
			// Thinking: standing still while a (possibly large) search runs over several ticks.
			routes.continuePlanning();
			return;
		}
		boolean atStepBoundary = !stepStarted && breakIndex == 0 && hands.breaking() == null;
		if (atStepBoundary) {
			if (goal.test(bot.level(), feet())) {
				// Arrived means at the middle of the cell, which is where plans and goals take it to
				// stand (what it sees and reaches from there). Pushed or shuffled off it (after picking
				// something up, say), it saw a block from the middle that was hidden from where it was.
				if (body.offCentre(feet()) && body.recentre(feet())) {
					return;
				}
				status = Status.ARRIVED;
				path = null;
				return;
			}
			// Dug out the floor of a step straight down, and gravity took it down before the step
			// itself did: it's where the step ends, so that step is done. (Taken for "fell off the
			// path", every block of a shaft re-planned the whole way, a second each.)
			if (path != null && index < path.size() && path.get(index).type() == PathStep.Type.DESCEND
					&& !feet().equals(path.get(index).from()) && feet().equals(path.get(index).to())) {
				nextStep();
				return;
			}
			// Not where the next step starts (fell into a hole someone dug, got pushed...): the rest
			// of this path no longer applies.
			if (path != null && index < path.size() && !feet().equals(path.get(index).from())) {
				replan("不在这一步的起点（被推开或掉下去了）：在 " + feet().toShortString() + "，应该在 " + path.get(index).from().toShortString());
			}
			// Under water a route that gets there is walked to the end: thinking again every second
			// cost nearly half the breath, standing still. Only when its breath isn't what the plan
			// said (what's ahead is checked by aheadChanged). The way up is different: the world
			// there changes fast (sand, flowing water, kelp) and nothing matters more, so again and
			// again from what's there now, quietly, it's routine.
			if (path != null && headUnderwater() && (ignoreAir ? bot.tickCount - routes.plannedAt > WATER_REPLAN_TICKS : shortOfBreath())) {
				hands.abortBreaking();
				path = null;
				routes.cancelLookahead();
			}
			// (Taking a breath before going under is a step of the plan now: see PathStep.Type.WAIT.)
			if (path == null || index >= path.size() || replanRequested) {
				if (!routes.switchToLookahead()) {
					replanRequested = false;
					routes.startPlanning(routes.minLevel);
					routes.continuePlanning();
					return;
				}
			}
		}
		if (path != null && index < path.size()) {
			if (stepTimedOut(path.get(index)) || aheadChanged()) {
				return;
			}
			executeStep(path.get(index));
			routes.advanceLookahead();
		}
		watchdog();
		checkProgress();
		if (bot.isDebugPath() && bot.tickCount % 10 == 0) {
			showPath();
		}
	}

	/** Set while getting out of a protected area is the whole point (see leaveZone). */
	ZoneExit leaving;
	BlockPos provenFrom;
	/** On the way up for air, plan again this often. */
	private static final int WATER_REPLAN_TICKS = 20;

	public Air.Profile airProfile() {
		return Air.profile(bot, !ignoreAir, engaged ? 0 : goalWork, mode.sprint && !bot.isUsingItem() ? Air.sprintBlocks(bot) : 0);
	}

	/** Sprinting is allowed (vanilla: more than 6 food, not eating) and this mode sprints. */
	private boolean canSprintSwim() {
		return mode.sprint && bot.getFoodData().getFoodLevel() > 6 && !bot.isUsingItem();
	}

	public boolean headUnderwater() {
		return bot.isEyeInFluid(FluidTags.WATER);
	}

	/** The last search that found nothing turned steps down for want of air (see onNoPath). */
	boolean noPathForAir;

	void onNoPath() {
		path = null;
		// Only when air was the reason: it used to be assumed whenever it was under water with less
		// than a full breath, and a drop it couldn't reach for another reason sent it up for air.
		if (!ignoreAir && headUnderwater() && noPathForAir && Air.budget(bot) < Air.fullBudget(bot)) {
			// Nowhere to go on the air left: get a full breath and try again (a player diving to
			// dig deeper does the same: down, dig, up, down again).
			bot.log("这口气不够走下去，先去换气");
			airRequested = true;
			return;
		}
		if (Rules.open() && BuildingBlocks.count(bot.getInventory()) < LOW_BLOCKS && !body.isInWater()) {
			// Maybe a bridge or pillar would work: get some blocks first.
			gathering = true;
			gatherRejected.clear();
			return;
		}
		if (++emptyPlans >= MAX_EMPTY_PLANS) {
			int r = routes.levels[routes.levels.length - 1].radiusXZ();
			// Under water the usual reason is air: nowhere it could go and still get back up in time.
			fail(headUnderwater() && !ignoreAir
					? "在水下，一口气能去又能回来换气的地方里找不到路（离换气的出口太远，或者头顶要挖的太慢）"
					: "方圆 " + r + " 格内都找不到能走的路（被保护区、岩浆、太高的墙挡住，或者没有方块可以搭路）");
		}
	}

	private void checkProgress() {
		// (A way that's known may lead off from the target for a while: to the shore, say.)
		if (target == null || status != Status.MOVING || hands.breaking() != null || routes.zoneRoute || locked) {
			return;
		}
		BlockPos here = bot.blockPosition();
		double distance = remaining(here);
		if (distance < bestDistance - 0.5) {
			bestDistance = distance;
			ticksWithoutProgress = 0;
			recoveries = 0;
			if (routes.minLevel > 0 && distance < routes.escalatedAtDistance - 16) {
				routes.minLevel = 0; // well past the obstacle: back to cheap small searches
			}
		} else if (++ticksWithoutProgress > NO_PROGRESS_TICKS) {
			if (routes.minLevel < routes.levels.length - 1) {
				// Probably circling in a dead end the small window can't see out of: think bigger.
				routes.minLevel++;
				routes.escalatedAtDistance = distance;
				ticksWithoutProgress = 0;
				replan("太久没靠近目标，扩大搜索范围");
			} else {
				fail("用最大搜索范围也没能靠近目标，可能在原地绕圈");
			}
		}
	}

	/** Less breath than the plan has for here (see LocalPathfinder.Result#air). */
	private boolean shortOfBreath() {
		return index > 0 && index <= pathAir.size() && Air.budget(bot) < pathAir.get(index - 1) - Air.PLAN_SLACK;
	}

	private TravelEstimate travel;
	private BlockPos travelTarget;
	private boolean travelHorizontal;

	/**
	 * How far it still is, in blocks of running, by the same estimate the planner steers by (see
	 * {@link TravelEstimate}): climbing out of a mine on a long trip counts as getting closer. By
	 * straight-line distance it didn't, and a long climb looked like being stuck.
	 */
	double remaining(BlockPos here) {
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
		if (hands.breaking() != null || now.distanceToSqr(lastPosition) > 1e-6) {
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
		body.settleToGround();
	}

	void fail(String reason) {
		bot.log("移动失败：" + reason);
		hands.abortBreaking();
		failReason = reason;
		status = Status.FAILED;
		path = null;
		gathering = false;
		resetStep();
	}

	void resetStep() {
		stepTicks = 0;
		breakIndex = 0;
		hands.resetAttempts();
		stepStarted = false;
		placed = false;
		centered = false;
		body.verticalVelocity = 0;
		pillarJumps = 0;
		blockedTicks = 0;
	}

	// ---- learning from collisions -------------------------------------------------------

	/** Ticks of being blocked (not moving at all) before the obstacle is learned and avoided. */
	static final int BLOCKED_LEARN_TICKS = 6;
	/** How long a learned obstacle is avoided, in ticks (the world may change, e.g. it gets mined). */
	private static final int AVOID_TICKS = 600;
	int blockedTicks;
	/** Cells that turned out to block movement although the plan thought them open: pos -> expiry tick. */
	final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap avoidUntil = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

	/**
	 * Whatever blocked {@code attempted} (a fence sticking up from below, an odd-shaped block, a
	 * minecart...): mark the cells it occupies inside the attempted box as walls for a while. This
	 * covers any mismatch between the planner's model and real collisions, without special cases.
	 */
	void learnObstacle(AABB attempted) {
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

	void nextStep() {
		stepBegan = bot.tickCount;
		index++;
		resetStep();
	}

	// ---- borrowed from Baritone: step timeout, checking ahead, sticking to the route ------

	/** Real ticks each step of the path should take. */
	private List<Double> pathTicks = List.of();
	/** Breath the plan expects at the end of each step (see LocalPathfinder.Result#air). */
	List<Integer> pathAir = List.of();

	/**
	 * For the log: where it is in the plan, as the plan saw it (the step, the breath it expected at
	 * its start and end, its ticks planned and spent so far), or null if it's not following one.
	 * Where the real breath falls well short, the plan's picture of what moving costs is off.
	 */
	public String planAirNote() {
		if (path == null || index >= path.size() || index >= pathAir.size()) {
			return null;
		}
		PathStep s = path.get(index);
		int before = index > 0 ? pathAir.get(index - 1) : -1;
		return "第 " + (index + 1) + "/" + path.size() + " 步（" + s.type() + (s.toBreak().isEmpty() ? "" : "，要挖 " + s.toBreak().size() + " 块")
				+ "）计划开始时 " + (before >= 0 ? before + " 刻" : "?") + "、结束时 " + pathAir.get(index) + " 刻，计划用 "
				+ String.format(java.util.Locale.ROOT, "%.0f", pathTicks.get(index)) + " 刻、已用 " + (bot.tickCount - stepBegan) + " 刻";
	}

	/** When the current step began (for planAirNote). */
	private int stepBegan;
	/** Cells of the most recent path towards the current goal: favoured when planning again. */
	final it.unimi.dsi.fastutil.longs.LongOpenHashSet lastPath = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	/** Ticks spent on the current step. */
	private int stepTicks;
	/** A step may take this much longer than its estimate before it's given up on. */
	private static final int STEP_TIMEOUT_EXTRA = 100;
	/** How many steps ahead are checked against the world as it is now. */
	private static final int VERIFY_AHEAD = 5;
	private static final int VERIFY_INTERVAL = 5;

	void adopt(LocalPathfinder.Result result) {
		path = result.steps();
		pathTicks = result.ticks();
		pathAir = result.air();
		stepBegan = bot.tickCount;
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
		if (bot.isUsingItem()) {
			return false; // waiting for the hands (see executeStep), not stuck
		}
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
			if (!s.type().placesWhileMoving && Double.isNaN(LocalPathfinder.floorHeight(level, s.to()))
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

	void replan(String why) {
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
		hands.abortBreaking();
		path = null;
		routes.cancelSearches();
		replanRequested = false;
		resetStep();
	}

	// ---- gathering building blocks ------------------------------------------------------

	/** Mine solid blocks right around the body (so the drops land on us) until we have enough. */
	private void gatherTick() {
		if (hands.breaking() != null) {
			mine(hands.breaking(), bot.level().getBlockState(hands.breaking()));
			if (hands.breaking() == null) {
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
		hands.resetAttempts();
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
		if (bot.getY() < Math.min(step.from().getY(), step.to().getY()) - 0.6 && !body.isInWater()) {
			replan("掉到了这一步的起点和终点下面");
			return;
		}
		// Taking out a tool or a block ends a meal, and it starts over: it ate one apple nine times
		// through a tunnel. Walking goes on while it eats, the hands wait.
		if (bot.isUsingItem() && (breakIndex < step.toBreak().size() || step.place() != null && !placed)) {
			return;
		}
		while (breakIndex < step.toBreak().size()) {
			BlockPos pos = step.toBreak().get(breakIndex);
			if (LocalPathfinder.isPassable(level, pos)) {
				hands.abortBreaking();
				breakIndex++;
				hands.resetAttempts();
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
		if (step.place() != null && !step.type().placesWhileMoving && !placed) {
			Placement.Refusal refused = hands.placeBlock(step.place());
			if (refused != null) {
				replan("搭桥放方块失败：" + refused.why);
				return;
			}
			placed = true;
			return;
		}
		move(step);
	}

	private void mine(BlockPos pos, BlockState state) {
		if (hands.mine(pos, state, Hands.AUTO_TOOL) == Hands.MineResult.FAILED && !gathering) {
			replan("挖不动 " + pos.toShortString());
		}
	}

	// ---- movement -----------------------------------------------------------------------

	/**
	 * One tick of a step, in three parts: get up to the target floor (step up, jump, or swim
	 * up), move horizontally, then come down onto the target floor.
	 */
	private void move(PathStep step) {
		// Up through a roof into the water (see DigMove#digOutUnder): the way on is the water that's
		// coming down. Going on before it's here, the step has nowhere to land, and it would set about
		// another bit of the roof.
		if (!stepStarted && step.to().getY() - step.from().getY() == 2 && !step.toBreak().isEmpty() && !body.isInWater()
				&& LocalPathfinder.isWater(bot.level(), step.to().above())) {
			return;
		}
		if (!stepStarted) {
			stepFloor = executor(step).targetFloor(bot.level(), step);
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
		if (!centered && needsCentre(step) && !body.isInWater()) {
			if (body.recentre(step.from())) {
				return;
			}
			centered = true;
		}
		BlockPos to = step.to();
		double tx = to.getX() + 0.5;
		double tz = to.getZ() + 0.5;
		double ty = stepFloor;

		if (step.type() != PathStep.Type.WAIT && body.isInWater() && LocalPathfinder.isWater(bot.level(), to)) {
			swimTowards(tx, ty, tz);
			return;
		}
		executor(step).tick(this, step, ty);
	}

	/** Who carries out steps of this type (see StepExecutor). */
	private static final java.util.Map<PathStep.Type, StepExecutor> EXECUTORS = new java.util.EnumMap<>(java.util.Map.of(
			PathStep.Type.WALK, WalkStep.INSTANCE,
			PathStep.Type.ASCEND, WalkStep.INSTANCE,
			PathStep.Type.DESCEND, WalkStep.INSTANCE,
			PathStep.Type.PILLAR, PillarStep.INSTANCE,
			PathStep.Type.WAIT, BreatheStep.INSTANCE));

	private static StepExecutor executor(PathStep step) {
		return EXECUTORS.get(step.type());
	}

	private static boolean needsCentre(PathStep step) {
		boolean vertical = step.from().getX() == step.to().getX() && step.from().getZ() == step.to().getZ();
		return step.type().startsCentred || vertical;
	}

	/** Water to water: a straight 3D line at swimming speed (rising and sinking a bit slower). */
	private void swimTowards(double tx, double ty, double tz) {
		double dx = tx - bot.getX(), dy = ty - bot.getY(), dz = tz - bot.getZ();
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		// Sprint-swim (vanilla's swimming pose, which the game takes on itself for a sprinting player
		// under water) where the plan did: fully under water at both ends, sprinting allowed.
		BlockPos here = feet();
		BlockPos there = BlockPos.containing(tx, ty + 0.01, tz);
		boolean sprint = canSprintSwim() && Air.submerged(bot.level(), here) && Air.submerged(bot.level(), there);
		bot.setSprinting(sprint);
		if (len > Body.EPS) {
			double speed = sprint ? Air.SPRINT_SWIM_SPEED : LocalPathfinder.SWIM_SPEED;
			double vertical = sprint ? Air.SPRINT_SWIM_SPEED : Air.SWIM_UP_SPEED;
			double s = Math.min(speed, len) / len;
			double mx = dx * s, my = Math.max(-vertical, Math.min(vertical, dy * s)), mz = dz * s;
			if (Math.abs(dx) > Body.EPS || Math.abs(dz) > Body.EPS) {
				body.face(dx, dz);
			}
			// Same safety net as walking: slide along whatever is free, and if nothing moves at all
			// for a while, remember the obstacle and plan around it.
			boolean moved = body.tryMove(mx, my, mz);
			if (!moved) {
				boolean across = Math.abs(mx) > Body.EPS || Math.abs(mz) > Body.EPS;
				moved |= Math.abs(my) > Body.EPS && body.tryMove(0, my, 0);
				moved |= across && (body.tryMove(mx, 0, mz) || (Math.abs(mx) > Body.EPS && body.tryMove(mx, 0, 0))
						|| (Math.abs(mz) > Body.EPS && body.tryMove(0, 0, mz)));
			}
			if (moved) {
				blockedTicks = 0;
				// Vanilla's hunger for swimming: 0.01 a block (sprinting doesn't add to it in water).
				bot.causeFoodExhaustion((float) (0.01 * Math.sqrt(mx * mx + my * my + mz * mz)));
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

	/** See {@link Body#fall}. */
	void fall() {
		body.fall(status == Status.ARRIVED);
	}

	public boolean isInWater() {
		return body.isInWater();
	}

	/** One tick of getting to air (see {@link Body#ascendStep}). */
	public boolean ascendStep() {
		return body.ascendStep(canSprintSwim(), hands);
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
