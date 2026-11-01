package com.vale.helperbot.knowledge;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Which biomes of this dimension generate a block as part of their features (trees, flowers,
 * ores, coral...). Each feature's configuration is serialised to JSON and searched for the
 * block's id, which works for any feature type, including other mods'.
 *
 * <p>Blocks placed by terrain/surface rules (sand, terracotta...) or only by structures aren't
 * found this way.
 */
public final class BiomeHints {
	private static final Map<ConfiguredFeature<?, ?>, String> FEATURE_JSON = new IdentityHashMap<>();

	private BiomeHints() {
	}

	public static void clearCache() {
		FEATURE_JSON.clear();
	}

	/** Biomes of this level that generate any of {@code blocks}; empty if none or unknown. */
	public static Set<Holder<Biome>> biomesFor(ServerLevel level, Collection<Block> blocks) {
		Set<String> ids = new HashSet<>();
		for (Block block : blocks) {
			ids.add("\"" + BuiltInRegistries.BLOCK.getKey(block) + "\"");
		}
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
		Set<Holder<Biome>> result = new HashSet<>();
		for (Holder<Biome> biome : level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
			if (generatesAny(biome, ids, ops)) {
				result.add(biome);
			}
		}
		return result;
	}

	private static boolean generatesAny(Holder<Biome> biome, Set<String> ids, RegistryOps<JsonElement> ops) {
		for (HolderSet<PlacedFeature> step : biome.value().getGenerationSettings().features()) {
			for (Holder<PlacedFeature> placed : step) {
				boolean hit = placed.value().getFeatures().anyMatch(feature -> {
					String json = FEATURE_JSON.computeIfAbsent(feature, f -> ConfiguredFeature.DIRECT_CODEC
							.encodeStart(ops, f).result().map(JsonElement::toString).orElse(""));
					for (String id : ids) {
						if (json.contains(id)) {
							return true;
						}
					}
					return false;
				});
				if (hit) {
					return true;
				}
			}
		}
		return false;
	}
}
