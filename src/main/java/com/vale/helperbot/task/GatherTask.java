package com.vale.helperbot.task;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.bot.BotTask;
import com.vale.helperbot.bot.Hands;
import com.vale.helperbot.bot.MovementController;
import com.vale.helperbot.guard.Guard;
import com.vale.helperbot.guard.ProtectionZones;
import com.vale.helperbot.knowledge.BiomeHints;
import com.vale.helperbot.knowledge.BlockScanner;
import net.minecraft.core.component.DataComponents;
import com.vale.helperbot.path.Air;
import com.vale.helperbot.path.Escape;
import com.vale.helperbot.knowledge.DropAnalysis;
import com.vale.helperbot.knowledge.Obtainability;
import com.vale.helperbot.path.MineSpot;
import com.vale.helperbot.path.Mode;
import com.vale.helperbot.path.PathGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collect N of any item that comes from breaking a block: works out which blocks drop it and
 * which of the player's tools work (from the real loot tables), finds them nearby or in the
 * right biome far away, mines them like a player, picks up the drops and brings them back.
 */
public class GatherTask implements BotTask {
	private static final int SCAN_CHUNKS = 6;
	private static final int TRAVEL_SCAN_CHUNKS = 3;
	private static final int TRAVEL_SCAN_INTERVAL = 100;
	/** Looked through when already standing in the right biome (about the usual view distance). */
	private static final int MAX_TRAVEL_FAILURES = 4;

	private enum Phase {
		PREPARE, FIND, MAP, LOCATE, TRAVEL, WAY, APPROACH, MINE, FETCH, RETURN
	}

	private final ServerPlayer requester;
	private final Mode mode;
	private final Item item;
	private final int wanted;
	private final ComeTask returnLeg;
	private Phase phase = Phase.PREPARE;
	/** Items on the ground are sources too (see DropFetch): the one it's fetching, in FETCH. */
	private final DropFetch drops = new DropFetch();
	/** Items lying within this are sources: the same range it looks for blocks in. */
	private static final double DROP_RANGE = SCAN_CHUNKS * 16;
	private int startCount;
	/** Hand checks failed in a row for the current target (see {@link #mine}). */
	private int handFixes;
	/** The last block mined, to see afterwards whether it dropped what it should have. */
	private BlockPos lastBreak;
	private Block lastBreakBlock;
	private int lastBreakTick;
	/**
	 * Breaks in a row that should have dropped the item (checked beforehand) and didn't. That
	 * means a bug somewhere or another mod changing drops: reported in detail, not guessed at.
	 */
	private int anomalies;
	private static final int MAX_ANOMALIES = 6;
	/** Ticks after a break to look at what it dropped (the drop tracker's window, plus a little). */
	private static final int DROP_CHECK_TICKS = 7;
	/** Targets it can't get to (no spot within reach, or not on one breath): skipped for this task. */
	/**
	 * What's out of reach, for the whole order: a new gathering step starting over would only find
	 * it out again. With the breath it had then (full, health it may spend included): under water
	 * a bigger breath (health back) may bring them in reach again. It used to be for good: after
	 * spending some health under water, every deep block was out of reach for the rest of the task.
	 */
	private final OutOfReach unreachable;
	/** See the constructor: null for an ordinary gathering. */
	private final Set<Block> onlyFrom;
	/** For this item, the whole order long (see Explorer). */
	private final Map<Item, Explorer> explorers;

	private boolean outOfReach(BotPlayer bot, BlockPos pos) {
		return unreachable.covers(pos, Air.fullBudget(bot), bot.blockPosition());
	}
	/** Search nodes left for this place: spent on what turns out out of reach, whole again once it gets somewhere. */
	private int budget = MovementController.LOCK_NODES;
	/** How many of the nearest targets are compared each time it picks the next one. */
	private static final int TARGETS_COMPARED = 12;

	/** Blocks that drop the item with a tool we have, and the inventory slot (or HAND) to use. */
	private final Map<Block, Integer> toolFor = new HashMap<>();
	/** The item that was in each tool slot when it was chosen, to notice the player moving it. */
	private final Map<Block, Item> toolItems = new HashMap<>();
	private List<BlockPos> targets = new ArrayList<>();
	private BlockPos current;
	private Block currentBlock;

	private Set<Holder<Biome>> biomes;
	private boolean triedBiome;
	private FarBiome locating;
	private BlockPos travelTarget;
	private int travelTicks;
	private int travelFailures;
	/** For /bot status: how long the current phase has been running. */
	private Phase lastPhase;
	private int phaseTicks;
	private int blocksMined;

	/**
	 * Part of a bigger plan (see {@link AcquireTask}): finish where the items are instead of
	 * walking back to the player, and leave the "come and get it" messages to the plan.
	 */
	private final boolean asStep;

	public GatherTask(ServerPlayer requester, Item item, int wanted, Mode mode) {
		this(requester, item, wanted, mode, false, new OutOfReach(), new HashMap<>());
	}

	GatherTask(ServerPlayer requester, Item item, int wanted, Mode mode, boolean asStep, OutOfReach unreachable, Map<Item, Explorer> explorers) {
		this(requester, item, wanted, mode, asStep, unreachable, explorers, new java.util.ArrayList<>(), null);
	}

	GatherTask(ServerPlayer requester, Item item, int wanted, Mode mode, boolean asStep, OutOfReach unreachable, Map<Item, Explorer> explorers,
			   Set<Block> onlyFrom) {
		this(requester, item, wanted, mode, asStep, unreachable, explorers, new java.util.ArrayList<>(), onlyFrom);
	}

	/**
	 * @param onlyFrom null, or the blocks it may get the item from (of those that drop it): the ground,
	 *                 for building blocks (see RestockStep), not a player's farmland for dirt. With
	 *                 it, it gathers near here only: when what's around runs out, it's done with what
	 *                 it has, never off to explore.
	 */
	/** @param structuresDone see {@link #structuresDone}: the order's, for this item */
	GatherTask(ServerPlayer requester, Item item, int wanted, Mode mode, boolean asStep, OutOfReach unreachable, Map<Item, Explorer> explorers,
			   List<BlockPos> structuresDone, Set<Block> onlyFrom) {
		this.structuresDone = structuresDone;
		this.onlyFrom = onlyFrom;
		this.unreachable = unreachable;
		this.explorers = explorers;
		this.requester = requester;
		this.item = item;
		this.wanted = wanted;
		this.mode = mode;
		this.asStep = asStep;
		this.returnLeg = new ComeTask(requester, mode);
	}

	@Override
	public boolean wantsPickup(ItemStack stack) {
		return stack.is(item);
	}

	private MutableComponent itemName() {
		return new ItemStack(item).getHoverName().copy();
	}

	@Override
	public Status tick(BotPlayer bot) {
		if (phase != lastPhase) {
			lastPhase = phase;
			phaseTicks = 0;
		} else {
			phaseTicks++;
		}
		if (lastBreak != null && bot.tickCount - lastBreakTick >= DROP_CHECK_TICKS) {
			Status s = checkDrop(bot);
			if (s != null) {
				return s;
			}
		}
		if (phase != Phase.PREPARE && phase != Phase.RETURN) {
			// (Not for a restock: what lies around then is mostly what rolled off the edge it dug at,
			// down the chasm or into the pit by the lava, and it went down after the last one or two.)
			if (onlyFrom == null && collected(bot) < wanted && (phase == Phase.APPROACH || phase == Phase.MINE) && bot.tickCount % 10 == 0
					&& collected(bot) + drops.lying(bot, item, DROP_RANGE) >= wanted && !bot.movement().isMining()) {
				// Enough lies around: no more mining, just picking up.
				bot.log("地上的已经够了，去捡（还差 " + (wanted - collected(bot)) + " 个）");
				bot.movement().stop();
				return nextTarget(bot);
			}
			if (collected(bot) >= wanted) {
				if (asStep) {
					bot.movement().stop();
					return Status.DONE;
				}
				bot.say(requester, Component.literal("采够了 " + collected(bot) + " 个").append(itemName()).append("，正在回来。"));
				phase = Phase.RETURN;
			} else if (inventoryFull(bot) && (!bot.tidyInventory() || inventoryFull(bot))) {
				return giveUp(bot, "背包满了（扔掉自己捡的杂物后还是满的，剩下的都是你给的东西或任务要的东西）");
			}
		}
		return switch (phase) {
			case PREPARE -> prepare(bot);
			case FIND -> find(bot);
			case MAP -> lookFurther(bot);
			case LOCATE -> locate(bot);
			case TRAVEL -> travel(bot);
			case WAY -> way(bot);
			case APPROACH -> approach(bot);
			case MINE -> mine(bot);
			case FETCH -> fetch(bot);
			case RETURN -> ComeTask.followTick(bot, requester, mode, returnLeg);
		};
	}

	// ---- planning -----------------------------------------------------------------------

	private Status prepare(BotPlayer bot) {
		ServerLevel level = bot.level();
		startCount = count(bot.getInventory());
		String refusal = Obtainability.refusal(level, item);
		if (refusal != null) {
			bot.say(requester, refusal);
			return Status.FAILED;
		}
		Map<Block, List<String>> candidates = DropAnalysis.candidates(level, bot, item);
		if (onlyFrom != null) {
			candidates = new HashMap<>(candidates);
			candidates.keySet().retainAll(onlyFrom);
		}
		if (candidates.isEmpty()) {
			bot.say(requester, Component.literal("没有哪种方块挖了会掉").append(itemName())
					.append("。它可能要靠合成、熔炼、打怪、交易或钓鱼获得（这些还不支持），也可能生存模式里本来就拿不到。"));
			return Status.FAILED;
		}
		if (!refreshTools(bot, candidates.keySet())) {
			bot.say(requester, Component.literal("要采").append(itemName()).append("，需要："
					+ DropAnalysis.describeOptions(candidates) + "。请右键我，放进我的背包。"));
			return Status.FAILED;
		}
		MutableComponent sources = Component.empty();
		int shown = 0;
		for (Block block : toolFor.keySet()) {
			if (shown++ == 3) {
				sources.append(" 等");
				break;
			}
			if (shown > 1) {
				sources.append("、");
			}
			sources.append(block.getName());
		}
		bot.say(requester, Component.literal("目标：").append(itemName()).append(" ×" + wanted + "。来源：")
				.append(sources).append("。开始找。"));
		phase = Phase.FIND;
		return Status.RUNNING;
	}

	/** Recomputes which of our tools work for which blocks (tools wear out). False if none do. */
	private boolean refreshTools(BotPlayer bot, Set<Block> blocks) {
		toolFor.clear();
		toolItems.clear();
		for (Block block : blocks) {
			int slot = DropAnalysis.toolSlotFor(bot.level(), bot, bot.getInventory(), block, item);
			if (slot != DropAnalysis.NONE) {
				toolFor.put(block, slot == DropAnalysis.HAND ? Hands.HAND : slot);
				if (slot >= 0) {
					toolItems.put(block, bot.getInventory().getItem(slot).getItem());
				}
			}
		}
		return !toolFor.isEmpty();
	}

	// ---- finding ------------------------------------------------------------------------

	private Status find(BotPlayer bot) {
		if (onlyFrom != null && scanned) {
			// Near here only: one look round. What it didn't get from that is for the restock to
			// start over on (see RestockStep), not for scanning on and on through all the ground in
			// sight: boxed in, it went through every block of dirt under the floor, one by one.
			return giveUp(bot, "附近能挖的都试过了");
		}
		scanned = true;
		targets = scan(bot, SCAN_CHUNKS);
		bot.log("附近扫描到 " + targets.size() + " 处" + itemName().getString() + "（不算记下到不了的 " + unreachable.size() + " 处）");
		if (!targets.isEmpty()) {
			return nextTarget(bot);
		}
		return lookFurther(bot);
	}

	private List<BlockPos> scan(BotPlayer bot, int chunkRadius) {
		ServerLevel level = bot.level();
		return BlockScanner.find(level, bot.blockPosition(), chunkRadius,
				state -> toolFor.containsKey(state.getBlock()) && DropAnalysis.dropsFrom(item, state),
				pos -> !outOfReach(bot, pos) && Guard.canHarvest(level, pos, level.getBlockState(pos))
						&& (onlyFrom == null || !bot.movement().hands().laid(pos)), // not its own bridge
				64);
	}

	/** It has looked round once (see #find). */
	private boolean scanned;

	private Status nextTarget(BotPlayer bot) {
		if (!refreshTools(bot, new HashSet<>(toolFor.keySet()))) {
			return toolsGone(bot);
		}
		ServerLevel level = bot.level();
		BlockPos here = bot.blockPosition();
		drops.stop();
		behind = null;
		// Items on the ground: sources like the blocks, on the same scale (see DropFetch). If they
		// make up what's still missing, no more mining.
		int needed = wanted - collected(bot);
		DropFetch.Best lyingBest = drops.best(bot, item, DROP_RANGE, needed);
		net.minecraft.world.entity.item.ItemEntity bestItem = lyingBest.item();
		double bestItemCost = lyingBest.cost();
		Escape escape = bot.movement().newEscape();
		if (bestItem != null && lyingBest.lying() >= needed && onlyFrom == null) { // (see tick, for a restock)
			return fetch(bot, bestItem);
		}
		// Also drops ones that changed since the scan (berries picked, crop trampled).
		targets.removeIf(p -> outOfReach(bot, p) || !toolFor.containsKey(level.getBlockState(p).getBlock())
				|| !DropAnalysis.dropsFrom(item, level.getBlockState(p)));
		if (targets.isEmpty()) {
			if (bestItem != null) {
				return fetch(bot, bestItem);
			}
			phase = Phase.FIND;
			return Status.RUNNING;
		}
		targets.sort(Comparator.comparingDouble(p -> p.distSqr(here)));
		// Of the nearest few, the one that's cheapest to get: under water that includes the air it
		// costs. Ones it can't reach on a breath, or can't reach at all, are skipped; e.g. the bottom
		// of a kelp stalk deeper than it can dive, when the top of the same stalk is right there.
		BlockPos best = null;
		double bestCost = Double.MAX_VALUE;
		// The nearest few; and if some of those are in water, also the nearest few on dry land, even
		// if further away (otherwise a near patch to float over always wins against plenty on land).
		int checked = 0;
		int dryChecked = 0;
		boolean sawWater = false;
		int skipped = 0;
		for (java.util.Iterator<BlockPos> it = targets.iterator(); it.hasNext()
				&& (checked < TARGETS_COMPARED || (sawWater && dryChecked < TARGETS_COMPARED)); ) {
			BlockPos p = it.next();
			boolean wet = MineSpot.inWater(level, p);
			if (checked >= TARGETS_COMPARED && wet) {
				continue; // past the nearest few: only looking for dry ones now
			}
			checked++;
			sawWater |= wet;
			if (!wet) {
				dryChecked++;
			}
			double cost = MineCost.cost(bot, p, item, toolFor.getOrDefault(level.getBlockState(p).getBlock(), Hands.HAND), wanted - collected(bot), escape);
			if (Double.isNaN(cost)) {
				it.remove();
				unreachable.judged(p, Air.fullBudget(bot));
				skipped++;
				continue;
			}
			if (cost < bestCost) {
				bestCost = cost;
				best = p;
			}
		}
		if (skipped > 0) {
			bot.log(skipped + " 处判断为不去（附近没有能站着伸手够到的位置，或者挖的时候头在水下，或者一口气不够），跳过");
		}
		if (best == null) {
			return nextTarget(bot); // all of those were out of reach: look at the next few
		}
		if (bestItem != null && bestItemCost <= bestCost) {
			return fetch(bot, bestItem);
		}
		if (Guard.ofTheSea(level.getBlockState(best)) && !bot.movement().headUnderwater()) {
			return findWay(bot);
		}
		targets.remove(best);
		current = best;
		currentBlock = level.getBlockState(current).getBlock();
		goNear(bot, current);
		phase = Phase.APPROACH;
		return Status.RUNNING;
	}

	/** What it's looking for a way to, each with where it's got at from (see findWay). */
	private final Map<BlockPos, PathGoal> wayTo = new HashMap<>();

	/**
	 * What the sea has is in the water or nowhere, so whether it can be got at decides: before going
	 * in, one search for all of it in sight (see MovementController#findWay).
	 */
	private Status findWay(BotPlayer bot) {
		ServerLevel level = bot.level();
		wayTo.clear();
		for (BlockPos p : targets) {
			if (Guard.ofTheSea(level.getBlockState(p))) {
				wayTo.put(p, MineSpot.reachGoal(p, MineSpot.reach(bot), bot.getEyeHeight(), MineSpot.exposed(level, p), false));
			}
		}
		bot.movement().findWay(List.copyOf(wayTo.keySet()), PathGoal.anyOf(wayTo.values()), mode);
		bot.movement().limitSearch(budget);
		phase = Phase.WAY;
		return Status.RUNNING;
	}

	private Status way(BotPlayer bot) {
		if (bot.movement().findingWay()) {
			return Status.RUNNING;
		}
		ServerLevel level = bot.level();
		BlockPos end = bot.movement().wayEnd();
		BlockPos reached = null;
		for (Map.Entry<BlockPos, PathGoal> e : wayTo.entrySet()) {
			if (end != null && (reached == null || e.getKey().distSqr(end) < reached.distSqr(end)) && e.getValue().test(level, end)) {
				reached = e.getKey();
			}
		}
		if (reached == null) {
			bot.log("这 " + wayTo.size() + " 处" + itemName().getString() + "都到不了，记下，去别处找");
			BlockPos from = bot.movement().feet();
			wayTo.keySet().forEach(p -> unreachable.proven(p, Air.fullBudget(bot), from));
			targets.removeAll(wayTo.keySet());
			bot.movement().stop();
			return spent(bot);
		}
		targets.remove(reached);
		current = reached;
		currentBlock = level.getBlockState(current).getBlock();
		bot.movement().settle(wayTo.get(reached), reached);
		phase = Phase.APPROACH;
		return Status.RUNNING;
	}

	/** Walk to any spot from which the block is within arm's reach. */
	private void goNear(BotPlayer bot, BlockPos block) {
		double reach = MineSpot.reach(bot);
		// Not from inside a protected chunk: it can't do anything there (see ProtectionZones).
		ServerLevel level = bot.level();
		double eyeHeight = bot.getEyeHeight();
		// With a face open to air or water it must also be in sight, like a player has to see what
		// they mine: within reach isn't enough from the bottom of a hole, with the floor in between
		// (it stopped there, set about mining the floor as "in the way", and gave the block up).
		// Buried ones (ore in rock) have no such spot: getting close and digging through is the way.
		BlockState state = level.getBlockState(block);
		bot.movement().goTo(MineSpot.reachGoal(block, reach, eyeHeight, MineSpot.exposed(level, block), !Guard.ofTheSea(state)), block, mode);
		bot.movement().limitSearch(budget);
	}

	/** None around: on to the next place that may have it (see Explorer). */
	private Status lookFurther(BotPlayer bot) {
		if (onlyFrom != null) {
			return giveUp(bot, "附近的挖完了"); // near here only (see the constructor)
		}
		ServerLevel level = bot.level();
		if (biomes == null) {
			biomes = BiomeHints.biomesFor(level, toolFor.keySet());
		}
		if (biomes.isEmpty()) {
			// No biome generates it: the structures built with it, nearest first.
			var built = com.vale.helperbot.knowledge.StructureIndex.holding(level, toolFor.keySet());
			if (structureTrip != null) {
				// Back to looking further after going there: nothing (more) to be had from it.
				doneWith(bot, "那里没有（更多）能采的了");
			}
			BlockPos at = structuresDone.size() >= MAX_STRUCTURES ? null
					: com.vale.helperbot.knowledge.StructureIndex.nearest(level, built, bot.blockPosition(), structuresDone);
			if (at == null) {
				return giveUp(bot, built.isEmpty() ? "附近已加载的区域里没有，也不知道该去哪找" : "去过的结构里都没找到");
			}
			structureTrip = at;
			bot.say(requester, Component.literal("它在结构里才有，去 " + at.getX() + ", " + at.getZ() + " 找。"));
			startTravel(bot, at);
			return Status.RUNNING;
		}
		Explorer explorer = explorers.computeIfAbsent(item, i -> {
			// How much a chunk of each has, on average, by how the game makes it (see BiomeHints).
			Map<Holder<Biome>, Double> worth = new HashMap<>(BiomeHints.perChunk(level, toolFor.keySet()));
			return new Explorer(level, bot.blockPosition(), worth, BiomeHints.layerFor(level, toolFor.keySet()) != com.vale.helperbot.knowledge.Habitat.Layer.SURFACE, !seaOnly(), SCAN_CHUNKS * 16, mode.speed());
		});
		if (!explorer.ready()) {
			phase = Phase.MAP;
			return Status.RUNNING;
		}
		explorer.look(bot.blockPosition()); // here's been looked at: the next place is somewhere else
		var owner = bot.current(requester);
		BlockPos next = explorer.timeUp() ? null : explorer.next(level, bot.blockPosition(),
				owner != null && owner.level() == level ? owner.blockPosition() : null, Math.max(1, wanted - collected(bot)));
		if (next != null) {
			if (!announcedExplore) {
				announcedExplore = true;
				bot.log("探索地图算好了，用了 " + explorer.tookMillis() + " 毫秒");
				bot.say(requester, Component.literal("附近没有，到方圆 " + Explorer.RADIUS + " 格内会生成它的地方找……"));
			}
			bot.log("去 " + next.getX() + ", " + next.getZ() + " 找");
			exploring = explorer;
			startTravel(bot, next);
			return Status.RUNNING;
		}
		if (explorer.nowhereInRange() && !triedBiome) {
			triedBiome = true;
			return startLocate(bot);
		}
		return giveUp(bot, explorer.timeUp() ? "找了 " + explorer.seconds() / 60 + " 分钟，没找到"
				: "方圆 " + Explorer.RADIUS + " 格内能去的地方都找过了，没找到");
	}

	/** All it comes from is what the sea alone has (see Guard#ofTheSea): the one thing to go to the sea for. */
	private boolean seaOnly() {
		return toolFor.keySet().stream().allMatch(b -> Guard.ofTheSea(b.defaultBlockState()));
	}

	/**
	 * Structures done with for this item, the whole order long: got there and found none it could
	 * take, or never got there (the way kept being blocked: a lava lake its windows couldn't see
	 * round, say; maybe there's a way, but not one it finds). Not the ones it got some from: another
	 * step may go back for more. It used to be this step's alone: a new step after a failed one went
	 * back to the bastion it had just been through, then on to the one it couldn't reach, and round.
	 * The next order starts afresh.
	 */
	private final List<BlockPos> structuresDone;
	/** The structure it's on its way to or at, until it's done with it (see #doneWith). */
	private BlockPos structureTrip;
	/** Most structures tried for one item in an order. */
	private static final int MAX_STRUCTURES = 3;

	private void doneWith(BotPlayer bot, String why) {
		bot.log("这一单不再去 " + structureTrip.getX() + ", " + structureTrip.getZ() + " 的结构找" + itemName().getString() + "（" + why + "）");
		structuresDone.add(structureTrip);
		structureTrip = null;
	}

	private boolean announcedExplore;
	/** Set while on the way to one of the explorer's places (not the one trip further off). */
	private Explorer exploring;

	/** Nowhere in range: the nearest biome that makes it, wherever it is. */
	private Status startLocate(BotPlayer bot) {
		ServerLevel level = bot.level();
		bot.say(requester, Component.literal("附近没有，去找会生成它的群系……"));
		BlockPos origin = bot.blockPosition();
		Set<Holder<Biome>> wantedBiomes = new HashSet<>(biomes);
		if (!seaOnly()) {
			wantedBiomes.removeIf(Explorer::water);
		}
		locating = new FarBiome(level, wantedBiomes::contains, origin, 64);
		phase = Phase.LOCATE;
		return Status.RUNNING;
	}

	private Status locate(BotPlayer bot) {
		switch (locating.poll()) {
			case SEARCHING -> {
				return Status.RUNNING;
			}
			case ERROR -> {
				return giveUp(bot, FarBiome.errorText());
			}
			case NONE -> {
				return giveUp(bot, Explorer.FAR + " 格内没有会生成它的群系");
			}
			case FOUND -> {
			}
		}
		if (locating.here()) {
			// Already in that biome: look through everything that's loaded around us, then stop.
			targets = scan(bot, FarBiome.WIDE_SCAN_CHUNKS);
			return targets.isEmpty()
					? giveUp(bot, "就在会生成它的群系里，但已加载的区域里没找到")
					: nextTarget(bot);
		}
		BlockPos at = locating.at();
		bot.say(requester, "最近的 " + locating.biomeName() + " 在 " + at.getX() + ", " + at.getZ() + "，约 " + locating.distance() + " 格，出发。");
		startTravel(bot, at);
		return Status.RUNNING;
	}

	/** That search came to nothing: on to the next target, or with the budget gone, somewhere else. */
	private Status spent(BotPlayer bot) {
		budget -= bot.movement().searchedNodes();
		if (budget > 0) {
			return nextTarget(bot);
		}
		bot.log("这里搜够了，都到不了，换个地方");
		targets.clear();
		return lookFurther(bot);
	}

	private void startTravel(BotPlayer bot, BlockPos to) {
		budget = MovementController.LOCK_NODES;
		travelTarget = to;
		// At the height its sources are at: on the surface for trees and flowers (see Habitat).
		ServerLevel level = bot.level();
		com.vale.helperbot.knowledge.Habitat.Layer layer = BiomeHints.layerFor(level, toolFor.keySet());
		bot.movement().goTo(com.vale.helperbot.knowledge.Habitat.arrived(to, 16, layer),
				com.vale.helperbot.knowledge.Habitat.target(level, to, layer, bot.blockPosition()), mode,
				!com.vale.helperbot.knowledge.Habitat.heightKnown(layer));
		travelTicks = 0;
		phase = Phase.TRAVEL;
	}

	private Status travel(BotPlayer bot) {
		// (Swimming over the sea bed it's on its way somewhere better: what's under it isn't worth a search.)
		if (++travelTicks % TRAVEL_SCAN_INTERVAL == 0 && (!bot.movement().isInWater() || seaOnly())) {
			List<BlockPos> found = scan(bot, TRAVEL_SCAN_CHUNKS);
			if (!found.isEmpty()) {
				targets = found;
				bot.movement().stop();
				exploring = null;
				return nextTarget(bot);
			}
		}
		if (exploring != null) {
			exploring.tick();
			if (travelTicks % TRAVEL_SCAN_INTERVAL == 0) {
				exploring.look(bot.blockPosition());
			}
		}
		MovementController.Status status = bot.movement().status();
		if (status == MovementController.Status.ARRIVED || (exploring != null && exploring.timeUp())) {
			if (exploring != null) {
				exploring.leavePlace();
				exploring = null;
			} else {
				explorers.remove(item); // the trip further off: from here it looks around anew
			}
			bot.movement().stop();
			phase = Phase.FIND;
		} else if (exploring != null && (status == MovementController.Status.FAILED || status == MovementController.Status.IDLE)) {
			bot.log("去不了那里（" + bot.movement().failReason() + "），换一处");
			exploring.leavePlace();
			exploring = null;
			phase = Phase.FIND;
		} else if (status == MovementController.Status.FAILED || status == MovementController.Status.IDLE) {
			if (++travelFailures > MAX_TRAVEL_FAILURES) {
				if (structureTrip != null) {
					doneWith(bot, "去不了，路上多次受阻");
				}
				return giveUp(bot, "路上多次受阻（" + bot.movement().failReason() + "）");
			}
			startTravel(bot, travelTarget);
		}
		return Status.RUNNING;
	}

	// ---- mining and collecting ----------------------------------------------------------

	private Status approach(BotPlayer bot) {
		if (!bot.level().getBlockState(current).is(currentBlock)) {
			// Someone else got it (or, if it was only in the way, it's gone: good).
			return behind != null ? backToTarget(bot) : nextTarget(bot);
		}
		MovementController.Status status = bot.movement().status();
		BlockPos from = bot.movement().provenFrom();
		if (from != null) {
			// Known, not a mishap on the way: nothing to count, and no going back for the same again.
			bot.log(current.toShortString() + " 确定到不了（" + from.toShortString() + " 发现的），记下，换下一个目标");
			unreachable.proven(realTarget(), Air.fullBudget(bot), from);
			bot.movement().stop();
			behind = null;
			return spent(bot);
		}
		if (status == MovementController.Status.ARRIVED) {
			budget = MovementController.LOCK_NODES;
			phase = Phase.MINE;
		} else if (status == MovementController.Status.FAILED || status == MovementController.Status.IDLE) {
			bot.log("走不到 " + current.toShortString() + "（" + bot.movement().failReason() + "），换下一个目标");
			dropTarget(bot);
			return spent(bot);
		}
		return Status.RUNNING;
	}

	private Status mine(BotPlayer bot) {
		ServerLevel level = bot.level();
		Vec3 eye = bot.getEyePosition();
		Vec3 center = Vec3.atCenterOf(current);
		if (eye.distanceTo(center) > bot.blockInteractionRange()) {
			goNear(bot, current);
			phase = Phase.APPROACH;
			return Status.RUNNING;
		}
		BlockPos blocker = MineSpot.blockerOf(level, eye, current);
		if (blocker != null) {
			return clearTheView(bot, blocker);
		}
		// Mining what was only in the way: the best tool for it, nothing to check about its drops.
		boolean obstacle = behind != null;
		BlockPos aim = current;
		Block aimBlock = currentBlock;
		int tool = obstacle ? Hands.AUTO_TOOL : toolFor.getOrDefault(currentBlock, Hands.HAND);
		// The slot was worked out earlier; the player may have rearranged the bag since.
		if (tool >= 0 && bot.getInventory().getItem(tool).getItem() != toolItems.get(currentBlock)) {
			return toolMoved(bot);
		}
		// Before the first hit: with what's actually in hand, on the block as it actually is, would
		// it drop the item? The plan says so; this catches the hand drifting from the plan for any
		// reason, before a block is wasted, instead of guessing afterwards. Checked inside the mining
		// step, after it took the tool out: taking it out again here would swap it back.
		BlockState targetState = level.getBlockState(current);
		int plannedTool = tool;
		java.util.function.Predicate<ItemStack> handOk = obstacle ? null : held -> handFits(bot, targetState, held, plannedTool);
		Hands.MineResult result = bot.movement().hands().mineTarget(aim, aimBlock, tool, obstacle, handOk);
		return afterHit(bot, result, aim, aimBlock, tool, obstacle, targetState);
	}

	/** Like a player, the bot has to see the block: what's in the way is the next job. */
	private Status clearTheView(BotPlayer bot, BlockPos blocker) {
		ServerLevel level = bot.level();
		BlockState blockerState = level.getBlockState(blocker);
		if (behind == null && toolFor.containsKey(blockerState.getBlock()) && DropAnalysis.dropsFrom(item, blockerState)
				&& Guard.canHarvest(level, blocker, blockerState)) {
			// The blocker is something we want too: take it first, and the one behind it next
			// (back on the list: it was taken off when picked, and used to be forgotten, leaving
			// part of an ore vein behind).
			if (!targets.contains(current)) {
				targets.add(current);
			}
			targets.remove(blocker);
			current = blocker;
			currentBlock = blockerState.getBlock();
			return Status.RUNNING;
		}
		if (!Guard.canBreak(level, blocker, blockerState)) {
			bot.log(blocker.toShortString() + " 的" + blockerState.getBlock().getName().getString() + "挡着"
					+ realTarget().toShortString() + "，又不能挖，换下一个目标");
			dropTarget(bot);
			return nextTarget(bot);
		}
		// A job of its own (see behind): planned like any block (a spot to stand, a breath for
		// it), with the target next.
		if (behind == null) {
			behind = current;
			behindBlock = currentBlock;
		}
		bot.log(blocker.toShortString() + " 的" + blockerState.getBlock().getName().getString() + "挡着"
				+ itemName().getString() + "，先挖它");
		current = blocker;
		currentBlock = blockerState.getBlock();
		goNear(bot, current);
		phase = Phase.APPROACH;
		return Status.RUNNING;
	}

	/** The tool isn't where it was (the player rearranged the bag): work it out again. */
	private Status toolMoved(BotPlayer bot) {
		bot.log("工具位置变了，重新确认工具");
		if (!refreshTools(bot, new HashSet<>(toolFor.keySet()))) {
			return toolsGone(bot);
		}
		if (!toolFor.containsKey(currentBlock)) {
			reject(bot, current);
			return nextTarget(bot); // no tool left for this kind of block, but for others
		}
		return Status.RUNNING;
	}

	/** What came of this tick's mining. */
	private Status afterHit(BotPlayer bot, Hands.MineResult result, BlockPos aim, Block aimBlock, int tool,
							boolean obstacle, BlockState targetState) {
		if (result == Hands.MineResult.WRONG_HAND) {
			ItemStack held = bot.getMainHandItem();
			bot.log("手里的东西挖不出" + itemName().getString() + "：计划用 " + toolName(bot, tool) + "，手里是 "
					+ (held.isEmpty() ? "空手" : held.getHoverName().getString()) + "，方块 " + targetState + "；重新选工具");
			refreshTools(bot, new HashSet<>(toolFor.keySet()));
			if (!toolFor.containsKey(currentBlock) || ++handFixes > 2) {
				bot.log("还是不行，跳过这一块（这是个 bug，请把这段日志发给开发者）");
				handFixes = 0;
				dropTarget(bot);
				return nextTarget(bot);
			}
			return Status.RUNNING;
		}
		if (result == Hands.MineResult.RUNNING) {
			handFixes = 0;
		}
		if (result == Hands.MineResult.FAILED) {
			bot.log("挖不了 " + aim.toShortString() + "（" + aimBlock.getName().getString() + "），换下一个目标");
			dropTarget(bot);
			return nextTarget(bot);
		}
		if (obstacle && (result == Hands.MineResult.DONE || result == Hands.MineResult.GONE)) {
			return backToTarget(bot); // out of the way
		}
		if (result == Hands.MineResult.GONE) {
			// Changed or went by itself (fell off with its root, turned into another block,
			// someone else took it): not its break, so nothing to record or check.
			bot.log(current.toShortString() + " 的方块自己变了/没了，换下一个");
			return nextTarget(bot);
		}
		if (result == Hands.MineResult.DONE) {
			blocksMined++;
			// Noted to check it dropped what it should (see checkDrop); what came out is a source
			// like any other now, and the nearest, so usually picked up next.
			int brokeAt = bot.movement().hands().lastBreakTick();
			bot.loot().onBreak(current, brokeAt);
			lastBreak = current;
			lastBreakBlock = currentBlock;
			lastBreakTick = brokeAt;
			return nextTarget(bot);
		}
		return Status.RUNNING;
	}

	/** Blocks that (almost) always drop the item: for these, simulating the drop is a sure answer. */
	private static final double SURE_DROP_RATE = 0.95;

	/** The hand as far as drops go: plain items act like a bare hand, tools by kind and enchantments. */
	private record HandKey(BlockState state, Item tool, Object enchantments) {
		static HandKey of(BlockState state, ItemStack held) {
			boolean plain = !held.isDamageableItem() && !held.isEnchanted();
			return plain ? new HandKey(state, null, null)
					: new HandKey(state, held.getItem(), held.get(DataComponents.ENCHANTMENTS));
		}
	}

	/** Simulation results, per block state and hand: the answer never changes within a task. */
	private final Map<HandKey, Boolean> dropCheck = new HashMap<>();

	/**
	 * Is what's in hand right for this block? For drops that always come, simulate the break
	 * with exactly this hand (once per state and hand, then remembered). For chance drops (apples
	 * from leaves) a simulation can come up empty by bad luck, so check instead that the hand holds
	 * what the plan chose: the plan already worked out that it can drop the item.
	 */
	private boolean handFits(BotPlayer bot, BlockState state, ItemStack held, int tool) {
		if (DropAnalysis.rate(item, state.getBlock()) >= SURE_DROP_RATE) {
			return dropCheck.computeIfAbsent(HandKey.of(state, held),
					k -> DropAnalysis.wouldDrop(bot.level(), bot, state, held, item));
		}
		if (tool == Hands.HAND) {
			return !held.isDamageableItem() && !held.isEnchanted();
		}
		return held.getItem() == toolItems.get(currentBlock);
	}

	private String toolName(BotPlayer bot, int tool) {
		if (tool == Hands.HAND) {
			return "空手";
		}
		if (tool == Hands.AUTO_TOOL) {
			return "自动选择";
		}
		return bot.getInventory().getItem(tool).getHoverName().getString();
	}

	/**
	 * A few ticks after a break: did it drop the item? It was checked beforehand that it should,
	 * so if a block that (nearly) always drops it gave nothing, something is wrong in the code (or
	 * another mod changes the drop): log the details, skip it and carry on; stop only if it keeps
	 * happening.
	 */
	private Status checkDrop(BotPlayer bot) {
		BlockPos pos = lastBreak;
		lastBreak = null;
		if (bot.loot().dropped(pos, lastBreakTick, item)) {
			anomalies = 0;
			return null;
		}
		if (DropAnalysis.rate(item, lastBreakBlock) < 0.95) {
			return null; // a chance drop (flint from gravel, apples): nothing to read into
		}
		anomalies++;
		bot.log("异常：挖掉 " + pos.toShortString() + " 的" + lastBreakBlock.getName().getString() + "，按掉落表应该掉"
				+ itemName().getString() + "，实际没掉（手里：" + (bot.getMainHandItem().isEmpty() ? "空手"
				: bot.getMainHandItem().getHoverName().getString()) + "）");
		if (anomalies >= MAX_ANOMALIES) {
			return giveUp(bot, "连续 " + anomalies + " 块该掉" + itemName().getString() + "却没掉，可能是 bug 或者别的模组改了掉落（详情见 /bot log）");
		}
		return null;
	}

	private Status fetch(BotPlayer bot, net.minecraft.world.entity.item.ItemEntity e) {
		drops.start(bot, e, mode);
		phase = Phase.FETCH;
		return Status.RUNNING;
	}

	/** Fetching an item from the ground; once it's picked up (or out of reach), the next source. */
	private Status fetch(BotPlayer bot) {
		if (drops.tick(bot, mode) == DropFetch.Result.RUNNING) {
			return Status.RUNNING;
		}
		return nextTarget(bot);
	}

	private void reject(BotPlayer bot, BlockPos pos) {
		if (pos != null) {
			unreachable.failed(pos, Air.fullBudget(bot), bot.movement().feet());
		}
	}

	// ---- bookkeeping --------------------------------------------------------------------

	/** True once it stopped because no tool it has can mine any source any more (worn out, taken). */
	private boolean toolsGone;
	/**
	 * The target waiting behind the block it's on now, when that block is only in the way (dirt
	 * over clay): what's in the way is a job like any other, planned and mined on its own (a breath
	 * for it, a spot to stand), and then the target is next. It used to be mined on the spot as an
	 * "obstacle", counted with the target as one piece of work: under deep water that never fit one
	 * breath, and the target was given up.
	 */
	private BlockPos behind;
	private Block behindBlock;

	/** The block it's really after: the one behind what's in the way, or the current one. */
	private BlockPos realTarget() {
		return behind != null ? behind : current;
	}

	/** Can't do this target now (and whatever was in its way goes with it). */
	private void dropTarget(BotPlayer bot) {
		reject(bot, realTarget());
		behind = null;
	}

	/** What was in the way is gone: on to the target behind it. */
	private Status backToTarget(BotPlayer bot) {
		current = behind;
		currentBlock = behindBlock;
		behind = null;
		goNear(bot, current);
		phase = Phase.APPROACH;
		return Status.RUNNING;
	}

	boolean toolsGone() {
		return toolsGone;
	}

	/**
	 * The tools for this ran out (worn out, or taken from the bag). Not a failure of the source:
	 * the plan can make a new tool and carry on.
	 */
	private Status toolsGone(BotPlayer bot) {
		toolsGone = true;
		return giveUp(bot, "能挖" + itemName().getString() + "的工具用坏了（或者被拿走了）");
	}

	/** Stops searching. If anything was collected, still brings it back. */
	private Status giveUp(BotPlayer bot, String reason) {
		int got = collected(bot);
		if (asStep) {
			bot.say(requester, Component.literal(reason + "（").append(itemName()).append(" 采到 " + got + "/" + wanted + "）。"));
			bot.movement().stop();
			return got > 0 ? Status.DONE : Status.FAILED;
		}
		if (got > 0) {
			bot.say(requester, Component.literal(reason + "，先把采到的 " + got + " 个").append(itemName()).append("带回来。"));
			phase = Phase.RETURN;
			return Status.RUNNING;
		}
		bot.say(requester, Component.literal(reason + "，一个").append(itemName()).append("都没采到，放弃。"));
		return Status.FAILED;
	}

	private int collected(BotPlayer bot) {
		return Math.max(0, count(bot.getInventory()) - startCount);
	}

	private int count(Inventory inv) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).is(item)) {
				n += inv.getItem(i).getCount();
			}
		}
		return n;
	}

	private boolean inventoryFull(BotPlayer bot) {
		Inventory inv = bot.getInventory();
		return inv.getFreeSlot() < 0 && inv.getSlotWithRemainingSpace(new ItemStack(item)) < 0;
	}

	@Override
	public void onEnd(BotPlayer bot, Status status) {
		if (locating != null) {
			locating.cancel();
		}
		if (asStep) {
			return;
		}
		switch (status) {
			case DONE -> bot.say(requester, Component.literal("带回来了 " + collected(bot) + " 个").append(itemName())
					.append("，右键我拿走吧。"));
			case CANCELLED -> bot.say(requester, Component.literal("采集").append(itemName()).append("的任务已取消。"));
			default -> {
			}
		}
	}

	@Override
	public String describe() {
		String what = new ItemStack(item).getHoverName().getString() + " ×" + wanted;
		return describePhase(what) + "（这一步已 " + String.format("%.1f", phaseTicks / 20.0) + " 秒，已挖 " + blocksMined + " 块）";
	}

	private String describePhase(String what) {
		return switch (phase) {
			case PREPARE -> "采集 " + what + "：分析中";
			case FIND -> "采集 " + what + "：在附近找";
			case MAP -> "采集 " + what + "：看哪里有";
			case LOCATE -> "采集 " + what + "：搜索远处的群系";
			case TRAVEL -> "采集 " + what + "：前往 " + travelTarget.getX() + ", " + travelTarget.getZ();
			case WAY -> "采集 " + what + "：算能不能到";
			case APPROACH -> "采集 " + what + "：走向 " + current.toShortString();
			case MINE -> "采集 " + what + "：正在挖 " + current.toShortString();
			case FETCH -> "采集 " + what + "：捡地上的";
			case RETURN -> "采集 " + what + "：返回";
		};
	}
}
