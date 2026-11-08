package com.vale.helperbot.knowledge;

import com.vale.helperbot.HelperBot;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where each item can be had from, for the remote's item list: the Overworld alone, or only with
 * the Nether, or only with the End, or not at all. The same reckoning as the planner's "out in the
 * world" (see CraftPlanner#onlyInStructures), once for each set of dimensions: what each one's
 * biomes and structures have and what its mobs drop, then what recipes, the furnace and using
 * things (see Interactions) make of those, over and over until nothing more is added. A block only
 * counts if a plain tool already counted (or the hand) gets it: silk touch never does.
 *
 * <p>It says where things come from, not that a plan will find them from where the bot is.
 */
public final class WhereFrom {
	public enum Place {
		OVERWORLD, NETHER, END, NONE
	}

	private static Map<Item, Place> places;
	/** Of those with a place, the ones that need a silk touch tool given to the bot (directly or for what they're made from). */
	private static Set<Item> silk;

	private WhereFrom() {
	}

	public static void clearCache() {
		places = null;
		silk = null;
	}

	public static Map<Item, Place> all(MinecraftServer server) {
		if (places == null) {
			long start = System.nanoTime();
			ServerLevel overworld = server.overworld();
			Map<Item, Place> plain = build(server, false), withSilk = build(server, true);
			Map<Item, Place> out = new HashMap<>();
			Set<Item> needSilk = new HashSet<>();
			// Without silk touch where it can, with it only where there's no other way: it can't make
			// one, the player has to give it (see CraftPlanner#ensureGatherTool).
			for (Item item : BuiltInRegistries.ITEM) {
				Place p = plain.get(item);
				if (p == Place.NONE && withSilk.get(item) != Place.NONE && Obtainability.refusal(overworld, item) == null) {
					p = withSilk.get(item);
					needSilk.add(item);
				}
				out.put(item, p);
			}
			places = out;
			silk = needSilk;
			HelperBot.LOGGER.info("HelperBot item origins: {} items ({} with silk touch), {} ms", out.size(), needSilk.size(),
					(System.nanoTime() - start) / 1_000_000);
		}
		return places;
	}

	/** Those of #all that need a silk touch tool. */
	public static Set<Item> needSilk(MinecraftServer server) {
		all(server);
		return silk;
	}

	private static Map<Item, Place> build(MinecraftServer server, boolean silkTouch) {
		ServerLevel overworld = server.overworld(), nether = server.getLevel(Level.NETHER), end = server.getLevel(Level.END);
		Set<Item> o = reach(overworld, List.of(overworld), silkTouch);
		Set<Item> on = nether == null ? o : reach(overworld, List.of(overworld, nether), silkTouch);
		Set<Item> oe = end == null ? o : reach(overworld, List.of(overworld, end), silkTouch);
		Set<Item> all = nether == null || end == null ? new HashSet<>(on) : reach(overworld, List.of(overworld, nether, end), silkTouch);
		all.addAll(oe);
		Map<Item, Place> out = new HashMap<>();
		for (Item item : BuiltInRegistries.ITEM) {
			Place p;
			if (Obtainability.refusal(overworld, item) != null) {
				p = Place.NONE;
			} else if (o.contains(item)) {
				p = Place.OVERWORLD;
			} else if (on.contains(item)) {
				p = Place.NETHER;
			} else if (all.contains(item)) {
				p = Place.END; // the End alone, or the End and the Nether both
			} else {
				p = Place.NONE;
			}
			out.put(item, p);
		}
		return out;
	}

	/** What these dimensions give, between them; with {@code silkTouch}, a block that drops it only to silk touch counts too. */
	private static Set<Item> reach(ServerLevel overworld, List<ServerLevel> levels, boolean silkTouch) {
		FakePlayer player = FakePlayer.get(overworld);
		// Mobs living there: their biomes' spawn lists (each dimension's own), and its structures.
		Set<EntityType<?>> mobs = new HashSet<>();
		for (ServerLevel level : levels) {
			for (Holder<Biome> biome : level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
				MobSpawnSettings settings = biome.value().getMobSettings();
				for (MobCategory category : MobCategory.values()) {
					for (Weighted<MobSpawnSettings.SpawnerData> entry : settings.getMobs(category).unwrap()) {
						mobs.add(entry.value().type());
					}
				}
			}
			for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
				if (!StructureIndex.spawning(level, type).isEmpty()) {
					mobs.add(type);
				}
			}
		}
		Map<Block, Boolean> blockThere = new HashMap<>();
		Set<Item> yes = new HashSet<>();
		List<Item> rest = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (Obtainability.refusal(overworld, item) == null) {
				rest.add(item);
			}
		}
		for (boolean more = true; more; ) {
			more = false;
			for (java.util.Iterator<Item> it = rest.iterator(); it.hasNext(); ) {
				Item item = it.next();
				if (gets(item, overworld, player, levels, mobs, blockThere, yes, silkTouch)) {
					yes.add(item);
					it.remove();
					more = true;
				}
			}
		}
		return yes;
	}

	private static boolean gets(Item item, ServerLevel overworld, FakePlayer player, List<ServerLevel> levels,
								Set<EntityType<?>> mobs, Map<Block, Boolean> blockThere, Set<Item> yes, boolean silkTouch) {
		// Mined: a block there that drops it by hand, or with a plain tool it can have already; with
		// silkTouch, also one no plain tool gets it from (that's silk touch: it's what's left).
		for (Block block : DropAnalysis.candidates(overworld, player, item).keySet()) {
			if (!blockThere.computeIfAbsent(block, b -> there(b, levels))) {
				continue;
			}
			if (DropAnalysis.handDrops(overworld, player, block, item)) {
				return true;
			}
			List<Item> tools = DropAnalysis.toolItemsFor(overworld, player, block, item);
			if (tools.stream().anyMatch(yes::contains) || (silkTouch && tools.isEmpty())) {
				return true;
			}
		}
		// Killed: a mob there that drops it.
		if (MobDrops.sources(overworld, player, item).keySet().stream().anyMatch(mobs::contains)) {
			return true;
		}
		// Water: the planner's own step for it (see CraftPlanner.Fill); there's water in the Overworld.
		if (item == net.minecraft.world.item.Items.WATER_BUCKET && yes.contains(net.minecraft.world.item.Items.BUCKET)) {
			return true;
		}
		for (RecipeIndex.Craft craft : RecipeIndex.craftsFor(overworld, item)) {
			if (craft.ingredients().stream().allMatch(ing -> any(ing, yes))) {
				return true;
			}
		}
		for (RecipeIndex.Smelt smelt : RecipeIndex.smeltsFor(overworld, item)) {
			if (any(smelt.input(), yes)) {
				return true;
			}
		}
		for (Interactions.Way way : Interactions.waysTo(overworld, item)) {
			if (!yes.contains(way.input())) {
				continue;
			}
			boolean ok = switch (way.kind()) {
				case SCOOP -> way.at().stream().anyMatch(b -> blockThere.computeIfAbsent(b, x -> there(x, levels)));
				case MOB -> way.mobs().stream().anyMatch(mobs::contains);
				default -> true;
			};
			if (ok) {
				return true;
			}
		}
		return false;
	}

	private static boolean any(Ingredient ingredient, Set<Item> yes) {
		return RecipeIndex.options(ingredient).stream().anyMatch(yes::contains);
	}

	/** One of these dimensions' biomes makes it, or one of its structures is built with it. */
	private static boolean there(Block block, List<ServerLevel> levels) {
		for (ServerLevel level : levels) {
			if (!BiomeHints.biomesFor(level, List.of(block)).isEmpty() || !StructureIndex.holding(level, List.of(block)).isEmpty()) {
				return true;
			}
		}
		return false;
	}
}
