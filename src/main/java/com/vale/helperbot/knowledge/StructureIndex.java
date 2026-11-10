package com.vale.helperbot.knowledge;

import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;
import com.vale.helperbot.HelperBot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the world's structures hold, worked out from the game's own data, so structures of other
 * mods and data packs count the same as vanilla's. Nothing is written per structure:
 *
 * <ul>
 * <li>Jigsaw structures (villages, bastions, trial chambers, most modded ones) name their pools,
 * and pools name their templates, which name further pools: followed all the way, that's every
 * piece a structure can have. A template lists its blocks, and its spawners and mobs.
 * <li>The older structures are built by code, which can't be read. Their parts go by name: all
 * the words of the structure's id in the name of a template folder ({@code mansion} and
 * {@code woodland_mansion/...}), and structures of one structure set are
 * variants of each other ({@code shipwreck}, {@code shipwreck_beached}). A name that doesn't
 * match is left out: a place it doesn't know beats sending it to the wrong one.
 * <li>Mobs also come from each structure's own spawn lists.
 * </ul>
 *
 * <p>What's in chests isn't counted on: it's a draw, and nearly all of it can be made.
 */
public final class StructureIndex {
	private static final Pattern ID = Pattern.compile("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+");
	/** Most chunks searched around a point for the nearest structure. */
	private static final int SEARCH_CHUNKS = 200;
	/** With no regular spacing to go by (strongholds, placed in rings). */
	private static final int UNKNOWN_DISTANCE = 1500;

	/** What one structure can have. */
	private static final class Contents {
		final Set<Block> blocks = new HashSet<>();
		/** Mobs its templates name. */
		final Set<EntityType<?>> mobs = new HashSet<>();
		/** Mobs its own spawn rules bring (a fortress's blazes): its alone, never shared with the rest of its set. */
		final Set<EntityType<?>> spawned = new HashSet<>();
	}

	private static Map<Holder<Structure>, Contents> contents;

	private StructureIndex() {
	}

	public static void clearCache() {
		contents = null;
	}

	public static void warmUp(ServerLevel level) {
		build(level);
	}

	/** Structures of this world built with any of these blocks. */
	public static Set<Holder<Structure>> holding(ServerLevel level, Collection<Block> blocks) {
		build(level);
		Set<Holder<Structure>> found = new HashSet<>();
		for (Holder<Structure> structure : present(level)) {
			Contents c = contents.get(structure);
			if (c != null && blocks.stream().anyMatch(c.blocks::contains)) {
				found.add(structure);
			}
		}
		return found;
	}

	/** Structures of this world where this mob is found. */
	public static Set<Holder<Structure>> spawning(ServerLevel level, EntityType<?> type) {
		build(level);
		Set<Holder<Structure>> found = new HashSet<>();
		for (Holder<Structure> structure : present(level)) {
			Contents c = contents.get(structure);
			if (c != null && (c.mobs.contains(type) || c.spawned.contains(type))) {
				found.add(structure);
			}
		}
		return found;
	}

	/**
	 * About how far the nearest of these is, in blocks: from how the game places them. One may
	 * start in each cell of a grid (half a cell away), but only so many of the cells turn into one:
	 * those that come up in its chance, and whose spot is in a biome it can stand in. It's the
	 * typical distance of the game's spread, not of this world: looking is for setting off.
	 */
	public static int typicalDistance(ServerLevel level, Collection<Holder<Structure>> structures) {
		double best = Double.POSITIVE_INFINITY;
		var biomes = level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
		for (Holder<Structure> structure : structures) {
			long here = structure.value().biomes().stream().filter(biomes::contains).count();
			double share = (double) Math.max(1, here) / Math.max(1, biomes.size());
			for (var placement : level.getChunkSource().getGeneratorState().getPlacementsForStructure(structure)) {
				if (placement instanceof RandomSpreadStructurePlacement spread) {
					best = Math.min(best, spread.spacing() * 16 / 2.0 / Math.sqrt(frequency(level, spread) * share));
				}
			}
		}
		return Double.isInfinite(best) ? UNKNOWN_DISTANCE : (int) best;
	}

	/** The share of the grid's cells that get one: the game keeps it to itself, its data says it. */
	private static double frequency(ServerLevel level, net.minecraft.world.level.levelgen.structure.placement.StructurePlacement placement) {
		return net.minecraft.world.level.levelgen.structure.placement.StructurePlacement.CODEC
				.encodeStart(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), placement).result()
				.filter(JsonElement::isJsonObject).map(j -> j.getAsJsonObject().get("frequency"))
				.map(JsonElement::getAsDouble).orElse(1.0);
	}

	/**
	 * The nearest of these from {@code from}, not one of {@code seen}, or null. Like /locate:
	 * it takes a moment, so it's for when it's setting off, not for planning.
	 */
	public static BlockPos nearest(ServerLevel level, Collection<Holder<Structure>> structures, BlockPos from, Collection<BlockPos> seen) {
		if (structures.isEmpty()) {
			return null;
		}
		HolderSet<Structure> set = HolderSet.direct(new ArrayList<>(structures));
		int step = Math.max(512, typicalDistance(level, structures));
		// Asked again from further along: the game only answers with the nearest.
		int[][] sides = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
		for (int[] side : sides) {
			Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator().findNearestMapStructure(
					level, set, from.offset(side[0] * step, 0, side[1] * step), SEARCH_CHUNKS, false);
			if (found != null && seen.stream().noneMatch(p -> horizontal(p, found.getFirst()) < 64 * 64)) {
				return found.getFirst();
			}
		}
		return null;
	}

	private static long horizontal(BlockPos a, BlockPos b) {
		long dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	/** The structures this level really generates (a flat world, or the other dimensions', don't count). */
	private static Set<Holder<Structure>> present(ServerLevel level) {
		Set<Holder<Structure>> found = new HashSet<>();
		for (Holder<StructureSet> set : level.getChunkSource().getGeneratorState().possibleStructureSets()) {
			for (StructureSet.StructureSelectionEntry entry : set.value().structures()) {
				found.add(entry.structure());
			}
		}
		return found;
	}

	// ---- reading the game's data ------------------------------------------------------------

	private static void build(ServerLevel level) {
		if (contents != null) {
			return;
		}
		long start = System.nanoTime();
		var server = level.getServer();
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
		Set<Identifier> templateIds = new HashSet<>();
		StructureTemplateManager templates = server.getStructureManager();
		templates.listTemplates().forEach(templateIds::add);
		Registry<StructureTemplatePool> pools = level.registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL);
		var processors = level.registryAccess().lookupOrThrow(Registries.PROCESSOR_LIST);
		Map<Identifier, Set<Identifier>> processorIdsIn = new HashMap<>();
		Map<Identifier, Set<Identifier>> templateIdsIn = new HashMap<>();
		Map<Identifier, Set<Identifier>> poolIdsIn = new HashMap<>();

		Map<Holder<Structure>, Contents> built = new HashMap<>();
		Registry<Structure> structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		structures.listElements().forEach(holder -> {
			Contents c = new Contents();
			built.put(holder, c);
			readSpawnLists(holder.value(), c);
			// Pools and templates, followed from the structure's own definition. Five villages
			// share most of theirs: each is read once, what it names is what's kept.
			ArrayDeque<Identifier> queue = new ArrayDeque<>(ids(Structure.DIRECT_CODEC.encodeStart(ops, holder.value()).result()
					.map(JsonElement::toString).orElse("")));
			Set<Identifier> seen = new HashSet<>();
			while (!queue.isEmpty()) {
				Identifier id = queue.poll();
				if (!seen.add(id)) {
					continue;
				}
				if (pools.containsKey(id)) {
					Set<Identifier> named = poolIdsIn.computeIfAbsent(id, i -> ids(StructureTemplatePool.DIRECT_CODEC.encodeStart(ops, pools.getValue(i))
							.result().map(JsonElement::toString).orElse("")));
					queue.addAll(named);
					// Blocks a pool's own processors put in (written out in the pool itself).
					readTemplate(named, c);
				}
				// What processors swap in: a village farm's template is all wheat, and its processor
				// list turns some of it into carrots, potatoes and beetroots.
				if (processors.containsKey(id)) {
					Set<Identifier> named = processorIdsIn.computeIfAbsent(id, i -> ids(
							net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType.DIRECT_CODEC
									.encodeStart(ops, processors.getValue(i)).result().map(JsonElement::toString).orElse("")));
					readTemplate(named, c);
				}
				if (templateIds.contains(id)) {
					Set<Identifier> named = templateIdsIn.computeIfAbsent(id, i -> read(templates, i));
					queue.addAll(named);
					readTemplate(named, c);
				}
			}
		});
		matchByName(level, built, templateIds, templates, templateIdsIn);
		contents = built;
		HelperBot.LOGGER.info("HelperBot structure index: {} structures, {} templates read, {} ms",
				built.size(), templateIdsIn.size(), (System.nanoTime() - start) / 1_000_000);
	}

	private static void readSpawnLists(Structure structure, Contents c) {
		for (StructureSpawnOverride override : structure.spawnOverrides().values()) {
			for (Weighted<MobSpawnSettings.SpawnerData> entry : override.spawns().unwrap()) {
				c.spawned.add(entry.value().type());
			}
		}
	}

	/** The ids named in a template: its palette, what its blocks carry, its mobs. */
	private static Set<Identifier> read(StructureTemplateManager templates, Identifier id) {
		Set<Identifier> found = new HashSet<>();
		try {
			var template = templates.get(id);
			if (template.isEmpty()) {
				return found;
			}
			CompoundTag tag = template.get().save(new CompoundTag());
			for (String part : List.of(StructureTemplate.PALETTE_TAG, StructureTemplate.PALETTE_LIST_TAG, StructureTemplate.ENTITIES_TAG)) {
				collect(tag.get(part), found);
			}
			if (tag.get(StructureTemplate.BLOCKS_TAG) instanceof ListTag blocks) {
				for (Tag block : blocks) {
					if (block instanceof CompoundTag c) {
						collect(c.get(StructureTemplate.BLOCK_TAG_NBT), found);
					}
				}
			}
		} catch (RuntimeException e) {
			// a template that doesn't load isn't worth failing the rest over
		}
		return found;
	}

	private static void collect(Tag tag, Set<Identifier> found) {
		if (tag instanceof CompoundTag compound) {
			compound.keySet().forEach(key -> collect(compound.get(key), found));
		} else if (tag instanceof ListTag list) {
			list.forEach(element -> collect(element, found));
		} else if (tag instanceof StringTag text) {
			String value = text.value();
			if (value.indexOf(':') > 0) {
				Identifier id = Identifier.tryParse(value);
				if (id != null) {
					found.add(id);
				}
			}
		}
	}

	/** The ids written in this text. */
	private static Set<Identifier> ids(String text) {
		Set<Identifier> found = new HashSet<>();
		Matcher m = ID.matcher(text);
		while (m.find()) {
			Identifier id = Identifier.tryParse(m.group());
			if (id != null) {
				found.add(id);
			}
		}
		return found;
	}

	private static void readTemplate(Set<Identifier> named, Contents c) {
		for (Identifier id : named) {
			BuiltInRegistries.BLOCK.getOptional(id).filter(b -> !b.defaultBlockState().isAir()).ifPresent(c.blocks::add);
			BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresent(type -> {
				if (type.getCategory() != net.minecraft.world.entity.MobCategory.MISC) {
					c.mobs.add(type);
				}
			});
		}
	}

	/**
	 * The structures code builds: by name (see the class comment). Variants in one structure set
	 * share what their templates have; not their spawn rules. (The Nether's fortress and bastion
	 * share a set, one or the other at each spot: the bastion had the fortress's wither skeletons
	 * and blazes, and it went to a bastion for them.)
	 */
	private static void matchByName(ServerLevel level, Map<Holder<Structure>, Contents> built,
									Set<Identifier> templateIds, StructureTemplateManager templates, Map<Identifier, Set<Identifier>> templateIdsIn) {
		built.forEach((holder, c) -> {
			Identifier id = holder.unwrapKey().map(ResourceKey::identifier).orElse(null);
			if (id == null) {
				return;
			}
			Set<String> words = Set.of(id.getPath().split("_"));
			if (c.blocks.isEmpty()) {
				for (Identifier template : templateIds) {
					String folder = template.getPath().split("/")[0];
					if (template.getNamespace().equals(id.getNamespace()) && words(folder).containsAll(words)) {
						readTemplate(templateIdsIn.computeIfAbsent(template, i -> read(templates, i)), c);
					}
				}
			}
		});
		level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET).listElements().forEach(set -> {
			Contents shared = new Contents();
			for (StructureSet.StructureSelectionEntry entry : set.value().structures()) {
				Contents c = built.get(entry.structure());
				if (c != null) {
					shared.blocks.addAll(c.blocks);
					shared.mobs.addAll(c.mobs);
				}
			}
			for (StructureSet.StructureSelectionEntry entry : set.value().structures()) {
				Contents c = built.get(entry.structure());
				if (c != null) {
					c.blocks.addAll(shared.blocks);
					c.mobs.addAll(shared.mobs);
				}
			}
		});
	}

	private static Set<String> words(String path) {
		return new HashSet<>(Arrays.asList(path.split("[_/]")));
	}
}
