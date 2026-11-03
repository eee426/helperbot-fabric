package com.vale.helperbot.task;

import com.mojang.datafixers.util.Pair;
import com.vale.helperbot.HelperBot;
import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.bot.MovementController;
import com.vale.helperbot.guard.Guard;
import com.vale.helperbot.guard.ProtectionZones;
import com.vale.helperbot.knowledge.BiomeHints;
import com.vale.helperbot.knowledge.BlockScanner;
import net.minecraft.core.component.DataComponents;
import com.vale.helperbot.path.Air;
import com.vale.helperbot.path.BreakTimes;
import com.vale.helperbot.path.Escape;
import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.knowledge.DropAnalysis;
import com.vale.helperbot.knowledge.Obtainability;
import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.Mode;
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
import java.util.concurrent.CompletableFuture;

/**
 * Collect N of any item that comes from breaking a block: works out which blocks drop it and
 * which of the player's tools work (from the real loot tables), finds them nearby or in the
 * right biome far away, mines them like a player, picks up the drops and brings them back.
 */
public class GatherTask implements BotTask {
	private static final int SCAN_CHUNKS = 6;
	private static final int TRAVEL_SCAN_CHUNKS = 3;
	private static final int TRAVEL_SCAN_INTERVAL = 100;
	private static final int MAX_DISTANCE = FetchWaterTask.MAX_DISTANCE;
	/** Give up after this many targets in a row turned out unreachable (any success resets it). */
	private static final int MAX_UNREACHABLE_IN_ROW = 15;
	/** Looked through when already standing in the right biome (about the usual view distance). */
	private static final int WIDE_SCAN_CHUNKS = 10;
	private static final int MAX_TRAVEL_FAILURES = 4;
	private static final double REACH_MARGIN = 0.3;

	private enum Phase {
		PREPARE, FIND, LOCATE, TRAVEL, APPROACH, MINE, COLLECT, RETURN
	}

	private final ServerPlayer requester;
	private final Mode mode;
	private final Item item;
	private final int wanted;
	private final ComeTask returnLeg;
	private Phase phase = Phase.PREPARE;
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
	private final Set<BlockPos> unreachable = new HashSet<>();
	/** How many of the nearest targets are compared each time it picks the next one. */
	private static final int TARGETS_COMPARED = 12;

	/**
	 * Rough ticks to go and mine {@code p}, or NaN if it can't: there must be a spot to stand (or
	 * swim) within reach of it; under water the round trip plus mining at under-water speed must
	 * fit in one breath. So under water, kelp (breaks instantly) is fine, an ore that would take
	 * most of a breath is skipped if there are any others.
	 */
	private double targetCost(BotPlayer bot, BlockPos p, Escape escape) {
		ServerLevel level = bot.level();
		double reach = bot.blockInteractionRange() - REACH_MARGIN;
		Vec3 center = Vec3.atCenterOf(p);
		BlockState state = level.getBlockState(p);
		// With the tool it will really use for this block (not just the fastest in the bag).
		int slot = toolFor.getOrDefault(state.getBlock(), MovementController.HAND);
		ItemStack tool = slot >= 0 ? bot.getInventory().getItem(slot) : ItemStack.EMPTY;
		int dig = BreakTimes.estimateTicksWith(tool, level, p, state);
		if (dig == Integer.MAX_VALUE) {
			return Double.NaN;
		}
		double waterFactor = Air.waterDigFactor(bot);
		int full = Air.fullBudget(bot);
		double bestNeed = Double.NaN;
		boolean anyStand = false;
		int r = (int) Math.ceil(reach);
		search:
		for (int dy = -r - 1; dy <= r; dy++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos q = p.offset(dx, dy, dz);
					if (q.equals(p) || new Vec3(q.getX() + 0.5, q.getY() + bot.getEyeHeight(), q.getZ() + 0.5).distanceTo(center) > reach
							|| Double.isNaN(LocalPathfinder.floorHeight(level, q)) || ProtectionZones.isProtected(level, q)) {
						continue;
					}
					anyStand = true;
					if (Air.breathable(level, q)) {
						bestNeed = 0;
						break search;
					}
					double up = escape.ticks(q);
					double mining = Air.workUnderWater(dig, Air.digPenalty(level, q, waterFactor));
					// Down there (about as long as the way up) and mine, on one breath, keeping what the
					// way back up needs: the planner's rule and margin (see Air#enough). The way down used
					// to be left out (every dive ran out halfway through the block), then the way up was
					// counted twice (once more than the reserve already has it): blocks well within one
					// breath were skipped as out of reach.
					if (!Air.enough(full - up, up, mining + Air.PLAN_SLACK)) {
						continue;
					}
					double need = 2 * up + mining;
					if (Double.isNaN(bestNeed) || need < bestNeed) {
						bestNeed = need;
					}
				}
			}
		}
		if (!anyStand && !nearWater(level, p)) {
			// Buried (ore in solid rock, stone under the dirt): nowhere to stand next to it yet, but
			// digging a way there makes one, like a player would. Costed by what's in the way. (It used
			// to count as unreachable, so with no stone in the open it never got any cobblestone.)
			// Under water this isn't done: a dug tunnel floods, and air is a separate question.
			bestNeed = tunnelCost(bot, p);
		}
		if (Double.isNaN(bestNeed)) {
			return Double.NaN;
		}
		// Per item it gives, not per block: a dead bush (about a stick each) before leaves (1 in 50).
		double perBlock = Math.max(0.01, Math.min(1.0, DropAnalysis.rate(item, state.getBlock())));
		double cost = (Math.sqrt(p.distSqr(bot.blockPosition())) * 5 + bestNeed) / perBlock;
		// Experimental: like the path planner, prefer dry land (see LocalPathfinder#WATER_COST_FACTOR).
		return inWater(level, p) ? cost * LocalPathfinder.WATER_COST_FACTOR : cost;
	}

	/** In or under water (as opposed to on dry land, or at the water's edge from the shore). */
	private static boolean inWater(ServerLevel level, BlockPos p) {
		return LocalPathfinder.isWater(level, p) || LocalPathfinder.isWater(level, p.above());
	}

	/** Most cells looked at along a tunnel (beyond that it's too far to dig anyway). */
	private static final int MAX_TUNNEL = 96;

	/**
	 * Rough ticks to dig straight from the bot to {@code p}: every solid block on the line, at the
	 * speed of the best tool in the bag. NaN if something on the way can't be mined (bedrock).
	 */
	private static double tunnelCost(BotPlayer bot, BlockPos p) {
		ServerLevel level = bot.level();
		Vec3 from = bot.getEyePosition();
		Vec3 to = Vec3.atCenterOf(p);
		double length = from.distanceTo(to);
		Set<BlockPos> seen = new HashSet<>();
		double ticks = 0;
		for (double d = 0; d < length && seen.size() < MAX_TUNNEL; d += 0.5) {
			BlockPos c = BlockPos.containing(from.add(to.subtract(from).scale(d / length)));
			if (c.equals(p) || !seen.add(c) || LocalPathfinder.isPassable(level, c)) {
				continue;
			}
			BlockState s = level.getBlockState(c);
			int t = BreakTimes.estimateTicks(bot.getInventory(), level, c, s);
			if (t == Integer.MAX_VALUE || !Guard.canBreak(level, c, s)) {
				return Double.NaN;
			}
			ticks += t;
		}
		return ticks;
	}

	private static boolean nearWater(ServerLevel level, BlockPos p) {
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
			if (LocalPathfinder.isWater(level, p.relative(d))) {
				return true;
			}
		}
		return false;
	}

	/** Blocks that drop the item with a tool we have, and the inventory slot (or HAND) to use. */
	private final Map<Block, Integer> toolFor = new HashMap<>();
	/** The item that was in each tool slot when it was chosen, to notice the player moving it. */
	private final Map<Block, Item> toolItems = new HashMap<>();
	private final Set<BlockPos> rejected = new HashSet<>();
	private List<BlockPos> targets = new ArrayList<>();
	private BlockPos current;
	private Block currentBlock;

	private Set<Holder<Biome>> biomes;
	private boolean triedBiome;
	private CompletableFuture<Pair<BlockPos, Holder<Biome>>> locating;
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
		this(requester, item, wanted, mode, false);
	}

	GatherTask(ServerPlayer requester, Item item, int wanted, Mode mode, boolean asStep) {
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
			if (collected(bot) < wanted && phase != Phase.COLLECT
					&& collected(bot) + bot.loot().pending(item) >= wanted) {
				// Enough has come out: stop mining and let the drops be collected.
				bot.log("挖够了，等掉落物捡完（还差 " + (wanted - collected(bot)) + " 个）");
				bot.movement().stop();
				phase = Phase.COLLECT;
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
			case LOCATE -> locate(bot);
			case TRAVEL -> travel(bot);
			case APPROACH -> approach(bot);
			case MINE -> mine(bot);
			case COLLECT -> collect(bot);
			case RETURN -> requester.hasDisconnected() ? Status.FAILED : ComeTask.followTick(bot, requester, mode, returnLeg);
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
				toolFor.put(block, slot == DropAnalysis.HAND ? MovementController.HAND : slot);
				if (slot >= 0) {
					toolItems.put(block, bot.getInventory().getItem(slot).getItem());
				}
			}
		}
		return !toolFor.isEmpty();
	}

	// ---- finding ------------------------------------------------------------------------

	private Status find(BotPlayer bot) {
		targets = scan(bot, SCAN_CHUNKS);
		bot.log("附近扫描到 " + targets.size() + " 处" + itemName().getString() + "（不算已放弃的 " + (rejected.size() + unreachable.size()) + " 处）");
		if (!targets.isEmpty()) {
			return nextTarget(bot);
		}
		if (!triedBiome) {
			triedBiome = true;
			return startLocate(bot);
		}
		return giveUp(bot, travelTarget != null
				? "到了会生成它的群系，但已加载的区域里没找到"
				: "附近已加载的区域里没有");
	}

	private List<BlockPos> scan(BotPlayer bot, int chunkRadius) {
		ServerLevel level = bot.level();
		return BlockScanner.find(level, bot.blockPosition(), chunkRadius,
				state -> toolFor.containsKey(state.getBlock()) && DropAnalysis.dropsFrom(item, state),
				pos -> !rejected.contains(pos) && !unreachable.contains(pos) && Guard.canHarvest(level, pos, level.getBlockState(pos)),
				64);
	}

	private Status nextTarget(BotPlayer bot) {
		if (!refreshTools(bot, new HashSet<>(toolFor.keySet()))) {
			return toolsGone(bot);
		}
		ServerLevel level = bot.level();
		BlockPos here = bot.blockPosition();
		// Also drops ones that changed since the scan (berries picked, crop trampled).
		targets.removeIf(p -> rejected.contains(p) || !toolFor.containsKey(level.getBlockState(p).getBlock())
				|| !DropAnalysis.dropsFrom(item, level.getBlockState(p)));
		if (targets.isEmpty()) {
			phase = Phase.FIND;
			return Status.RUNNING;
		}
		targets.sort(Comparator.comparingDouble(p -> p.distSqr(here)));
		// Of the nearest few, the one that's cheapest to get: under water that includes the air it
		// costs (getting there and back, mining at the slow under-water speed). Ones it can't reach
		// on a breath, or can't reach at all, are skipped; e.g. the bottom of a kelp stalk deeper
		// than it can dive, when the top of the same stalk is right there.
		Escape escape = bot.movement().newEscape();
		BlockPos best = null;
		double bestCost = Double.MAX_VALUE;
		// The nearest few; and if some of those are in water, also the nearest few on dry land, even
		// if further away (otherwise a near patch under water always wins against plenty on land).
		int checked = 0;
		int dryChecked = 0;
		boolean sawWater = false;
		for (java.util.Iterator<BlockPos> it = targets.iterator(); it.hasNext()
				&& (checked < TARGETS_COMPARED || (sawWater && dryChecked < TARGETS_COMPARED)); ) {
			BlockPos p = it.next();
			boolean wet = inWater(level, p);
			if (checked >= TARGETS_COMPARED && wet) {
				continue; // past the nearest few: only looking for dry ones now
			}
			checked++;
			sawWater |= wet;
			if (!wet) {
				dryChecked++;
			}
			double cost = targetCost(bot, p, escape);
			if (Double.isNaN(cost)) {
				it.remove();
				unreachable.add(p);
				bot.log(p.toShortString() + " 判断为够不着（附近没有能站着伸手够到它的位置，或者一口气不够），跳过");
				continue;
			}
			if (cost < bestCost) {
				bestCost = cost;
				best = p;
			}
		}
		if (best == null) {
			return nextTarget(bot); // all of those were out of reach: look at the next few
		}
		targets.remove(best);
		current = best;
		currentBlock = level.getBlockState(current).getBlock();
		goNear(bot, current);
		phase = Phase.APPROACH;
		return Status.RUNNING;
	}

	/** Walk to any spot from which the block is within arm's reach. */
	private void goNear(BotPlayer bot, BlockPos block) {
		double reach = bot.blockInteractionRange() - REACH_MARGIN;
		Vec3 center = Vec3.atCenterOf(block);
		// Not from inside a protected chunk: it can't do anything there (see ProtectionZones).
		ServerLevel level = bot.level();
		double eyeHeight = bot.getEyeHeight();
		bot.movement().goTo((w, p) -> !p.equals(block) && !ProtectionZones.isProtected(w, p)
				&& new Vec3(p.getX() + 0.5, p.getY() + eyeHeight, p.getZ() + 0.5).distanceTo(center) <= reach,
				block, mode);
		// Under water the plan must leave air to mine it too, not just to get there and back.
		BlockState state = level.getBlockState(block);
		int dry = dryTicks(bot, block, state, toolFor.getOrDefault(state.getBlock(), MovementController.HAND));
		if (dry != Integer.MAX_VALUE) {
			bot.movement().workAtGoal(dry);
		}
	}

	/** Ticks to mine it on dry land with this tool (a slot, the hand, or the best there is), or MAX_VALUE if it can't be. */
	private static int dryTicks(BotPlayer bot, BlockPos pos, BlockState state, int tool) {
		return tool == MovementController.AUTO_TOOL
				? BreakTimes.estimateTicks(bot.getInventory(), bot.level(), pos, state)
				: BreakTimes.estimateTicksWith(tool >= 0 ? bot.getInventory().getItem(tool) : ItemStack.EMPTY, bot.level(), pos, state);
	}

	private Status startLocate(BotPlayer bot) {
		ServerLevel level = bot.level();
		if (biomes == null) {
			biomes = BiomeHints.biomesFor(level, toolFor.keySet());
		}
		if (biomes.isEmpty()) {
			// No biome generates it (e.g. only found in villages and other structures): wandering
			// around at random almost never finds such things, so say so instead of pretending.
			return giveUp(bot, "附近已加载的区域里没有，也不知道该去哪找（它可能只出现在村庄等结构里）");
		}
		bot.say(requester, Component.literal("附近没有，去找会生成它的群系……"));
		BlockPos origin = bot.blockPosition();
		Set<Holder<Biome>> wantedBiomes = biomes;
		// Samples world-gen noise only (no chunk loading), so it can run off-thread.
		locating = CompletableFuture.supplyAsync(() -> level.findClosestBiome3d(wantedBiomes::contains,
				origin, MAX_DISTANCE, 64, 64));
		phase = Phase.LOCATE;
		return Status.RUNNING;
	}

	private Status locate(BotPlayer bot) {
		if (!locating.isDone()) {
			return Status.RUNNING;
		}
		Pair<BlockPos, Holder<Biome>> found;
		try {
			found = locating.join();
		} catch (RuntimeException e) {
			HelperBot.LOGGER.error("Biome search failed", e);
			found = null;
		}
		if (found == null) {
			return giveUp(bot, MAX_DISTANCE + " 格内没有会生成它的群系");
		}
		BlockPos at = found.getFirst();
		int distance = (int) Math.sqrt(horizontalDistSq(bot.blockPosition(), at));
		if (distance < 48) {
			// Already in that biome: look through everything that's loaded around us, then stop.
			targets = scan(bot, WIDE_SCAN_CHUNKS);
			return targets.isEmpty()
					? giveUp(bot, "就在会生成它的群系里，但已加载的区域里没找到")
					: nextTarget(bot);
		}
		String name = found.getSecond().unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
		bot.say(requester, "最近的 " + name + " 在 " + at.getX() + ", " + at.getZ() + "，约 " + distance + " 格，出发。");
		startTravel(bot, at);
		return Status.RUNNING;
	}

	private void startTravel(BotPlayer bot, BlockPos to) {
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
		if (++travelTicks % TRAVEL_SCAN_INTERVAL == 0) {
			List<BlockPos> found = scan(bot, TRAVEL_SCAN_CHUNKS);
			if (!found.isEmpty()) {
				targets = found;
				bot.movement().stop();
				return nextTarget(bot);
			}
		}
		MovementController.Status status = bot.movement().status();
		if (status == MovementController.Status.ARRIVED) {
			phase = Phase.FIND;
		} else if (status == MovementController.Status.FAILED || status == MovementController.Status.IDLE) {
			if (++travelFailures > MAX_TRAVEL_FAILURES) {
				return giveUp(bot, "路上多次受阻（" + bot.movement().failReason() + "）");
			}
			startTravel(bot, travelTarget);
		}
		return Status.RUNNING;
	}

	// ---- mining and collecting ----------------------------------------------------------

	private Status approach(BotPlayer bot) {
		if (!bot.level().getBlockState(current).is(currentBlock)) {
			return nextTarget(bot); // someone else got it
		}
		MovementController.Status status = bot.movement().status();
		if (status == MovementController.Status.ARRIVED) {
			phase = Phase.MINE;
		} else if (status == MovementController.Status.FAILED || status == MovementController.Status.IDLE) {
			bot.log("走不到 " + current.toShortString() + "（" + bot.movement().failReason() + "），换下一个目标");
			reject(current);
			if (++unreachableInRow >= MAX_UNREACHABLE_IN_ROW) {
				return giveUp(bot, "连续 " + unreachableInRow + " 处都走不到（最后一次：" + bot.movement().failReason() + "）");
			}
			return nextTarget(bot);
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
		// Like a player, the bot has to see the block: if something is in the way, deal with that first.
		BlockHitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
		BlockPos aim = current;
		Block aimBlock = currentBlock;
		boolean obstacle = false;
		if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(current)) {
			BlockPos blocker = hit.getBlockPos();
			BlockState blockerState = level.getBlockState(blocker);
			if (toolFor.containsKey(blockerState.getBlock()) && DropAnalysis.dropsFrom(item, blockerState)
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
			aim = blocker;
			aimBlock = blockerState.getBlock();
			obstacle = true;
		}
		int tool = obstacle ? MovementController.AUTO_TOOL : toolFor.getOrDefault(currentBlock, MovementController.HAND);
		// The slot was worked out earlier; the player may have rearranged the bag since.
		if (tool >= 0 && bot.getInventory().getItem(tool).getItem() != toolItems.get(currentBlock)) {
			bot.log("工具位置变了，重新确认工具");
			if (!refreshTools(bot, new HashSet<>(toolFor.keySet()))) {
				return toolsGone(bot);
			}
			if (!toolFor.containsKey(currentBlock)) {
				reject(current);
				return nextTarget(bot); // no tool left for this kind of block, but for others
			}
			return Status.RUNNING;
		}
		if (!bot.movement().isBreaking(aim)) {
			Status air = airCheck(bot, aim, aimBlock, tool);
			if (air != null) {
				return air;
			}
		}
		// Before the first hit: with what's actually in hand, on the block as it actually is, would
		// it drop the item? The plan says so; this catches the hand drifting from the plan for any
		// reason, before a block is wasted, instead of guessing afterwards. Checked inside the mining
		// step, after it took the tool out: taking it out again here would swap it back.
		BlockState targetState = level.getBlockState(current);
		int plannedTool = tool;
		java.util.function.Predicate<ItemStack> handOk = obstacle ? null : held -> handFits(bot, targetState, held, plannedTool);
		MovementController.MineResult result = bot.movement().mineTarget(aim, aimBlock, tool, obstacle, handOk);
		if (result == MovementController.MineResult.WRONG_HAND) {
			ItemStack held = bot.getMainHandItem();
			bot.log("手里的东西挖不出" + itemName().getString() + "：计划用 " + toolName(bot, tool) + "，手里是 "
					+ (held.isEmpty() ? "空手" : held.getHoverName().getString()) + "，方块 " + targetState + "；重新选工具");
			refreshTools(bot, new HashSet<>(toolFor.keySet()));
			if (!toolFor.containsKey(currentBlock) || ++handFixes > 2) {
				bot.log("还是不行，跳过这一块（这是个 bug，请把这段日志发给开发者）");
				handFixes = 0;
				reject(current);
				return nextTarget(bot);
			}
			return Status.RUNNING;
		}
		if (result == MovementController.MineResult.RUNNING) {
			handFixes = 0;
		}
		if (result == MovementController.MineResult.FAILED) {
			bot.log("挖不了 " + aim.toShortString() + "（" + aimBlock.getName().getString() + "），换下一个目标");
			reject(current);
			return nextTarget(bot);
		}
		if (result == MovementController.MineResult.GONE && !obstacle) {
			// Changed or went by itself (fell off with its root, turned into another block,
			// someone else took it): not its break, so nothing to record or check.
			bot.log(current.toShortString() + " 的方块自己变了/没了，换下一个");
			return nextTarget(bot);
		}
		if (result == MovementController.MineResult.DONE && !obstacle) {
			blocksMined++;
			unreachableInRow = 0;
			// The drop tracker follows what came out (and the bot's loot layer collects it).
			int brokeAt = bot.movement().lastBreakTick();
			bot.loot().onBreak(current, brokeAt);
			lastBreak = current;
			lastBreakBlock = currentBlock;
			lastBreakTick = brokeAt;
			return nextTarget(bot);
		}
		return Status.RUNNING;
	}

	/**
	 * Under water, before the first hit: is there air enough to break it and still get up? Mining
	 * progress is lost when it leaves, so starting on half a breath means doing it all again. Like a
	 * player: not enough left, go up for air first; not even on a full breath from here, skip it.
	 * Null: go ahead and mine.
	 */
	private Status airCheck(BotPlayer bot, BlockPos aim, Block expected, int tool) {
		if (!bot.movement().headUnderwater()) {
			return null;
		}
		int budget = Air.budget(bot);
		if (budget >= Air.UNLIMITED) {
			return null;
		}
		ServerLevel level = bot.level();
		BlockState state = level.getBlockState(aim);
		if (!state.is(expected)) {
			// Already gone (mined, fell): the mining step notices. It used to cost the water that took
			// its place (hardness 100: "3750 seconds") and skip a perfectly good target.
			return null;
		}
		int dig = dryTicks(bot, aim, state, tool);
		if (dig == Integer.MAX_VALUE) {
			return null; // can't be mined at all: the mining step says so
		}
		// At the speed it will really go: under water, and 5x slower again when not standing (the
		// same reckoning the plan made, see Air#digPenalty).
		double mining = Air.workUnderWater(dig, Air.digPenalty(level, bot.blockPosition(), Air.waterDigFactor(bot)));
		double escape = bot.movement().escapeTicks();
		if (Air.enough(budget, escape, mining)) {
			return null;
		}
		// Even with a full breath: back down here (about as long as the way up), then the mining.
		if (!Air.enough(Air.fullBudget(bot) - escape, escape, mining)) {
			bot.log(aim.toShortString() + " 在水下要挖 " + (int) mining / 20 + " 秒，一口气挖不完再游上去，跳过");
			reject(current);
			return nextTarget(bot);
		}
		bot.log("剩下的气不够挖完 " + aim.toShortString() + "（要 " + (int) mining / 20 + " 秒），先去换气再挖");
		bot.movement().requestAir();
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
		if (tool == MovementController.HAND) {
			return !held.isDamageableItem() && !held.isEnchanted();
		}
		return held.getItem() == toolItems.get(currentBlock);
	}

	private String toolName(BotPlayer bot, int tool) {
		if (tool == MovementController.HAND) {
			return "空手";
		}
		if (tool == MovementController.AUTO_TOOL) {
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

	/**
	 * Enough has been mined; the bot's loot layer is collecting the drops (it runs before the task
	 * each tick). Under water it leaves drops floating up to the surface, so go up there. If some
	 * drops are lost (despawned, unreachable), back to mining.
	 */
	private Status collect(BotPlayer bot) {
		if (bot.loot().pending(item) == 0) {
			if (collected(bot) < wanted) {
				bot.log("有些掉落物没捡到，接着挖");
				return nextTarget(bot);
			}
			return Status.RUNNING; // tick() wraps up next time round
		}
		ServerLevel level = bot.level();
		if (bot.movement().headUnderwater() && bot.movement().status() != MovementController.Status.MOVING) {
			bot.movement().goTo((w, p) -> Air.breathable(w, p), bot.blockPosition().above(bot.movement().waterAbove() + 1), mode);
		}
		return Status.RUNNING;
	}

	private void reject(BlockPos pos) {
		if (pos != null) {
			rejected.add(pos);
		}
	}

	// ---- bookkeeping --------------------------------------------------------------------

	/** True once it stopped because no tool it has can mine any source any more (worn out, taken). */
	private boolean toolsGone;
	private int unreachableInRow;

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

	private static long horizontalDistSq(BlockPos a, BlockPos b) {
		long dx = a.getX() - b.getX();
		long dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	@Override
	public void onEnd(BotPlayer bot, Status status) {
		if (locating != null) {
			locating.cancel(false);
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
			case LOCATE -> "采集 " + what + "：搜索远处的群系";
			case TRAVEL -> "采集 " + what + "：前往 " + travelTarget.getX() + ", " + travelTarget.getZ();
			case APPROACH -> "采集 " + what + "：走向 " + current.toShortString();
			case MINE -> "采集 " + what + "：正在挖 " + current.toShortString();
			case COLLECT -> "采集 " + what + "：捡掉落物";
			case RETURN -> "采集 " + what + "：返回";
		};
	}
}
