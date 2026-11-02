package com.vale.helperbot.task;

import com.mojang.datafixers.util.Pair;
import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.bot.MovementController;
import com.vale.helperbot.guard.ProtectionZones;
import com.vale.helperbot.knowledge.CraftPlanner;
import com.vale.helperbot.knowledge.Habitat;
import com.vale.helperbot.knowledge.MobDrops;
import com.vale.helperbot.path.Mode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MobSpawnSettings;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One step of a plan: kill mobs that drop the item until enough has been picked up. Fights like a
 * player: has to reach and see the mob, and respects the weapon cooldown.
 *
 * <p>How it looks for them depends on how the game makes them appear (read from the spawn lists,
 * nothing per mob):
 * <ul>
 * <li>animals are mostly placed when the land is generated and respawn very rarely (Java: one try
 * every 20 seconds, only with few animals loaded around): it explores, within
 * {@link #EXPLORE_RADIUS} of where it started, the places whose biome spawns them most, never the
 * same place twice;</li>
 * <li>monsters keep spawning in the dark around players (the bot counts as one): it waits, for
 * the night if it's day under the open sky;</li>
 * <li>mobs in no spawn list of this dimension (phantoms, wither skeletons, guardians...) only
 * appear under special conditions: hunted if some are around, otherwise it says so and stops.</li>
 * </ul>
 * If exploring finds nothing, waiting a while is the fallback for animals too.
 */
final class HuntTask {
	private static final int SCAN_RADIUS = 64;
	/** Give up on one mob after chasing it this long (it may be unreachable, e.g. flying). */
	private static final int CHASE_TIMEOUT = 1200;
	/** The nearest biome where the prey is most common is gone to if it's within this (blocks)... */
	private static final int EXPLORE_MAX_RADIUS = 1000;
	/** ...otherwise it looks around within this (new land costs disk space). */
	private static final int EXPLORE_RADIUS = 500;
	/** Walking speed for estimates, blocks per tick (walking, not sprinting). */
	private static final double TRAVEL_SPEED = 0.2;
	/** Chunks it gets to see when it visits a place (it loads a few chunks around itself). */
	private static final double CHUNKS_SEEN_PER_VISIT = 9;
	/** Time spent looking around at a place, on top of getting there. */
	private static final double VISIT_TICKS = 200;
	/** Animals in a group, worldgen: a share of continuously spawning kinds (fish, squid, bats...) are about. */
	private static final double CONTINUOUS_SPAWN_GUESS = 0.3;
	/** Places to visit are this far apart (about what it sees around itself as it passes). */
	private static final int GRID = 48;
	/** Places within this distance of where it has been count as seen. */
	private static final int SEEN_RADIUS = 40;
	/** Total time spent exploring before falling back to waiting. */
	private static final int EXPLORE_MAX_TICKS = 18_000;
	/** Waiting for spawns: this long while they can actually spawn (dark, for monsters). */
	private static final int SPAWN_WAIT = 6000;
	/** ...and at most this long for the night to come. */
	private static final int NIGHT_WAIT = 12_000;
	private static final int RESCAN_INTERVAL = 100;
	/** Back off to heal below this health; resume at RESUME_HEALTH or after HEAL_MAX_TICKS. */
	private static final float LOW_HEALTH = 8;
	private static final float RESUME_HEALTH = 10;
	private static final int HEAL_MAX_TICKS = 600;

	private enum Phase {
		FIND, LOCATE, EXPLORE, WAIT, CHASE, HEAL
	}

	private final ServerPlayer requester;
	private final Item item;
	private final int wanted;
	private final Mode mode;
	private final Map<EntityType<?>, Double> sources;
	private final Set<UUID> rejected = new HashSet<>();
	private Phase phase = Phase.FIND;
	private int startCount = -1;
	private Mob target;
	private BlockPos aimedAt;
	private int phaseTicks;
	private String failure;

	private BlockPos origin;
	/** Grid places seen (been near) or found unreachable. */
	private final Set<Long> seen = new HashSet<>();
	private final Map<Long, Holder<Biome>> gridBiome = new HashMap<>();
	private final Map<Holder<Biome>, Double> biomeYield = new HashMap<>();
	private BlockPos waypoint;
	private long waypointKey;
	private int exploreTicks;
	private boolean announcedExplore;
	private boolean waitedAlready;
	private int waited;
	private int nightWaited;

	HuntTask(ServerPlayer requester, Item item, int wanted, Mode mode, Map<EntityType<?>, Double> sources) {
		this.requester = requester;
		this.item = item;
		this.wanted = wanted;
		this.mode = mode;
		this.sources = sources;
	}

	String failure() {
		return failure;
	}

	boolean wantsPickup(ItemStack stack) {
		return stack.is(item);
	}

	BotTask.Status tick(BotPlayer bot) {
		if (startCount < 0) {
			startCount = count(bot.getInventory());
			origin = bot.blockPosition();
			bot.say(requester, Component.literal("去打猎：").append(names()).append("，要 ").append(CraftPlanner.name(item))
					.append(" ×" + wanted + "。"));
		}
		if (count(bot.getInventory()) - startCount >= wanted) {
			bot.movement().stop();
			return BotTask.Status.DONE;
		}
		phaseTicks++;
		// Only stop to heal when health can come back (food at 18+, or something to eat): without
		// food it just stood there doing nothing, for nothing.
		if (phase != Phase.HEAL && bot.getHealth() < LOW_HEALTH) {
			if (bot.canRegenerate()) {
				bot.log("血量太低，先停下回血");
				bot.movement().stop();
				setPhase(Phase.HEAL);
			} else if (!warnedNoFood) {
				warnedNoFood = true;
				bot.log("血量低但没有吃的，回不了血，继续（小心点）");
			}
		}
		return switch (phase) {
			case FIND -> find(bot);
			case LOCATE -> locate(bot);
			case EXPLORE -> explore(bot);
			case WAIT -> waitForSpawns(bot);
			case CHASE -> chase(bot);
			case HEAL -> {
				if (bot.getHealth() >= RESUME_HEALTH || phaseTicks > HEAL_MAX_TICKS || !bot.canRegenerate()) {
					setPhase(Phase.FIND);
				}
				yield BotTask.Status.RUNNING;
			}
		};
	}

	private boolean warnedNoFood;

	private void setPhase(Phase next) {
		phase = next;
		phaseTicks = 0;
	}

	// ---- finding ------------------------------------------------------------------------

	private Mob nearestPrey(BotPlayer bot, int radius) {
		ServerLevel level = bot.level();
		List<Mob> mobs = level.getEntitiesOfClass(Mob.class, bot.getBoundingBox().inflate(radius),
				m -> sources.containsKey(m.getType()) && m.isAlive() && !rejected.contains(m.getUUID())
						&& MobDrops.mayHunt(m) && !ProtectionZones.isProtected(level, m.blockPosition()));
		return mobs.stream().min(Comparator.comparingDouble(bot::distanceToSqr)).orElse(null);
	}

	/** Spawns naturally somewhere in this dimension (it's in some biome's spawn list). */
	private boolean natural(ServerLevel level, EntityType<?> type) {
		return !MobDrops.spawnBiomes(level, type).isEmpty();
	}

	private boolean anyAnimals(ServerLevel level) {
		return sources.keySet().stream().anyMatch(t -> t.getCategory() != MobCategory.MONSTER && natural(level, t));
	}

	private boolean anyMonsters(ServerLevel level) {
		return sources.keySet().stream().anyMatch(t -> t.getCategory() == MobCategory.MONSTER && natural(level, t));
	}

	private BotTask.Status find(BotPlayer bot) {
		target = nearestPrey(bot, SCAN_RADIUS);
		if (target != null) {
			bot.log("盯上了 " + target.getType().getDescription().getString() + " @" + target.blockPosition().toShortString());
			bot.holdWeapon();
			setPhase(Phase.CHASE);
			return BotTask.Status.RUNNING;
		}
		ServerLevel level = bot.level();
		markSeen(bot);
		if (!anyAnimals(level) && !anyMonsters(level)) {
			return fail(bot, "附近没有" + names().getString() + "，而它们不会在这个维度的任何群系里自然刷新"
					+ "（只在特殊条件或特定结构里出现），这次不去找了");
		}
		if (anyAnimals(level) && !triedBestBiome) {
			triedBestBiome = true;
			return startBestBiomeSearch(bot);
		}
		if (anyAnimals(level) && exploreTicks < EXPLORE_MAX_TICKS) {
			BlockPos next = nextWaypoint(bot);
			if (next != null) {
				if (!announcedExplore) {
					announcedExplore = true;
					bot.say(requester, Component.literal("在方圆 " + EXPLORE_RADIUS + " 格内找").append(names()).append("。"));
				}
				waypoint = next;
				BlockPos t = next;
				Holder<Biome> biome = gridBiome.get(waypointKey);
				bot.log("去 " + t.getX() + ", " + t.getZ() + " 找（" + biomeName(biome) + "，预计每个区块 "
						+ String.format("%.2f", dropsPerChunk(biome)) + " 个）");
				headFor(bot, t);
				setPhase(Phase.EXPLORE);
				return BotTask.Status.RUNNING;
			}
		}
		if (!waitedAlready) {
			return startWait(bot);
		}
		return fail(bot, anyAnimals(level)
				? "能去的地方都找过了，也等过了，没有能打的" + names().getString()
				: "等了很久也没刷出" + names().getString());
	}

	// ---- the best biome (animals) -------------------------------------------------------

	/**
	 * First choice: the nearest biome where the prey is most common (from the spawn data, see
	 * {@link #dropsPerChunk}: at least half as good as the best biome there is), if one is within
	 * {@link #EXPLORE_MAX_RADIUS}. Found with the same biome search as other tasks use.
	 */
	private BotTask.Status startBestBiomeSearch(BotPlayer bot) {
		ServerLevel level = bot.level();
		Set<Holder<Biome>> all = level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
		double max = 0;
		for (Holder<Biome> b : all) {
			max = Math.max(max, dropsPerChunk(b));
		}
		Set<Holder<Biome>> best = new HashSet<>();
		for (Holder<Biome> b : all) {
			if (max > 0 && dropsPerChunk(b) >= max * BEST_BIOME_SHARE) {
				best.add(b);
			}
		}
		if (best.isEmpty()) {
			return BotTask.Status.RUNNING; // next tick: explore around here
		}
		// The biome where the prey lives: for animals the one up on the surface, not the cave's.
		BlockPos here = bot.blockPosition();
		BlockPos where = layer(level) == Habitat.Layer.SURFACE ? Habitat.target(level, here, Habitat.Layer.SURFACE, here) : here;
		if (best.contains(level.getBiome(where))) {
			bot.log("这里就是" + names().getString() + "多的群系，就在附近找");
			return BotTask.Status.RUNNING;
		}
		BlockPos from = bot.blockPosition();
		locating = CompletableFuture.supplyAsync(() -> level.findClosestBiome3d(best::contains, from, EXPLORE_MAX_RADIUS, 32, 64));
		setPhase(Phase.LOCATE);
		return BotTask.Status.RUNNING;
	}

	private BotTask.Status locate(BotPlayer bot) {
		if (!locating.isDone()) {
			return BotTask.Status.RUNNING;
		}
		Pair<BlockPos, Holder<Biome>> found;
		try {
			found = locating.join();
		} catch (RuntimeException e) {
			found = null;
		}
		locating = null;
		if (found == null) {
			bot.log(EXPLORE_MAX_RADIUS + " 格内没有" + names().getString() + "多的群系，就在附近 " + EXPLORE_RADIUS + " 格内找");
			setPhase(Phase.FIND);
			return BotTask.Status.RUNNING;
		}
		BlockPos t = found.getFirst();
		int distance = (int) Math.sqrt(horizontalDistSq(t, bot.blockPosition()));
		bot.say(requester, Component.literal("附近没有").append(names()).append("，去 " + distance + " 格外的 "
				+ biomeName(found.getSecond()) + " 找（那里最多）。"));
		waypoint = t;
		waypointKey = BEST_BIOME;
		headFor(bot, t);
		setPhase(Phase.EXPLORE);
		return BotTask.Status.RUNNING;
	}

	/** Biomes at least this good (relative to the best one) count as "where it's most common". */
	private static final double BEST_BIOME_SHARE = 0.5;
	/** {@link #waypointKey} for the trip to the best biome (not a place on the grid). */
	private static final long BEST_BIOME = Long.MIN_VALUE;
	private boolean triedBestBiome;
	private CompletableFuture<Pair<BlockPos, Holder<Biome>>> locating;

	// ---- exploring (animals) ------------------------------------------------------------

	private BotTask.Status explore(BotPlayer bot) {
		exploreTicks++;
		if (phaseTicks % 20 == 0) {
			markSeen(bot);
		}
		if (phaseTicks % 40 == 0 && nearestPrey(bot, SCAN_RADIUS) != null) {
			bot.movement().stop();
			setPhase(Phase.FIND);
			return BotTask.Status.RUNNING;
		}
		MovementController.Status status = bot.movement().status();
		if (status == MovementController.Status.ARRIVED || exploreTicks >= EXPLORE_MAX_TICKS) {
			if (waypointKey == BEST_BIOME) {
				// Arrived in the best biome: from now on, look around here.
				origin = bot.blockPosition();
				seen.clear();
				gridBiome.clear();
			}
			seen.add(waypointKey);
			bot.movement().stop();
			setPhase(Phase.FIND);
		} else if (status == MovementController.Status.FAILED || status == MovementController.Status.IDLE) {
			bot.log("去不了那里（" + bot.movement().failReason() + "），换一处");
			seen.add(waypointKey);
			setPhase(Phase.FIND);
		}
		return BotTask.Status.RUNNING;
	}

	/**
	 * The next place to look around here (within {@link #EXPLORE_RADIUS}): the one with the most
	 * expected drops per tick of effort, effort being the walk there plus looking around. Expected
	 * drops come from the game's spawn data for its biome (see {@link #dropsPerChunk}). Biomes are
	 * read from the world generator: nothing is loaded or generated just to decide.
	 */
	private BlockPos nextWaypoint(BotPlayer bot) {
		ServerLevel level = bot.level();
		var biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
		Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
		BlockPos here = bot.blockPosition();
		int cells = EXPLORE_RADIUS / GRID;
		BlockPos best = null;
		double bestRate = 0;
		for (int gx = -cells; gx <= cells; gx++) {
			for (int gz = -cells; gz <= cells; gz++) {
				if (gx * gx + gz * gz > cells * cells) {
					continue;
				}
				long key = ((long) gx << 32) | (gz & 0xffffffffL);
				if (seen.contains(key)) {
					continue;
				}
				int x = origin.getX() + gx * GRID, z = origin.getZ() + gz * GRID;
				Holder<Biome> biome = gridBiome.computeIfAbsent(key, k -> biomeSource.getNoiseBiome(
						QuartPos.fromBlock(x), QuartPos.fromBlock(level.getSeaLevel()), QuartPos.fromBlock(z), sampler));
				double yield = dropsPerChunk(biome) * CHUNKS_SEEN_PER_VISIT;
				if (yield <= 0) {
					continue;
				}
				double d = Math.sqrt((double) (x - here.getX()) * (x - here.getX()) + (double) (z - here.getZ()) * (z - here.getZ()));
				double rate = yield / (d / TRAVEL_SPEED + VISIT_TICKS);
				if (rate > bestRate) {
					bestRate = rate;
					best = new BlockPos(x, here.getY(), z);
					waypointKey = key;
				}
			}
		}
		return best;
	}

	/**
	 * Expected drops of the item per chunk of this biome, from its spawn settings: the chance a
	 * chunk gets a group of animals when generated, the share of groups that are prey (its
	 * spawn weight out of all), the group size, and drops per kill. Kinds that spawn all the time
	 * instead (fish, squid, bats) get a rough guess from their share.
	 */
	private double dropsPerChunk(Holder<Biome> biome) {
		return biomeYield.computeIfAbsent(biome, b -> {
			MobSpawnSettings settings = b.value().getMobSettings();
			double drops = 0;
			for (MobCategory category : MobCategory.values()) {
				if (category == MobCategory.MONSTER) {
					continue;
				}
				var entries = settings.getMobs(category).unwrap();
				int total = 0;
				for (Weighted<MobSpawnSettings.SpawnerData> entry : entries) {
					total += entry.weight();
				}
				if (total <= 0) {
					continue;
				}
				double groupChance = category == MobCategory.CREATURE ? settings.getCreatureProbability() : CONTINUOUS_SPAWN_GUESS;
				for (Weighted<MobSpawnSettings.SpawnerData> entry : entries) {
					Double perKill = sources.get(entry.value().type());
					if (perKill != null) {
						double group = (entry.value().minCount() + entry.value().maxCount()) / 2.0;
						drops += groupChance * entry.weight() / total * group * perKill;
					}
				}
			}
			return drops;
		});
	}


	/** Where the prey lives: animals on the surface; monsters anywhere dark (see {@link Habitat}). */
	private Habitat.Layer layer(ServerLevel level) {
		if (layer == null) {
			layer = Habitat.ofAll(sources.keySet());
		}
		return layer;
	}

	private Habitat.Layer layer;

	/** Off towards that x/z, at the height the prey lives at (see {@link Habitat}). */
	private void headFor(BotPlayer bot, BlockPos xz) {
		ServerLevel level = bot.level();
		Habitat.Layer l = layer(level);
		bot.movement().goTo(Habitat.arrived(level, xz, 16, l), Habitat.target(level, xz, l, bot.blockPosition()), mode,
				!Habitat.heightKnown(l));
	}

	/** Places near where it is now count as seen (it would have spotted the prey there). */
	private void markSeen(BotPlayer bot) {
		if (origin == null || (layer(bot.level()) == Habitat.Layer.SURFACE && !Habitat.onSurface(bot.level(), bot.blockPosition()))) {
			return; // passing underneath, through the rock, it hasn't seen what's up there
		}
		BlockPos here = bot.blockPosition();
		int r = SEEN_RADIUS / GRID + 1;
		int cx = Math.round((float) (here.getX() - origin.getX()) / GRID);
		int cz = Math.round((float) (here.getZ() - origin.getZ()) / GRID);
		for (int gx = cx - r; gx <= cx + r; gx++) {
			for (int gz = cz - r; gz <= cz + r; gz++) {
				double dx = origin.getX() + gx * GRID - here.getX(), dz = origin.getZ() + gz * GRID - here.getZ();
				if (dx * dx + dz * dz <= SEEN_RADIUS * SEEN_RADIUS) {
					seen.add(((long) gx << 32) | (gz & 0xffffffffL));
				}
			}
		}
	}

	// ---- waiting for spawns -------------------------------------------------------------

	private BotTask.Status startWait(BotPlayer bot) {
		waitedAlready = true;
		waited = 0;
		nightWaited = 0;
		bot.movement().stop();
		if (anyMonsters(bot.level())) {
			bot.say(requester, Component.literal("附近没有").append(names())
					.append("，在这里等它们刷出来（怪物只在黑暗处刷新，白天在露天就等到天黑）。"));
		} else {
			bot.say(requester, Component.literal("附近能去的地方都找过了，没找到").append(names())
					.append("，在这里等一会儿看会不会刷新（动物刷新很慢，周围动物多的话几乎不刷）。"));
		}
		setPhase(Phase.WAIT);
		return BotTask.Status.RUNNING;
	}

	private BotTask.Status waitForSpawns(BotPlayer bot) {
		ServerLevel level = bot.level();
		if (layer(level) == Habitat.Layer.SURFACE && !Habitat.onSurface(level, bot.blockPosition())) {
			// Animals only appear on the surface (grass, daylight): waiting in a cave is pointless.
			MovementController.Status status = bot.movement().status();
			if (status != MovementController.Status.MOVING) {
				if (status == MovementController.Status.FAILED && phaseTicks > 1) {
					return fail(bot, "上不到地面（" + bot.movement().failReason() + "），在地下等不到" + names().getString());
				}
				bot.log("先上到地面再等（" + names().getString() + "只在地面上刷）");
				headFor(bot, bot.blockPosition());
			}
			return BotTask.Status.RUNNING;
		}
		// Monsters only spawn in the dark: under the open sky that means at night.
		boolean monsters = anyMonsters(level) && !anyAnimals(level);
		boolean canSpawn = !monsters || !level.canSeeSky(bot.blockPosition().above()) || level.isDarkOutside();
		if (canSpawn) {
			waited++;
		} else if (++nightWaited == 1) {
			bot.log("现在是白天、在露天，怪物刷不出来，等天黑");
		}
		if (waited > SPAWN_WAIT) {
			return fail(bot, "等了 " + SPAWN_WAIT / 1200 + " 分钟也没刷出" + names().getString());
		}
		if (nightWaited > NIGHT_WAIT) {
			return fail(bot, "等天黑等太久了，没刷出" + names().getString());
		}
		if (phaseTicks % RESCAN_INTERVAL == 0 && nearestPrey(bot, SCAN_RADIUS) != null) {
			setPhase(Phase.FIND);
		}
		return BotTask.Status.RUNNING;
	}

	// ---- fighting -----------------------------------------------------------------------

	private BotTask.Status chase(BotPlayer bot) {
		if (target == null || target.isRemoved() || !target.isAlive()) {
			// Its drops are collected by the bot's loot layer (BotPlayer), before this task's
			// next tick; then on to the next one.
			bot.movement().stop();
			setPhase(Phase.FIND);
			return BotTask.Status.RUNNING;
		}
		if (phaseTicks > CHASE_TIMEOUT || !MobDrops.mayHunt(target)) {
			bot.log("追不上 " + target.getType().getDescription().getString() + "，换一只");
			rejected.add(target.getUUID());
			bot.movement().stop();
			setPhase(Phase.FIND);
			return BotTask.Status.RUNNING;
		}
		if (bot.isWithinEntityInteractionRange(target, 0) && bot.hasLineOfSight(target)) {
			bot.movement().stop();
			bot.movement().lookAt(target.getBoundingBox().getCenter());
			if (bot.getAttackStrengthScale(0.5f) >= 0.95f) {
				bot.holdWeapon();
				bot.attack(target);
				bot.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
			}
			return BotTask.Status.RUNNING;
		}
		// The prey keeps moving: follow it, re-aiming (without stopping) when it has wandered off.
		// The spot must be in reach AND in sight: "close" isn't enough with a block in between.
		Mob prey = target;
		java.util.function.Predicate<BlockPos> inReach = com.vale.helperbot.bot.Sight.attackSpot(bot, prey);
		BlockPos at = prey.blockPosition();
		if (bot.movement().status() != MovementController.Status.MOVING) {
			bot.movement().goTo(inReach, at, mode);
			aimedAt = at;
		} else if (aimedAt == null || aimedAt.distSqr(at) > 4) {
			bot.movement().retarget(inReach, at);
			aimedAt = at;
		}
		return BotTask.Status.RUNNING;
	}

	// ---- bookkeeping --------------------------------------------------------------------

	private BotTask.Status fail(BotPlayer bot, String reason) {
		failure = reason;
		bot.movement().stop();
		return count(bot.getInventory()) - startCount > 0 ? BotTask.Status.DONE : BotTask.Status.FAILED;
	}

	private Component names() {
		var text = Component.empty();
		int i = 0;
		for (EntityType<?> type : sources.keySet()) {
			if (i > 0) {
				text.append("、");
			}
			if (i++ == 3) {
				text.append("等");
				break;
			}
			text.append(type.getDescription());
		}
		return text;
	}

	private static String biomeName(Holder<Biome> biome) {
		return biome == null ? "?" : biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
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

	private static long horizontalDistSq(BlockPos a, BlockPos b) {
		long dx = a.getX() - b.getX();
		long dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	String describe() {
		String what = new ItemStack(item).getHoverName().getString();
		return switch (phase) {
			case FIND -> "打猎（" + what + "）：找猎物";
			case LOCATE -> "打猎（" + what + "）：找它们最多的群系";
			case EXPLORE -> "打猎（" + what + "）：去 " + (waypoint == null ? "?" : waypoint.getX() + ", " + waypoint.getZ())
					+ " 找（已探索 " + exploreTicks / 20 + " 秒）";
			case WAIT -> "打猎（" + what + "）：等刷新（已等 " + waited / 20 + " 秒）";
			case CHASE -> "打猎（" + what + "）：追击 " + (target == null ? "?" : target.getType().getDescription().getString());
			case HEAL -> "打猎（" + what + "）：血量低，回血中";
		};
	}

	void onEnd(BotPlayer bot) {
		if (locating != null) {
			locating.cancel(false);
		}
	}
}
