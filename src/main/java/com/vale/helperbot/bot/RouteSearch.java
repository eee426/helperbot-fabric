package com.vale.helperbot.bot;

import com.vale.helperbot.guard.ProtectionZones;
import com.vale.helperbot.guard.Rules;
import com.vale.helperbot.guard.ZoneExit;
import com.vale.helperbot.path.Air;
import com.vale.helperbot.path.BuildingBlocks;
import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.PathGoal;
import com.vale.helperbot.path.PathJob;
import com.vale.helperbot.path.PathStep;
import com.vale.helperbot.path.Snapshot;
import com.vale.helperbot.path.TravelEstimate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * Which search to run, and what to make of it: the windows for where it can't see, one whole search
 * for what it can (see MovementController#locked), the flood for many targets in the water, the
 * search through a protected area, and planning the next stretch while it walks. The searching itself
 * is LocalPathfinder's, on a background thread; following what comes of it is the movement controller's.
 */
final class RouteSearch {
	private final MovementController c;

	RouteSearch(MovementController c) {
		this.c = c;
	}

	/**
	 * How long the server thread waits for a search it just started, so a short one (most of
	 * them) is taken in the same tick; a longer one is picked up in a later tick.
	 */
	private static final long PLAN_WAIT_NANOS = 1_000_000;
	/** A partial path must get at least this many blocks closer, or a bigger window is tried. */
	private static final double MIN_USEFUL_PROGRESS = 3.0;
	/** The search running in the background, if any (see PathJob). */
	PathJob search;
	int searchLevel;
	/** Smallest search level to start from; raised when the bot stops making progress. */
	int minLevel;
	double escalatedAtDistance;
	/** The next stretch, planned while walking the current one. */
	PathJob lookahead;
	/** The search under way is {@link #startLocked}'s. */
	boolean lockedSearch;
	/** Around the bot and the target: the way may lead a little off the straight line. */
	private static final int LOCK_MARGIN = 32;
	/** Most nodes for the next whole search, and how many the last one took (see MovementController#limitSearch). */
	int lockNodes = MovementController.LOCK_NODES, searchedNodes;
	/** Set while it's yet to know if there's a way to these (see {@link #findWay}). */
	List<BlockPos> wayTargets;
	BlockPos wayEnd;
	/** Looking only up from the bot, or only down from it (see startWay). */
	boolean wayOneSide;
	/** The search that digs has had its say (see endWay). */
	boolean wayDug;
	/** Most cells of a search through everything: it's what a window of a hundred blocks of deep sea comes to. */
	private static final int WAY_NODES = 2_000_000;
	/** Around the targets: where they're reached from, and the way up from there. */
	private static final int WAY_MARGIN = 8;

	/** Search windows in use: a small cube first under water (see LocalPathfinder#WATER_LEVELS). */
	LocalPathfinder.Limits[] levels = LocalPathfinder.LEVELS;
	int plannedAt;
	/** The search under way is one of startZoneSearch's. */
	boolean zoneSearch;
	/** The path being walked came from one: it's what it should be, not something to second-guess. */
	boolean zoneRoute;
	/** Most nodes of a search through a protected area: its window is many times the usual ones. */
	private static final int ZONE_NODES = 400_000;

	/**
	 * In a protected area, where nothing can be dug or built, a wall is a wall: the small windows
	 * and "go as far as you can see, then think again" that do so well outdoors walk it into dead
	 * ends. And little needs thinking about there (it can only walk), so one search can take in all
	 * that's loaded. It finishes what it starts: within what it sees, a dead end is simply never
	 * chosen. If the way out lies past that, the path ends where it's nearest to it, and it looks
	 * again from there. False if it isn't in a protected area.
	 */
	boolean startZoneSearch() {
		BlockPos from = c.feet();
		ZoneExit exit = c.leaving != null ? c.leaving : ZoneExit.at(c.bot.level(), from);
		if (exit == null) {
			return false;
		}
		PathGoal searchGoal = c.goal;
		java.util.function.ToDoubleFunction<BlockPos> ahead = null;
		int reach = Math.max(LocalPathfinder.LEVELS[2].radiusXZ(), (int) Math.sqrt(from.distSqr(c.target)) + 16);
		if (c.leaving != null) {
			double walk = 1.0 / c.mode.speed();
			ahead = p -> exit.blocksOut(p) * walk;
		} else if (!exit.contains(c.target)) {
			// Going through: any way out nearer the target than here will do. One further off is the
			// way it came in, and it would come straight back in.
			PathGoal original = c.goal;
			BlockPos to = c.target;
			double now = flatDistSq(from, to);
			searchGoal = PathGoal.orAlso(original, (w, p, f) -> exit.outside(w, p) && flatDistSq(p, to) < now);
		}
		int radius = Math.min(reach, (c.bot.level().getServer().getPlayerList().getViewDistance() + 1) * 16);
		LocalPathfinder.Limits last = LocalPathfinder.LEVELS[2];
		c.bot.log("在保护区里，一次搜完 ±" + radius + " 格" + (c.leaving != null ? "找出口" : "，目标 " + c.target.toShortString()));
		if (search != null) {
			search.cancel();
		}
		search = startSearch(from, c.bot.getY(), new LocalPathfinder.Limits(radius, last.up(), last.down(), ZONE_NODES), searchGoal, ahead);
		zoneSearch = true;
		plannedAt = c.bot.tickCount;
		return true;
	}

	private static double flatDistSq(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	/**
	 * See {@link #findWay}: the box is whole chunks. Everything is below the bot: from the bottom of
	 * the world up to the bot; everything above: from the bot up over all that's in the box. That's
	 * half of what there is to look through, usually. It can miss a way over a ridge higher than
	 * the bot, so what it doesn't find is looked for again, top to bottom, before it's believed.
	 */
	void startWay() {
		ServerLevel level = c.bot.level();
		BlockPos from = c.feet();
		int minX = from.getX(), maxX = minX, minZ = from.getZ(), maxZ = minZ;
		boolean below = wayOneSide, above = wayOneSide;
		for (BlockPos t : wayTargets) {
			minX = Math.min(minX, t.getX());
			maxX = Math.max(maxX, t.getX());
			minZ = Math.min(minZ, t.getZ());
			maxZ = Math.max(maxZ, t.getZ());
			below &= t.getY() <= from.getY();
			above &= t.getY() >= from.getY();
		}
		minX -= WAY_MARGIN;
		maxX += WAY_MARGIN;
		minZ -= WAY_MARGIN;
		maxZ += WAY_MARGIN;
		int top = from.getY();
		for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
			for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
				var chunk = level.getChunkSource().getChunkNow(cx, cz);
				for (int i = 0; chunk != null && i < 16; i++) {
					for (int j = 0; j < 16; j++) {
						top = Math.max(top, chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, i, j));
					}
				}
			}
		}
		// (With nothing higher than the bot in the box, down from it is all there is: no second search.)
		wayOneSide = (below && top > from.getY()) || above;
		top = below ? from.getY() + WAY_MARGIN : top + WAY_MARGIN;
		int lowest = above ? from.getY() - WAY_MARGIN : level.getMinY();
		int side = 16 + Math.max(Math.max(from.getX() - minX, maxX - from.getX()), Math.max(from.getZ() - minZ, maxZ - from.getZ()));
		LocalPathfinder.Limits limits = new LocalPathfinder.Limits(side, top - from.getY(), from.getY() - lowest, WAY_NODES);
		search = startSearch(Snapshot.box(level, minX, lowest, minZ, maxX, top, maxZ), from, c.bot.getY(), limits, c.goal, null, true, true);
		zoneSearch = false;
		zoneRoute = false;
		plannedAt = c.bot.tickCount;
	}

	/** What the search of {@link #findWay} came to. */
	private void endWay() {
		PathJob job = search;
		search = null;
		c.resetStep();
		if (!job.result().reachesGoal() && wayOneSide) {
			c.bot.log("只往一个方向搜没找到（" + job.nodes() + " 格），上下全部再搜一次");
			wayOneSide = false;
			return; // tickMoving starts it (see startWay)
		}
		if (!job.result().reachesGoal() && !wayDug && !c.headUnderwater()) {
			// The flood digs nothing: a wall of rock is the end of it, and from a dry mine that's most
			// ways in. Once, for the nearest: going through them all takes minutes, for what's mostly no.
			wayDug = true;
			c.bot.log("不挖到不了（" + job.nodes() + " 格），查一次最近的能不能挖过去");
			startLocked();
			return;
		}
		if (lockedSearch) {
			searchedNodes = job.nodes();
		}
		lockedSearch = false;
		int targets = wayTargets.size();
		wayTargets = null;
		LocalPathfinder.Result result = job.result();
		c.bot.log("一次搜完 " + targets + " 个目标：" + job.nodes() + " 格，用了 " + (c.bot.tickCount - plannedAt) / 20.0 + " 秒，"
				+ (result.reachesGoal() ? result.steps().size() + " 步能到" : job.hitNodeLimit() ? "到上限也没搜到" : "都到不了"));
		if (!result.reachesGoal()) {
			c.bot.log(job.diagnose());
			c.provenFrom = c.feet();
			c.fail("一次搜完了，都到不了");
			return;
		}
		wayEnd = result.steps().isEmpty() ? c.feet() : result.steps().get(result.steps().size() - 1).to();
		if (result.steps().isEmpty()) {
			c.status = MovementController.Status.ARRIVED;
			c.path = null;
			return;
		}
		c.adopt(result);
		c.emptyPlans = 0;
	}

	/** The box around the bot and the target, one search, to the end: see {@link #locked}. */
	void startLocked() {
		zoneSearch = false;
		zoneRoute = false;
		BlockPos from = c.feet();
		int across = Math.max(Math.abs(c.target.getX() - from.getX()), Math.abs(c.target.getZ() - from.getZ()));
		LocalPathfinder.Limits limits = new LocalPathfinder.Limits(across + LOCK_MARGIN, Math.max(0, c.target.getY() - from.getY()) + LOCK_MARGIN,
				Math.max(0, from.getY() - c.target.getY()) + LOCK_MARGIN, lockNodes);
		long started = System.nanoTime();
		Snapshot world = Snapshot.around(c.bot.level(), from, limits, c.airProfile().limited());
		TravelEstimate ahead = new TravelEstimate(world, c.bot.getInventory(), c.mode, c.target, false);
		c.bot.perf().add(Perf.Part.PATHFIND, System.nanoTime() - started);
		search = startSearch(world, from, c.bot.getY(), limits, c.goal, ahead::ticks, true, false);
		lockedSearch = true;
		search.await(PLAN_WAIT_NANOS);
		plannedAt = c.bot.tickCount;
	}

	/** What {@link #startLocked} came to. No is only believed with the head out: see {@link #provenFrom}. */
	/** The whole search that sent it waiting for blocks had gone through everything (see MovementController#noBlocksAround). */
	boolean exhaustedForBlocks;

	private void endLocked() {
		PathJob job = search;
		search = null;
		lockedSearch = false;
		c.resetStep();
		searchedNodes = job.nodes();
		LocalPathfinder.Result result = job.result();
		if (result.reachesGoal()) {
			if (result.steps().isEmpty()) {
				c.status = MovementController.Status.ARRIVED;
				c.path = null;
				c.work = result.work();
				return;
			}
			c.bot.log("规划出 " + result.steps().size() + " 步（全量，能到终点）");
			c.adopt(result);
			c.emptyPlans = 0;
			return;
		}
		c.bot.log("全量搜了 " + job.nodes() + " 格，用了 " + (c.bot.tickCount - plannedAt) / 20.0 + " 秒，" + (job.hitNodeLimit() ? "到上限了" : "搜完了")
				+ "，到不了 " + c.target.toShortString());
		c.path = null;
		if (c.headUnderwater() && Air.budget(c.bot) < Air.UNLIMITED) {
			c.airRequested = true; // with its breath running, that says little: again from up there
			return;
		}
		if (startZoneSearch()) {
			return;
		}
		if (c.emptyPlans == 0 && c.askForBlocks()) {
			// Maybe a bridge or pillar would work: get some blocks first (see MovementController#wantsBlocks).
			exhaustedForBlocks = !job.hitNodeLimit();
			c.emptyPlans++;
			return;
		}
		c.provenFrom = c.feet();
		c.fail("全量搜索到不了 " + c.target.toShortString());
	}

	void startPlanning(int levelIndex) {
		if (c.locked) {
			startLocked();
			return;
		}
		zoneSearch = false;
		zoneRoute = false;
		if (levelIndex == minLevel) {
			levels = LocalPathfinder.levels(c.headUnderwater());
		}
		searchLevel = levelIndex;
		if (search != null) {
			search.cancel();
		}
		search = startSearch(c.feet(), c.bot.getY(), levels[levelIndex]);
		long started = System.nanoTime();
		search.await(PLAN_WAIT_NANOS);
		c.bot.perf().add(Perf.Part.PATHFIND, System.nanoTime() - started);
		plannedAt = c.bot.tickCount;
	}

	/**
	 * Starts a search in the background, on a copy of the blocks around it and of everything else
	 * it reads (tools, cells to avoid, the last route), so it never touches what the server thread
	 * is changing meanwhile. The copying is timed as pathfinding on the server thread.
	 */
	private PathJob startSearch(BlockPos start, double startFloor, LocalPathfinder.Limits limits) {
		return startSearch(start, startFloor, limits, c.goal, null, false);
	}

	/** A search through a protected area (see startZoneSearch). */
	private PathJob startSearch(BlockPos start, double startFloor, LocalPathfinder.Limits limits, PathGoal goal,
								java.util.function.ToDoubleFunction<BlockPos> ahead) {
		return startSearch(start, startFloor, limits, goal, ahead, true);
	}

	private PathJob startSearch(BlockPos start, double startFloor, LocalPathfinder.Limits limits, PathGoal goal,
								java.util.function.ToDoubleFunction<BlockPos> ahead, boolean whole) {
		return startSearch(Snapshot.around(c.bot.level(), start, limits, c.airProfile().limited()), start, startFloor, limits, goal, ahead, whole, false);
	}

	private PathJob startSearch(Snapshot world, BlockPos start, double startFloor, LocalPathfinder.Limits limits, PathGoal goal,
								java.util.function.ToDoubleFunction<BlockPos> ahead, boolean whole, boolean reachOnly) {
		long started = System.nanoTime();
		Air.Profile air = c.airProfile();
		if (breathAtStart >= 0 && air.limited()) {
			air = new Air.Profile(breathAtStart, air.full(), true, air.workTicks(), air.refill(), air.sprintBlocks());
		}
		Inventory tools = new Inventory(c.bot, new net.minecraft.world.entity.EntityEquipment());
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			tools.setItem(slot, c.bot.getInventory().getItem(slot).copy());
		}
		com.vale.helperbot.path.Avoided avoided = avoidedNow();
		it.unimi.dsi.fastutil.longs.LongOpenHashSet favored = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(c.lastPath);
		PathJob job = new PathJob(new LocalPathfinder(world, tools, c.mode, c.mode.maxDropFor(c.bot.getHealth()), start, startFloor,
				goal, c.target, c.horizontalOnly, limits, avoided, air, favored::contains, whole, ahead, reachOnly));
		c.bot.perf().add(Perf.Part.PATHFIND, System.nanoTime() - started);
		return job;
	}

	/** A search for further along: the breath the plan has for there, not what it has now. -1: now. */
	private int breathAtStart = -1;

	/**
	 * Cells it's steering around right now, and which of them a step was given up on (see
	 * MovementController#avoidUntil); expired ones are dropped. A copy: the search reads it on its own thread.
	 */
	private com.vale.helperbot.path.Avoided avoidedNow() {
		return new com.vale.helperbot.path.Avoided(current(c.avoidUntil), current(c.givenUpUntil));
	}

	private it.unimi.dsi.fastutil.longs.LongOpenHashSet current(it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap until) {
		it.unimi.dsi.fastutil.longs.LongOpenHashSet cells = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		for (var it = until.long2IntEntrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			if (e.getIntValue() < c.bot.tickCount) {
				it.remove(); // its time is up
			} else {
				cells.add(e.getLongKey());
			}
		}
		return cells;
	}

	/**
	 * Diagnosis, when it's about to give up for lack of a path: the same search once more, on the
	 * live world on this thread, for the log. If this one finds a way, the copy the background
	 * search worked on was missing something. Capped, and only at giving up, so it costs nothing
	 * otherwise.
	 */
	private void crossCheck() {
		LocalPathfinder.Limits limits = levels[minLevel];
		LocalPathfinder live = new LocalPathfinder(c.bot.level(), c.bot.getInventory(), c.mode, c.mode.maxDropFor(c.bot.getHealth()),
				c.feet(), c.bot.getY(), c.goal, c.target, c.horizontalOnly, limits, avoidedNow(), c.airProfile(),
				new it.unimi.dsi.fastutil.longs.LongOpenHashSet(c.lastPath)::contains);
		long started = System.nanoTime();
		boolean done = false;
		while (!done && System.nanoTime() - started < 200_000_000L) {
			done = live.step(20_000_000L);
		}
		c.bot.perf().add(Perf.Part.PATHFIND, System.nanoTime() - started);
		String what = !done ? "200 毫秒内没搜完"
				: !live.result().steps().isEmpty() ? "能找到 " + live.result().steps().size() + " 步（后台用的那份世界有问题！）"
				: "也找不到：" + live.diagnose();
		c.bot.log("对照：在真实世界上同样搜一次（±" + limits.radiusXZ() + " 格）：" + what);
	}

	/** Has the search finished? Counts its background time once, when it has. */
	private boolean finished(PathJob job) {
		if (!job.done()) {
			return false;
		}
		c.bot.perf().add(Perf.Part.BACKGROUND, job.takeNanos());
		return true;
	}

	void cancelSearches() {
		lockedSearch = false;
		if (search != null) {
			search.cancel();
			search = null;
		}
		cancelLookahead();
	}

	void cancelLookahead() {
		if (lookahead != null) {
			lookahead.cancel();
			lookahead = null;
		}
	}


	/**
	 * Runs the current search for this tick's budget. If it finds nothing useful (no path, or a
	 * partial path that barely gets closer), the next bigger window is tried.
	 */
	void continuePlanning() {
		c.bot.perf().testWait(search.paceForTests());
		if (!finished(search)) {
			return;
		}
		if (wayTargets != null && !c.engaged) {
			endWay();
			return;
		}
		if (lockedSearch) {
			endLocked();
			return;
		}
		LocalPathfinder.Result result = search.result();
		boolean wasZone = zoneSearch;
		zoneSearch = false;
		if (c.tracking && !wasZone && !result.reachesGoal() && c.target != null && c.inSight(c.target)) {
			// Keeping up with something: the small window gets there, or the whole search says (see
			// MovementController#tracking). Never a bit of the way, never the wider windows.
			c.locked = true;
			c.resetStep();
			if (c.headUnderwater() && Air.budget(c.bot) < Air.UNLIMITED) {
				// Under water the whole search's no isn't believed, and it thinks standing still on what
				// breath is left (ten seconds, once). The whole search said yes before the dive; the small
				// window, a cube 33 across, says no now: whatever changed (it swam off, the way's shut,
				// breath ran short), the whole search up top says, on a full breath, where a no counts.
				c.bot.log("±" + search.limits().radiusXZ() + " 格内到不了 " + c.target.toShortString() + "，先上去换气，回来用全量搜索再看");
				search = null;
				c.path = null;
				c.airRequested = true;
				return;
			}
			c.bot.log("±" + search.limits().radiusXZ() + " 格内到不了 " + c.target.toShortString() + "，改用全量搜索");
			startLocked();
			return;
		}
		if (!wasZone && !result.reachesGoal() && !c.headUnderwater() && startZoneSearch()) {
			return;
		}
		// Not when it's known to lead nowhere: under water that would use up the breath getting
		// nearer, come up, and start over from the same answer.
		boolean proven = !wasZone && search.provenUnreachable();
		String ended = (search.hitNodeLimit() ? "，节点到上限，没搜完" : proven ? "，搜完了" : "")
				+ (search.blocksShort() > 0 ? "，方块不够" : "");
		boolean useful = result.reachesGoal() || (wasZone && !result.steps().isEmpty())
				|| (!proven && !result.steps().isEmpty() && search.progressBlocks() >= MIN_USEFUL_PROGRESS);
		if (!useful && !wasZone && searchLevel < levels.length - 1) {
			// The last line before a long think: if it never comes back, this says what it was doing.
			c.bot.log("±" + search.limits().radiusXZ() + " 格内没有可用的路（用了 " + (c.bot.tickCount - plannedAt) / 20.0 + " 秒" + ended + "），扩大到 ±"
					+ levels[searchLevel + 1].radiusXZ() + " 格，目标 " + (c.target == null ? "（无）" : c.target.toShortString()));
			startPlanning(searchLevel + 1);
			return;
		}
		int radius = search.limits().radiusXZ();
		PathJob finished = search;
		search = null;
		c.resetStep();
		if (result.reachesGoal() && result.steps().isEmpty()) {
			c.status = MovementController.Status.ARRIVED; // it's already where the plan would have it
			c.path = null;
			c.work = result.work();
			return;
		}
		if (result.steps().isEmpty()) {
			c.bot.log("在 ±" + radius + " 格内找不到路：" + finished.diagnose());
			c.noPathForAir = finished.airRejected() > 0;
			if (c.emptyPlans + 1 >= MovementController.MAX_EMPTY_PLANS) {
				crossCheck(); // about to give up (see onNoPath)
			}
			c.onNoPath();
			return;
		}
		if (proven) {
			// Steps it could take, none of them to the goal, in the widest search there is.
			c.provenFrom = c.feet();
			c.fail("±" + radius + " 格内搜完了，确定到不了 " + c.target.toShortString());
			return;
		}
		c.bot.log("规划出 " + result.steps().size() + " 步（±" + radius + " 格，"
				+ (result.reachesGoal() ? "能到终点" : "先走一段") + ended + "）");
		c.adopt(result);
		zoneRoute = wasZone;
		c.emptyPlans = 0;
		if (!wasZone && result.reachesGoal() && searchLevel > minLevel) {
			// The way there takes a window this wide: plan again from it, not from the small one, which
			// can't see the goal and heads off the way the target lies. Under water, where it plans
			// every second, the two took turns: a step along the route, a step back.
			minLevel = searchLevel;
			escalatedAtDistance = c.remaining(c.bot.blockPosition());
		}
	}

	/**
	 * Think while walking: when the current (partial) path is nearly done, start planning the next
	 * stretch from where it ends, a little each tick, so it's ready on arrival and the bot doesn't
	 * stop to think. Only once the remaining steps won't change the world (no digging or placing
	 * left), since the search sees the world as it is now.
	 */
	void advanceLookahead() {
		if (lookahead != null) {
			c.bot.perf().testWait(lookahead.paceForTests());
			finished(lookahead); // only to count its time; it's taken at the end of the c.path
			return;
		}
		if (c.path == null || c.pathReachesGoal || c.replanRequested || zoneRoute || c.path.size() - c.index > MovementController.LOOKAHEAD_STEPS) {
			return;
		}
		for (int i = c.index; i < c.path.size(); i++) {
			PathStep step = c.path.get(i);
			if (!step.toBreak().isEmpty() || step.place() != null) {
				return;
			}
		}
		BlockPos end = c.path.get(c.path.size() - 1).to();
		double endFloor = LocalPathfinder.floorHeight(c.bot.level(), end);
		if (Double.isNaN(endFloor)) {
			return;
		}
		breathAtStart = c.pathAir.isEmpty() ? -1 : c.pathAir.get(c.pathAir.size() - 1);
		lookahead = startSearch(end, endFloor, levels[minLevel]);
		breathAtStart = -1;
	}

	/**
	 * At the end of the current path: continue straight onto the next stretch if the lookahead
	 * found a good one. Returns false if a normal (standing) re-plan is needed instead.
	 */
	boolean switchToLookahead() {
		PathJob next = lookahead;
		lookahead = null;
		if (next == null || c.replanRequested || c.path == null || !c.feet().equals(c.path.get(c.path.size() - 1).to())) {
			if (next != null) {
				next.cancel();
			}
			return false;
		}
		long started = System.nanoTime();
		next.await(PLAN_WAIT_NANOS);
		c.bot.perf().add(Perf.Part.PATHFIND, System.nanoTime() - started);
		if (!finished(next)) {
			lookahead = next; // almost always done by now; if not, finish it next tick
			return true;
		}
		LocalPathfinder.Result result = next.result();
		boolean useful = result.reachesGoal()
				|| (!c.tracking && !result.steps().isEmpty() && next.progressBlocks() >= MIN_USEFUL_PROGRESS);
		if (!useful || next.provenUnreachable() || (!result.reachesGoal() && ProtectionZones.isProtected(c.bot.level(), c.feet()))) {
			return false; // let the normal planner try (and widen the search if needed)
		}
		c.adopt(result);
		c.emptyPlans = 0;
		c.resetStep();
		return true;
	}
}
