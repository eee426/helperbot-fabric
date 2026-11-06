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
 * every 20 seconds, only with few animals loaded around): it goes looking (see {@link Explorer}),
 * never the same place twice;</li>
 * <li>monsters keep spawning in the dark around players (the bot counts as one): it waits, for
 * the night if it's day under the open sky, first going to where they spawn if they don't here;</li>
 * <li>mobs in no spawn list of this dimension (phantoms, wither skeletons, guardians...) only
 * appear under special conditions: hunted if some are around, otherwise it says so and stops.</li>
 * </ul>
 */
final class HuntTask {
	private static final int SCAN_RADIUS = 64;
	/** Give up on one mob after chasing it this long (it may be unreachable, e.g. flying). */
	private static final int CHASE_TIMEOUT = 1200;
	/** Animals in a group, worldgen: a share of continuously spawning kinds (fish, squid, bats...) are about. */
	private static final double CONTINUOUS_SPAWN_GUESS = 0.3;
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
		FIND, LOCATE, EXPLORE, WAIT, WAY, CHASE, FETCH, HEAL
	}

	private final ServerPlayer requester;
	private final Item item;
	private final int wanted;
	private final Mode mode;
	private final Map<EntityType<?>, Double> sources;
	private final Set<UUID> rejected = new HashSet<>();
	private Phase phase = Phase.FIND;
	/** Items on the ground are sources too (a kill's drops, say): the one it's fetching, in FETCH (see DropFetch). */
	private final DropFetch drops = new DropFetch();
	/**
	 * Ticks a kill takes besides getting there (a few hits, the mob running), for weighing a mob
	 * against an item on the ground on one scale (see DropFetch#cost).
	 */
	private static final double KILL_TICKS = 100;
	private int startCount = -1;
	private Mob target;
	private BlockPos aimedAt;
	private int phaseTicks;
	private String failure;

	/** For this item, the whole order long (see Explorer). */
	private final Map<Item, Explorer> explorers;
	private Explorer explorer;
	private final Map<Holder<Biome>, Double> biomeYield = new HashMap<>();
	private BlockPos waypoint;
	private boolean announcedExplore;
	private boolean waitedAlready;
	private int waited;
	private int nightWaited;

	HuntTask(ServerPlayer requester, Item item, int wanted, Mode mode, Map<EntityType<?>, Double> sources, Map<Item, Explorer> explorers) {
		this.explorers = explorers;
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
			case WAY -> way(bot);
			case CHASE -> chase(bot);
			case FETCH -> {
				if (drops.tick(bot, mode) != DropFetch.Result.RUNNING) {
					setPhase(Phase.FIND);
				}
				yield BotTask.Status.RUNNING;
			}
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
		shallowestTooDeep = Integer.MAX_VALUE;
		Mob best = null;
		for (Mob m : mobs) {
			int depth = waterOver(level, m);
			if (depth > 0 && !withinBreath(bot, depth)) {
				shallowestTooDeep = Math.min(shallowestTooDeep, depth);
				continue;
			}
			if (best == null || bot.distanceToSqr(m) < bot.distanceToSqr(best)) {
				best = m;
			}
		}
		return best;
	}

	/** Of the prey around left out as too deep for one breath (see withinBreath), the least deep; MAX_VALUE if none. */
	private int shallowestTooDeep = Integer.MAX_VALUE;

	/** Ticks of fighting under water, besides getting there: a few hits on a swimming mob. */
	private static final double FIGHT_UNDERWATER_TICKS = 80;

	/**
	 * Blocks of water above what the bot must get to (within its reach of the mob, about 2 blocks
	 * above it): 0 if that's in the open air.
	 */
	private static int waterOver(ServerLevel level, Mob m) {
		BlockPos p = BlockPos.containing(m.getEyePosition()).above(2);
		int n = 0;
		while (n < 64 && com.vale.helperbot.path.LocalPathfinder.isWater(level, p)) {
			p = p.above();
			n++;
		}
		return n;
	}

	/**
	 * Down there, the fight, back up, on one breath (the planner's rule, see Air#enough). Deep-sea
	 * mobs (a glow squid 20 blocks down) are out of reach without water breathing: it used to go
	 * for them anyway, dive as far as its air allowed, come back up, and do it again, forever.
	 */
	private static boolean withinBreath(BotPlayer bot, int depth) {
		int full = com.vale.helperbot.path.Air.fullBudget(bot);
		if (full >= com.vale.helperbot.path.Air.UNLIMITED) {
			return true;
		}
		double up = com.vale.helperbot.path.Air.ascentTicks(bot.movement().airProfile(), depth);
		return com.vale.helperbot.path.Air.enough(full - up, up, FIGHT_UNDERWATER_TICKS + com.vale.helperbot.path.Air.PLAN_SLACK);
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
		// What lies on the ground (its kills' drops, or anyone's) against the nearest mob, on one scale.
		int needed = wanted - (count(bot.getInventory()) - startCount);
		net.minecraft.world.entity.item.ItemEntity bestItem = null;
		double bestItemCost = Double.MAX_VALUE;
		int lying = 0;
		for (net.minecraft.world.entity.item.ItemEntity e : drops.nearby(bot, item, SCAN_RADIUS)) {
			lying += e.getItem().getCount();
			double c = DropFetch.cost(bot, e, needed, bot.movement().newEscape());
			if (c < bestItemCost) {
				bestItemCost = c;
				bestItem = e;
			}
		}
		if (bestItem != null) {
			double preyCost = target == null ? Double.MAX_VALUE
					: (bot.distanceTo(target) * 5 + KILL_TICKS) / Math.max(0.01, sources.getOrDefault(target.getType(), 1.0));
			if (lying >= needed || bestItemCost <= preyCost) {
				target = null;
				drops.start(bot, bestItem, mode);
				setPhase(Phase.FETCH);
				return BotTask.Status.RUNNING;
			}
		}
		if (target == null && shallowestTooDeep != Integer.MAX_VALUE) {
			return fail(bot, "附近的" + names().getString() + "都在水下太深（最浅的也在水面下 " + shallowestTooDeep
					+ " 格），一口气潜不到、打完再回来。给我水下呼吸药水，或者戴一个有水下呼吸附魔的头盔，就能去");
		}
		if (target != null) {
			if (target.isUnderWater() && !bot.movement().headUnderwater()) {
				return findWay(bot);
			}
			return lockOn(bot);
		}
		ServerLevel level = bot.level();
		if (explorer != null) {
			explorer.look(bot.blockPosition());
		}
		if (!anyAnimals(level) && !anyMonsters(level)) {
			return fail(bot, "附近没有" + names().getString() + "，而它们不会在这个维度的任何群系里自然刷新"
					+ "（只在特殊条件或特定结构里出现），这次不去找了");
		}
		if (waitedAlready) {
			return fail(bot, "等了很久也没刷出" + names().getString());
		}
		return lookFurther(bot);
	}

	private BotTask.Status lockOn(BotPlayer bot) {
		bot.log("盯上了 " + target.getType().getDescription().getString() + " @" + target.blockPosition().toShortString());
		aimedAt = null;
		bot.holdWeapon();
		setPhase(Phase.CHASE);
		return BotTask.Status.RUNNING;
	}

	/** The prey under water around here, each with where it can be hit from (see findWay). */
	private final Map<Mob, com.vale.helperbot.path.PathGoal> wayTo = new HashMap<>();

	/**
	 * In a closed-off cave under water it can look near and be out of reach: before going in, one
	 * search says if any of it can be got at, from where it is now (see MovementController#findWay).
	 * It doesn't say it will catch what swims off; it only leaves out what's plainly not to be had.
	 * Only for what's under water: on land, chasing it is how it finds out, at no risk.
	 */
	private BotTask.Status findWay(BotPlayer bot) {
		ServerLevel level = bot.level();
		wayTo.clear();
		for (Mob m : level.getEntitiesOfClass(Mob.class, bot.getBoundingBox().inflate(SCAN_RADIUS),
				m -> sources.containsKey(m.getType()) && m.isAlive() && !rejected.contains(m.getUUID()) && MobDrops.mayHunt(m)
						&& !ProtectionZones.isProtected(level, m.blockPosition()) && m.isUnderWater() && withinBreath(bot, waterOver(level, m)))) {
			wayTo.put(m, com.vale.helperbot.bot.Sight.attackSpot(bot, m));
		}
		List<com.vale.helperbot.path.PathGoal> goals = List.copyOf(wayTo.values());
		bot.movement().findWay(wayTo.keySet().stream().map(Mob::blockPosition).toList(), (w, p) -> {
			for (com.vale.helperbot.path.PathGoal g : goals) {
				if (g.test(w, p)) {
					return true;
				}
			}
			return false;
		}, mode);
		setPhase(Phase.WAY);
		return BotTask.Status.RUNNING;
	}

	private BotTask.Status way(BotPlayer bot) {
		if (bot.movement().findingWay()) {
			return BotTask.Status.RUNNING;
		}
		BlockPos end = bot.movement().wayEnd();
		bot.movement().stop();
		target = null;
		for (Map.Entry<Mob, com.vale.helperbot.path.PathGoal> e : wayTo.entrySet()) {
			if (end != null && e.getKey().isAlive() && e.getValue().test(bot.level(), end)
					&& (target == null || e.getKey().distanceToSqr(bot) < target.distanceToSqr(bot))) {
				target = e.getKey();
			}
		}
		if (target != null) {
			return lockOn(bot);
		}
		bot.log("水下的" + names().getString() + "到不了（" + wayTo.size() + " 只），不去");
		wayTo.keySet().forEach(m -> rejected.add(m.getUUID()));
		setPhase(Phase.FIND);
		return BotTask.Status.RUNNING;
	}

	// ---- looking further ------------------------------------------------------------------

	/** Where to look from here (see Explorer). */
	private BotTask.Status lookFurther(BotPlayer bot) {
		ServerLevel level = bot.level();
		if (explorer == null) {
			explorer = explorers.computeIfAbsent(item, i -> new Explorer(level, bot.blockPosition(), worth(level), layer(level) != Habitat.Layer.SURFACE, false, SCAN_RADIUS, mode.speed()));
		}
		if (!explorer.ready()) {
			setPhase(Phase.LOCATE);
			return BotTask.Status.RUNNING;
		}
		boolean monsters = !anyAnimals(level);
		if (monsters && !waitedAlready && worth(level).getOrDefault(level.getBiome(bot.blockPosition()), 0.0) > 0) {
			return startWait(bot); // they come to where it is: going anywhere won't bring more
		}
		var owner = bot.current(requester);
		BlockPos next = explorer.timeUp() ? null : explorer.next(level, bot.blockPosition(),
				owner != null && owner.level() == level ? owner.blockPosition() : null, Math.max(1, wanted - (count(bot.getInventory()) - startCount)));
		if (next != null) {
			if (!announcedExplore) {
				announcedExplore = true;
				bot.log("探索地图算好了，用了 " + explorer.tookMillis() + " 毫秒");
				bot.say(requester, Component.literal("附近没有").append(names()).append("，到方圆 " + Explorer.RADIUS + " 格内找。"));
			}
			waypoint = next;
			bot.log("去 " + next.getX() + ", " + next.getZ() + " 找（" + biomeName(explorer.headingBiome()) + "）");
			headFor(bot, next);
			setPhase(Phase.EXPLORE);
			return BotTask.Status.RUNNING;
		}
		if (explorer.nowhereInRange() && !triedFar) {
			triedFar = true;
			return startFarSearch(bot);
		}
		if (monsters && !waitedAlready) {
			return startWait(bot);
		}
		return fail(bot, explorer.timeUp() ? "找了 " + explorer.seconds() / 60 + " 分钟，没找到" + names().getString()
				: "方圆 " + Explorer.RADIUS + " 格内能去的地方都找过了，没找到" + names().getString());
	}

	/** Nowhere in range: the nearest biome that has them, wherever it is. Then it looks around there. */
	private BotTask.Status startFarSearch(BotPlayer bot) {
		ServerLevel level = bot.level();
		Map<Holder<Biome>, Double> worth = worth(level);
		BlockPos from = bot.blockPosition();
		locating = CompletableFuture.supplyAsync(() -> level.findClosestBiome3d(b -> worth.getOrDefault(b, 0.0) > 0, from, Explorer.FAR, 32, 64));
		setPhase(Phase.LOCATE);
		return BotTask.Status.RUNNING;
	}

	private BotTask.Status locate(BotPlayer bot) {
		if (locating == null) {
			return explorer.ready() ? lookFurther(bot) : BotTask.Status.RUNNING;
		}
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
			return fail(bot, Explorer.FAR + " 格内没有" + names().getString() + "的群系");
		}
		BlockPos t = found.getFirst();
		int distance = (int) Math.sqrt(horizontalDistSq(t, bot.blockPosition()));
		bot.say(requester, Component.literal("附近没有").append(names()).append("，去 " + distance + " 格外的 "
				+ biomeName(found.getSecond()) + " 找。"));
		waypoint = t;
		farTrip = true;
		headFor(bot, t);
		setPhase(Phase.EXPLORE);
		return BotTask.Status.RUNNING;
	}

	private boolean triedFar;
	/** On the way to a biome past the explorer's range: there, a new one takes over. */
	private boolean farTrip;
	private CompletableFuture<Pair<BlockPos, Holder<Biome>>> locating;

	// ---- exploring ----------------------------------------------------------------------

	private BotTask.Status explore(BotPlayer bot) {
		explorer.tick();
		ServerLevel level = bot.level();
		if (phaseTicks % 20 == 0 && (layer(level) != Habitat.Layer.SURFACE || Habitat.onSurface(level, bot.blockPosition()))) {
			explorer.look(bot.blockPosition()); // passing underneath, through the rock, it hasn't seen what's up there
		}
		if (phaseTicks % 40 == 0 && nearestPrey(bot, SCAN_RADIUS) != null) {
			bot.movement().stop();
			setPhase(Phase.FIND);
			return BotTask.Status.RUNNING;
		}
		MovementController.Status status = bot.movement().status();
		boolean over = !farTrip && explorer.timeUp();
		if (status == MovementController.Status.ARRIVED || over) {
			if (farTrip) {
				farTrip = false;
				explorers.remove(item);
				explorer = null;
			} else {
				explorer.leavePlace();
			}
			bot.movement().stop();
			setPhase(Phase.FIND);
		} else if (status == MovementController.Status.FAILED || status == MovementController.Status.IDLE) {
			bot.log("去不了那里（" + bot.movement().failReason() + "），换一处");
			explorer.leavePlace();
			farTrip = false;
			setPhase(Phase.FIND);
		}
		return BotTask.Status.RUNNING;
	}

	/** What each biome of this dimension is worth looking for this prey in (animals if any of it are; then monsters). */
	private Map<Holder<Biome>, Double> worth(ServerLevel level) {
		if (biomeWorth == null) {
			biomeWorth = new HashMap<>();
			boolean animals = anyAnimals(level);
			for (Holder<Biome> b : level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
				biomeWorth.put(b, animals ? dropsPerChunk(b) : monsterShare(b));
			}
		}
		return biomeWorth;
	}

	private Map<Holder<Biome>, Double> biomeWorth;

	/** Of the monsters that spawn in this biome, the share that's prey, by the drops they give. */
	private double monsterShare(Holder<Biome> biome) {
		var entries = biome.value().getMobSettings().getMobs(MobCategory.MONSTER).unwrap();
		int total = 0;
		double prey = 0;
		for (Weighted<MobSpawnSettings.SpawnerData> entry : entries) {
			total += entry.weight();
			prey += entry.weight() * sources.getOrDefault(entry.value().type(), 0.0);
		}
		return total > 0 ? prey / total : 0;
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
		bot.movement().goTo(Habitat.arrived(xz, 16, l), Habitat.target(level, xz, l, bot.blockPosition()), mode,
				!Habitat.heightKnown(l));
	}

	// ---- waiting for spawns -------------------------------------------------------------

	private BotTask.Status startWait(BotPlayer bot) {
		waitedAlready = true;
		waited = 0;
		nightWaited = 0;
		bot.movement().stop();
		bot.say(requester, Component.literal("附近没有").append(names())
				.append("，在这里等它们刷出来（怪物只在黑暗处刷新，白天在露天就等到天黑）。"));
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
			// What it dropped lies around now: a source like the next mob (see find).
			bot.movement().stop();
			setPhase(Phase.FIND);
			return BotTask.Status.RUNNING;
		}
		int depth = waterOver(bot.level(), target);
		if (depth > 0 && !withinBreath(bot, depth)) {
			bot.log(target.getType().getDescription().getString() + " 游到了水下 " + depth + " 格，一口气够不着，换一只");
			target = null;
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
		com.vale.helperbot.path.PathGoal inReach = com.vale.helperbot.bot.Sight.attackSpot(bot, prey);
		BlockPos at = prey.blockPosition();
		if (bot.movement().provenFrom() != null) {
			bot.log(target.getType().getDescription().getString() + " 到不了，换一只");
			rejected.add(target.getUUID());
			target = null;
			bot.movement().stop();
			setPhase(Phase.FIND);
			return BotTask.Status.RUNNING;
		}
		if (bot.movement().status() != MovementController.Status.MOVING) {
			if (aimedAt == null) {
				bot.movement().goTo(inReach, at, mode);
			} else {
				bot.movement().follow(inReach, at, mode);
			}
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
			case LOCATE -> "打猎（" + what + "）：看哪里有它们";
			case EXPLORE -> "打猎（" + what + "）：去 " + (waypoint == null ? "?" : waypoint.getX() + ", " + waypoint.getZ())
					+ " 找（已探索 " + (explorer == null ? 0 : explorer.seconds()) + " 秒）";
			case WAIT -> "打猎（" + what + "）：等刷新（已等 " + waited / 20 + " 秒）";
			case WAY -> "打猎（" + what + "）：算能不能到";
			case CHASE -> "打猎（" + what + "）：追击 " + (target == null ? "?" : target.getType().getDescription().getString());
			case FETCH -> "打猎（" + what + "）：捡地上的";
			case HEAL -> "打猎（" + what + "）：血量低，回血中";
		};
	}

	void onEnd(BotPlayer bot) {
		if (locating != null) {
			locating.cancel(false);
		}
	}
}
