package com.vale.helperbot.knowledge;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which naturally spawning mobs drop what, worked out by rolling each mob's real loot table as if
 * the bot had killed it (so "killed by player" drops like blaze rods count), and where they spawn
 * (from each biome's spawn list). Nothing is written per mob.
 */
public final class MobDrops {
	private static final int TRIALS = 400;

	/** Never hunted, whatever they drop: villagers and other mobs players care about, bosses. */
	private static final Set<EntityType<?>> PROTECTED = Set.of(
			EntityType.VILLAGER, EntityType.WANDERING_TRADER, EntityType.IRON_GOLEM, EntityType.SNOW_GOLEM,
			EntityType.ALLAY, EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.WARDEN,
			EntityType.ELDER_GUARDIAN, EntityType.PLAYER);

	/** item -> mob type -> average number dropped per kill. */
	private static Map<Item, Map<EntityType<?>, Double>> index;
	/** mob type -> biomes of this dimension it spawns in naturally. */
	private static Map<EntityType<?>, Set<Holder<Biome>>> spawnBiomes;
	/** mob type -> how common it is among its kind where it's most common (see #commonness). */
	private static Map<EntityType<?>, Double> commonness;

	private MobDrops() {
	}

	public static void clearCache() {
		index = null;
		spawnBiomes = null;
		commonness = null;
	}

	/** Mobs that drop {@code item} when killed by the bot, with drops per kill. */
	public static Map<EntityType<?>, Double> sources(ServerLevel level, Player killer, Item item) {
		build(level, killer);
		return index.getOrDefault(item, Map.of());
	}

	public static Set<Holder<Biome>> spawnBiomes(ServerLevel level, EntityType<?> type) {
		buildSpawns(level);
		return spawnBiomes.getOrDefault(type, Set.of());
	}

	/**
	 * How often this mob turns up next to the others of its kind (monsters, animals...), in the
	 * biome where it's most common, from the biomes' spawn weights: 1 for one of average weight,
	 * more for the common ones, less for the rare. In plains a witch has weight 5 of 515 among
	 * nine monsters: about 0.09, one turning up for every eleven of an average monster. 1 if it
	 * doesn't spawn naturally.
	 */
	public static double commonness(ServerLevel level, EntityType<?> type) {
		buildSpawns(level);
		return commonness.getOrDefault(type, 1.0);
	}

	/** May the bot hunt this particular mob? No pets, named or leashed mobs, babies or protected kinds. */
	public static boolean mayHunt(Mob mob) {
		return !PROTECTED.contains(mob.getType())
				&& !mob.hasCustomName()
				&& !mob.isLeashed()
				&& !mob.isBaby()
				&& !(mob instanceof TamableAnimal pet && pet.isTame())
				&& !mob.isPersistenceRequired();
	}

	private static void build(ServerLevel level, Player killer) {
		if (index != null) {
			return;
		}
		Map<Item, Map<EntityType<?>, Double>> built = new HashMap<>();
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			if (type.getCategory() == MobCategory.MISC || PROTECTED.contains(type) || type.getDefaultLootTable().isEmpty()) {
				continue;
			}
			Entity entity;
			try {
				entity = type.create(level, EntitySpawnReason.LOAD);
			} catch (RuntimeException e) {
				continue;
			}
			if (!(entity instanceof Mob)) {
				continue;
			}
			LootTable table = level.getServer().reloadableRegistries().getLootTable(type.getDefaultLootTable().get());
			Map<Item, Integer> counts = new HashMap<>();
			try {
				LootParams params = new LootParams.Builder(level)
						.withParameter(LootContextParams.THIS_ENTITY, entity)
						.withParameter(LootContextParams.ORIGIN, killer.position())
						.withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().playerAttack(killer))
						.withOptionalParameter(LootContextParams.ATTACKING_ENTITY, killer)
						.withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, killer)
						.withOptionalParameter(LootContextParams.LAST_DAMAGE_PLAYER, killer)
						.create(LootContextParamSets.ENTITY);
				for (int i = 0; i < TRIALS; i++) {
					for (ItemStack drop : table.getRandomItems(params)) {
						if (!drop.isEmpty()) {
							counts.merge(drop.getItem(), drop.getCount(), Integer::sum);
						}
					}
				}
			} catch (RuntimeException e) {
				continue; // a (modded) table needing context we can't fake
			} finally {
				entity.discard();
			}
			for (Map.Entry<Item, Integer> e : counts.entrySet()) {
				built.computeIfAbsent(e.getKey(), k -> new HashMap<>()).put(type, (double) e.getValue() / TRIALS);
			}
		}
		index = built;
	}

	private static void buildSpawns(ServerLevel level) {
		if (spawnBiomes != null) {
			return;
		}
		Map<EntityType<?>, Set<Holder<Biome>>> built = new HashMap<>();
		Map<EntityType<?>, Double> common = new HashMap<>();
		for (Holder<Biome> biome : level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
			MobSpawnSettings settings = biome.value().getMobSettings();
			for (MobCategory category : MobCategory.values()) {
				var entries = settings.getMobs(category).unwrap();
				int total = 0;
				for (Weighted<MobSpawnSettings.SpawnerData> entry : entries) {
					total += entry.weight();
				}
				for (Weighted<MobSpawnSettings.SpawnerData> entry : entries) {
					built.computeIfAbsent(entry.value().type(), k -> new HashSet<>()).add(biome);
					if (total > 0) {
						// Its share, against the share of one of average weight (1 / how many kinds).
						double c = (double) entry.weight() * entries.size() / total;
						common.merge(entry.value().type(), c, Math::max);
					}
				}
			}
		}
		commonness = common;
		spawnBiomes = built;
	}
}
