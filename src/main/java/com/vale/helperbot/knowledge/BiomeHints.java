package com.vale.helperbot.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.vale.helperbot.HelperBot;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How much of each block a chunk of each biome of this dimension has, by how the game makes it:
 * worked out from the world-generation data, so other mods' biomes and features count the same as
 * vanilla's as long as they use the game's own kinds of feature. Four sources:
 *
 * <ul>
 * <li><b>Features</b> (ores, trees, flowers, ice...): how often per chunk they're placed, from
 * their placement (counts, one-in-N chances, noise-based counts), times what one placement puts
 * down: an ore's size, a patch's tries, a tree's trunk and leaves, a disk's area. A block only
 * counts where it's placed: the same id where it's only a condition ("a spring may sit in packed
 * ice", "an ore replaces stone") doesn't make a biome have it. That's what made packed ice look
 * like it was in 53 biomes, when it's in icebergs and ice spikes. Features whose shape is all code
 * (icebergs, ice spikes, huge mushrooms) count one block a placement: how often is right, how big
 * is too small, and they're still found. A feature type named like a block (seagrass, kelp,
 * bamboo) puts down that block.
 * <li><b>Surface rules</b>: the top of the ground, by biome (sand in deserts, terracotta and red
 * sand in badlands, mycelium on mushroom islands): a whole chunk's worth, the surface being made of it.
 * <li><b>Freezing</b>: cold biomes get snow on top, and ice on their water if they're oceans or
 * rivers (from each biome's temperature: under 0.15, as the game freezes it).
 * <li><b>The sea</b>: oceans and rivers lie under the dimension's sea level, filled with its
 * default fluid (from its generator settings).
 * </ul>
 *
 * <p>Built once per dimension. It says what a biome has, on average: what a particular place has,
 * the bot finds by going, like a player.
 */
public final class BiomeHints {
	/** A chunk's columns: what covers the whole surface. */
	private static final double WHOLE_CHUNK = 256;
	/** Water freezes, and snow settles, below this temperature (vanilla). */
	private static final float FREEZING = 0.15f;
	/**
	 * Keys whose blocks are conditions, not what's put down. ("blocks" isn't one: as a condition it
	 * only names blocks, never states, so it adds nothing; and it's where a geode keeps its layers.)
	 */
	private static final Set<String> CONDITIONS = Set.of("target", "predicate", "valid_blocks", "can_place_on", "can_be_placed_on",
			"replaceable", "match", "can_place_below", "cannot_replace", "allowed_blocks", "placed_on", "rule_test",
			"location_predicate", "input_predicate", "position_predicate", "target_state");

	private record Index(Map<Block, Map<Holder<Biome>, Double>> perChunk, Map<Block, Set<GenerationStep.Decoration>> steps,
						 Set<Block> onSurface) {
	}

	private static final Map<Identifier, Index> INDEXES = new HashMap<>();

	private BiomeHints() {
	}

	public static void clearCache() {
		INDEXES.clear();
	}

	/** Biomes of this level that generate any of {@code blocks}; empty if none or unknown. */
	public static Set<Holder<Biome>> biomesFor(ServerLevel level, Collection<Block> blocks) {
		return perChunk(level, blocks).keySet();
	}

	/** On average, how many of these blocks one chunk of each biome has (biomes with none left out). */
	public static Map<Holder<Biome>, Double> perChunk(ServerLevel level, Collection<Block> blocks) {
		Map<Holder<Biome>, Double> total = new HashMap<>();
		Index index = index(level);
		for (Block block : blocks) {
			index.perChunk.getOrDefault(block, Map.of()).forEach((biome, n) -> total.merge(biome, n, Double::sum));
		}
		return total;
	}

	/** The most of these any biome has per chunk, 0 if none. */
	public static double richest(ServerLevel level, Collection<Block> blocks) {
		return perChunk(level, blocks).values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
	}

	/**
	 * Which layer what places any of {@code blocks} decorates: trees, flowers and the surface are on
	 * top, ores and cave growth underground (from the generation step each feature belongs to). ANY
	 * if both, or if nothing places them.
	 */
	public static Habitat.Layer layerFor(ServerLevel level, Collection<Block> blocks) {
		Index index = index(level);
		boolean surface = false, underground = false;
		for (Block block : blocks) {
			if (index.onSurface.contains(block)) {
				surface = true;
			}
			for (GenerationStep.Decoration step : index.steps.getOrDefault(block, Set.of())) {
				switch (step) {
					case UNDERGROUND_ORES, UNDERGROUND_DECORATION, UNDERGROUND_STRUCTURES, FLUID_SPRINGS -> underground = true;
					default -> surface = true;
				}
			}
		}
		return Habitat.in(level, surface == underground ? Habitat.Layer.ANY : surface ? Habitat.Layer.SURFACE : Habitat.Layer.UNDERGROUND);
	}

	/** For checking it against the world: every block it knows of, the biomes and how many per chunk. */
	public static Map<Block, Map<Holder<Biome>, Double>> table(ServerLevel level) {
		return index(level).perChunk;
	}

	// ---- reading the data -------------------------------------------------------------------

	private static Index index(ServerLevel level) {
		Identifier dimension = level.dimension().identifier();
		Index known = INDEXES.get(dimension);
		if (known == null) {
			long start = System.nanoTime();
			known = new Reader(level).read();
			INDEXES.put(dimension, known);
			HelperBot.LOGGER.info("HelperBot biome index for {}: {} blocks in {} ms", dimension, known.perChunk.size(),
					(System.nanoTime() - start) / 1_000_000);
		}
		return known;
	}

	/** One pass over a dimension's world-generation data. */
	private static final class Reader {
		final ServerLevel level;
		final RegistryOps<JsonElement> ops;
		final Registry<PlacedFeature> placed;
		final Registry<ConfiguredFeature<?, ?>> configured;
		final Map<Identifier, Map<Block, Double>> placedYields = new HashMap<>();
		final Map<Identifier, Map<Block, Double>> configuredYields = new HashMap<>();
		/** Placed and configured features being read (apart: the two often share a name). */
		final Set<Identifier> readingPlaced = new HashSet<>(), readingConfigured = new HashSet<>();
		final Map<Block, Map<Holder<Biome>, Double>> perChunk = new HashMap<>();
		final Map<Block, Set<GenerationStep.Decoration>> steps = new HashMap<>();
		final Set<Block> onSurface = new HashSet<>();

		Reader(ServerLevel level) {
			this.level = level;
			this.ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
			this.placed = level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
			this.configured = level.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE);
		}

		Index read() {
			Set<Holder<Biome>> biomes = level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
			GenerationStep.Decoration[] stepOf = GenerationStep.Decoration.values();
			for (Holder<Biome> biome : biomes) {
				List<HolderSet<PlacedFeature>> features = biome.value().getGenerationSettings().features();
				for (int i = 0; i < features.size(); i++) {
					for (Holder<PlacedFeature> feature : features.get(i)) {
						Map<Block, Double> yield = placedYield(encode(feature));
						GenerationStep.Decoration step = i < stepOf.length ? stepOf[i] : GenerationStep.Decoration.VEGETAL_DECORATION;
						yield.forEach((block, n) -> {
							add(block, biome, n);
							steps.computeIfAbsent(block, b -> new HashSet<>()).add(step);
						});
					}
				}
				freezing(biome);
			}
			surfaceRules(biomes);
			sea(biomes);
			return new Index(perChunk, steps, onSurface);
		}

		/**
		 * The sea: below its sea level the dimension's terrain is filled with its default fluid, and
		 * the biomes the game tags as oceans and rivers are the ones whose ground lies under it, so
		 * their whole surface is that fluid (water in the Overworld). No feature places it, so without
		 * this an ocean had no more water than a desert: the springs and lakes every biome has.
		 */
		private void sea(Set<Holder<Biome>> biomes) {
			if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noise)) {
				return;
			}
			Block fluid = noise.generatorSettings().value().defaultFluid().getBlock();
			if (fluid.defaultBlockState().getFluidState().isEmpty()) {
				return;
			}
			for (Holder<Biome> biome : biomes) {
				if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) {
					add(fluid, biome, WHOLE_CHUNK);
					onSurface.add(fluid);
				}
			}
		}

		private void add(Block block, Holder<Biome> biome, double n) {
			if (n > 0 && !block.defaultBlockState().isAir()) {
				perChunk.computeIfAbsent(block, b -> new HashMap<>()).merge(biome, n, Double::sum);
			}
		}

		private JsonElement encode(Holder<PlacedFeature> feature) {
			return PlacedFeature.DIRECT_CODEC.encodeStart(ops, feature.value()).result().orElse(new JsonObject());
		}

		/** Blocks one chunk gets from a placed feature: how often it's placed, times what each puts down. */
		private Map<Block, Double> placedYield(JsonElement json) {
			if (json.isJsonPrimitive()) {
				Identifier id = Identifier.tryParse(json.getAsString());
				if (id == null || !placed.containsKey(id) || !readingPlaced.add(id)) {
					return Map.of();
				}
				Map<Block, Double> known = placedYields.get(id);
				if (known == null) {
					known = placedYield(PlacedFeature.DIRECT_CODEC.encodeStart(ops, placed.getValue(id)).result().orElse(new JsonObject()));
					placedYields.put(id, known);
				}
				readingPlaced.remove(id);
				return known;
			}
			if (!json.isJsonObject()) {
				return Map.of();
			}
			JsonObject o = json.getAsJsonObject();
			double times = 1;
			if (o.get("placement") instanceof JsonArray placement) {
				for (JsonElement modifier : placement) {
					times *= timesPerChunk(modifier);
				}
			}
			return scale(featureYield(o.get("feature")), times);
		}

		/** What one placement of a configured feature puts down. */
		private Map<Block, Double> featureYield(JsonElement json) {
			if (json == null) {
				return Map.of();
			}
			if (json.isJsonPrimitive()) {
				Identifier id = Identifier.tryParse(json.getAsString());
				if (id == null || !configured.containsKey(id) || !readingConfigured.add(id)) {
					return Map.of();
				}
				Map<Block, Double> known = configuredYields.get(id);
				if (known == null) {
					known = featureYield(ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, configured.getValue(id)).result().orElse(new JsonObject()));
					configuredYields.put(id, known);
				}
				readingConfigured.remove(id);
				return known;
			}
			if (!json.isJsonObject()) {
				return Map.of();
			}
			JsonObject o = json.getAsJsonObject();
			String type = path(o.get("type"));
			JsonObject config = o.get("config") instanceof JsonObject c ? c : new JsonObject();
			Map<Block, Double> out = new HashMap<>();
			switch (type) {
				case "ore", "scattered_ore" -> {
					double size = number(config.get("size"), 1);
					if (config.get("targets") instanceof JsonArray targets && !targets.isEmpty()) {
						for (JsonElement t : targets) {
							if (t instanceof JsonObject target) {
								merge(out, states(target.get("state")), size / targets.size());
							}
						}
					}
				}
				case "random_patch", "flower", "no_bonemeal_flower" ->
						merge(out, placedYield(config.get("feature")), number(config.get("tries"), 1));
				case "random_selector" -> {
					double left = 1;
					if (config.get("features") instanceof JsonArray features) {
						for (JsonElement f : features) {
							if (f instanceof JsonObject entry) {
								double chance = number(entry.get("chance"), 0);
								left -= chance;
								merge(out, placedYield(entry.get("feature")), chance);
							}
						}
					}
					merge(out, placedYield(config.get("default")), Math.max(0, left));
				}
				case "simple_random_selector" -> {
					if (config.get("features") instanceof JsonArray features && !features.isEmpty()) {
						for (JsonElement f : features) {
							merge(out, placedYield(f), 1.0 / features.size());
						}
					}
				}
				case "random_boolean_selector" -> {
					merge(out, placedYield(config.get("feature_true")), 0.5);
					merge(out, placedYield(config.get("feature_false")), 0.5);
				}
				case "tree" -> tree(config, out);
				case "disk" -> {
					double r = number(config.get("radius"), 1), h = number(config.get("half_height"), 1);
					merge(out, states(config.get("state_provider")), Math.PI * r * r * (2 * h + 1));
				}
				case "simple_block" -> merge(out, states(config.get("to_place")), 1);
				case "block_column" -> {
					if (config.get("layers") instanceof JsonArray layers) {
						for (JsonElement l : layers) {
							if (l instanceof JsonObject layer) {
								merge(out, states(layer.get("provider")), number(layer.get("height"), 1));
							}
						}
					}
				}
				case "vegetation_patch", "waterlogged_vegetation_patch" -> {
					double r = number(config.get("xz_radius"), 1), area = (2 * r + 1) * (2 * r + 1);
					merge(out, states(config.get("ground_state")), area * number(config.get("depth"), 1));
					merge(out, placedYield(config.get("vegetation_feature")), area * number(config.get("vegetation_chance"), 0));
				}
				case "fill_layer" -> merge(out, states(config.get("state")), WHOLE_CHUNK * number(config.get("height"), 1));
				// Glow lichen and the like: the block is named, not given as a state.
				case "multiface_growth" -> {
					if (config.get("block") != null && config.get("block").isJsonPrimitive()) {
						BuiltInRegistries.BLOCK.getOptional(Identifier.tryParse(config.get("block").getAsString()))
								.ifPresent(block -> out.merge(block, 1.0, Double::sum));
					}
				}
				default -> {
					// Its shape is code: one block a placement of each block it puts down; and a
					// feature it places (the azalea tree on top of a root system), once.
					merge(out, states(config), 1);
					if (config.get("feature") != null) {
						merge(out, placedYield(config.get("feature")), 1);
					}
					if (o.get("type") != null && o.get("type").isJsonPrimitive()) {
						BuiltInRegistries.BLOCK.getOptional(Identifier.tryParse(o.get("type").getAsString()))
								.ifPresent(block -> out.merge(block, 1.0, Double::sum));
					}
				}
			}
			return out;
		}

		/** A tree: its trunk's average height in logs, its foliage's blocks, and what decorates it. */
		private void tree(JsonObject config, Map<Block, Double> out) {
			JsonObject trunk = config.get("trunk_placer") instanceof JsonObject t ? t : new JsonObject();
			double height = number(trunk.get("base_height"), 4) + number(trunk.get("height_rand_a"), 0) / 2 + number(trunk.get("height_rand_b"), 0) / 2;
			merge(out, states(config.get("trunk_provider")), height);
			JsonObject foliage = config.get("foliage_placer") instanceof JsonObject f ? f : new JsonObject();
			double r = number(foliage.get("radius"), 2), layers = number(foliage.get("height"), number(foliage.get("foliage_height"), 3));
			merge(out, states(config.get("foliage_provider")), (2 * r + 1) * (2 * r + 1) * layers);
			if (config.get("decorators") instanceof JsonArray decorators) {
				for (JsonElement d : decorators) {
					if (d instanceof JsonObject decorator && decorator.get("type") != null && decorator.get("type").isJsonPrimitive()) {
						double chance = number(decorator.get("probability"), 1);
						// Named like a block (cocoa, beehive), or putting down the blocks it says (propagules).
						BuiltInRegistries.BLOCK.getOptional(Identifier.tryParse(decorator.get("type").getAsString()))
								.ifPresent(block -> out.merge(block, chance, Double::sum));
						merge(out, states(decorator), chance);
					}
				}
			}
		}

		/** How many times per chunk a placement modifier makes it: counts, one-in-N chances, noise. */
		private double timesPerChunk(JsonElement json) {
			if (!(json instanceof JsonObject o)) {
				return 1;
			}
			return switch (path(o.get("type"))) {
				case "count", "count_on_every_layer" -> number(o.get("count"), 1);
				case "rarity_filter" -> 1 / Math.max(1, number(o.get("chance"), 1));
				case "noise_threshold_count" -> (number(o.get("below_noise"), 0) + number(o.get("above_noise"), 0)) / 2;
				// Placed where noise plus offset is above zero, more the higher: with the noise taken
				// as spread evenly over -1..1, that's on average (1 + offset)^2 / 4 of the ratio.
				case "noise_based_count" -> {
					double offset = Math.clamp(number(o.get("noise_offset"), 0), -1, 1);
					yield number(o.get("noise_to_count_ratio"), 1) * (1 + offset) * (1 + offset) / 4;
				}
				default -> 1;
			};
		}

		/** The blocks a state, or a state provider, puts down, with the share of each. */
		private Map<Block, Double> states(JsonElement json) {
			Map<Block, Double> out = new HashMap<>();
			collectStates(json, 1, out);
			return out;
		}

		private void collectStates(JsonElement json, double share, Map<Block, Double> out) {
			if (json instanceof JsonArray array) {
				for (JsonElement e : array) {
					collectStates(e, share / Math.max(1, array.size()), out);
				}
				return;
			}
			if (!(json instanceof JsonObject o)) {
				return;
			}
			if (o.get("Name") != null && o.get("Name").isJsonPrimitive()) {
				BuiltInRegistries.BLOCK.getOptional(Identifier.tryParse(o.get("Name").getAsString()))
						.ifPresent(block -> out.merge(block, share, Double::sum));
				return;
			}
			if (o.get("entries") instanceof JsonArray entries) {
				// A weighted list: each by its weight.
				double total = 0;
				for (JsonElement e : entries) {
					total += e instanceof JsonObject entry ? number(entry.get("weight"), 1) : 0;
				}
				for (JsonElement e : entries) {
					if (e instanceof JsonObject entry) {
						collectStates(entry.get("data"), share * number(entry.get("weight"), 1) / Math.max(1e-9, total), out);
					}
				}
				return;
			}
			int parts = 0;
			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				if (!CONDITIONS.contains(e.getKey()) && (e.getValue().isJsonObject() || e.getValue().isJsonArray())) {
					parts++;
				}
			}
			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				if (!CONDITIONS.contains(e.getKey()) && (e.getValue().isJsonObject() || e.getValue().isJsonArray())) {
					collectStates(e.getValue(), share / Math.max(1, parts), out);
				}
			}
		}

		/** Cold biomes: snow over them, and ice over their water if they're seas or rivers. */
		private void freezing(Holder<Biome> biome) {
			if (biome.value().getBaseTemperature() >= FREEZING) {
				return;
			}
			add(Blocks.SNOW, biome, WHOLE_CHUNK);
			onSurface.add(Blocks.SNOW);
			if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) {
				add(Blocks.ICE, biome, WHOLE_CHUNK);
				onSurface.add(Blocks.ICE);
			}
		}

		/** The surface rules: which block tops the ground in which biome. */
		private void surfaceRules(Set<Holder<Biome>> biomes) {
			if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noise)) {
				return;
			}
			var settings = noise.generatorSettings().value();
			JsonElement rules = SurfaceRules.RuleSource.CODEC.encodeStart(ops, settings.surfaceRule()).result().orElse(null);
			Map<Identifier, Holder<Biome>> byId = new HashMap<>();
			biomes.forEach(b -> b.unwrapKey().ifPresent(k -> byId.put(k.identifier(), b)));
			if (rules != null) {
				rule(rules, Set.copyOf(biomes), byId);
			}
			Block ground = settings.defaultBlock().getBlock();
			for (Holder<Biome> biome : biomes) {
				add(ground, biome, WHOLE_CHUNK);
			}
		}

		private void rule(JsonElement json, Set<Holder<Biome>> where, Map<Identifier, Holder<Biome>> byId) {
			if (!(json instanceof JsonObject o) || where.isEmpty()) {
				return;
			}
			switch (path(o.get("type"))) {
				case "sequence" -> {
					if (o.get("sequence") instanceof JsonArray list) {
						list.forEach(r -> rule(r, where, byId));
					}
				}
				case "condition" -> rule(o.get("then_run"), narrow(o.get("if_true"), where, byId), byId);
				case "block" -> states(o.get("result_state")).keySet().forEach(block -> {
					onSurface.add(block);
					where.forEach(biome -> add(block, biome, WHOLE_CHUNK));
				});
				// Badlands bands: coloured terracotta, in layers (a rule of its own, made in code).
				case "bandlands" -> {
					for (Block block : List.of(Blocks.TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.ORANGE_TERRACOTTA,
							Blocks.YELLOW_TERRACOTTA, Blocks.BROWN_TERRACOTTA, Blocks.RED_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA)) {
						onSurface.add(block);
						where.forEach(biome -> add(block, biome, WHOLE_CHUNK));
					}
				}
				default -> {
				}
			}
		}

		/** The biomes a condition leaves: biome conditions narrow it, the rest (depth, noise...) don't say. */
		private Set<Holder<Biome>> narrow(JsonElement condition, Set<Holder<Biome>> where, Map<Identifier, Holder<Biome>> byId) {
			if (!(condition instanceof JsonObject o)) {
				return where;
			}
			String type = path(o.get("type"));
			if (type.equals("biome") && o.get("biome_is") instanceof JsonArray ids) {
				Set<Holder<Biome>> listed = new HashSet<>();
				ids.forEach(id -> {
					Holder<Biome> b = byId.get(Identifier.tryParse(id.getAsString()));
					if (b != null) {
						listed.add(b);
					}
				});
				Set<Holder<Biome>> left = new HashSet<>(where);
				left.retainAll(listed);
				return left;
			}
			if (type.equals("not") && o.get("invert") instanceof JsonObject inner && path(inner.get("type")).equals("biome")) {
				Set<Holder<Biome>> excluded = narrow(inner, where, byId);
				Set<Holder<Biome>> left = new HashSet<>(where);
				left.removeAll(excluded);
				return left;
			}
			return where;
		}

		private static Map<Block, Double> scale(Map<Block, Double> yield, double times) {
			Map<Block, Double> out = new HashMap<>();
			yield.forEach((b, n) -> out.put(b, n * times));
			return out;
		}

		private static void merge(Map<Block, Double> into, Map<Block, Double> add, double times) {
			add.forEach((b, n) -> into.merge(b, n * times, Double::sum));
		}

		private static String path(JsonElement type) {
			if (type == null || !type.isJsonPrimitive()) {
				return "";
			}
			String s = type.getAsString();
			return s.substring(s.indexOf(':') + 1);
		}

		/** A number, or the average of an int or float provider (uniform, weighted, clamped...). */
		private static double number(JsonElement json, double otherwise) {
			if (json == null) {
				return otherwise;
			}
			if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
				return json.getAsDouble();
			}
			if (!(json instanceof JsonObject o)) {
				return otherwise;
			}
			if (o.get("value") != null) {
				return number(o.get("value"), otherwise);
			}
			if (o.get("source") != null) {
				return number(o.get("source"), otherwise);
			}
			if (o.get("distribution") instanceof JsonArray list) {
				double total = 0, sum = 0;
				for (JsonElement e : list) {
					if (e instanceof JsonObject entry) {
						double w = number(entry.get("weight"), 1);
						total += w;
						sum += w * number(entry.get("data"), 0);
					}
				}
				return total > 0 ? sum / total : otherwise;
			}
			JsonElement min = o.get("min_inclusive") != null ? o.get("min_inclusive") : o.get("min");
			JsonElement max = o.get("max_inclusive") != null ? o.get("max_inclusive") : o.get("max");
			if (min != null && max != null) {
				return (number(min, 0) + number(max, 0)) / 2;
			}
			if (o.get("mean") != null) {
				return number(o.get("mean"), otherwise);
			}
			return otherwise;
		}
	}
}
