package com.vale.helperbot.knowledge;

import com.vale.helperbot.HelperBot;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Development tool: with the environment variable HELPERBOT_BENCH set, plans a few items from an
 * empty bag at the world spawn when the server starts, logs how long each took and where the time
 * went, and stops the server. For measuring the planner without playing.
 */
public final class PlanBench {
	private static final List<Item> ITEMS = List.of(Items.DIAMOND_PICKAXE, Items.STONE_PICKAXE, Items.WHITE_CARPET,
			Items.BLUE_CARPET, Items.DIAMOND_PICKAXE, Items.STONE_PICKAXE);

	private PlanBench() {
	}

	public static void run(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.ZERO);
		for (int dx = -5; dx <= 5; dx++) {
			for (int dz = -5; dz <= 5; dz++) {
				level.getChunk((spawn.getX() >> 4) + dx, (spawn.getZ() >> 4) + dz);
			}
		}
		spawn = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.ZERO);
		FakePlayer bot = FakePlayer.get(level);
		bot.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
		if ("sweep".equals(System.getenv("HELPERBOT_BENCH"))) {
			sweep(level, bot);
			server.halt(false);
			return;
		}
		for (Item item : ITEMS) {
			CraftPlanner planner = new CraftPlanner(level, bot, bot.getInventory(), new HashSet<>()).unlimited();
			long start = System.nanoTime();
			CraftPlanner.Plan plan = planner.plan(item, 1, Map.of());
			long ms = (System.nanoTime() - start) / 1_000_000;
			StringBuilder steps = new StringBuilder();
			if (plan.possible()) {
				for (CraftPlanner.Action a : plan.actions()) {
					steps.append(switch (a) {
						case CraftPlanner.Gather g -> "gather " + g.item() + "x" + g.count();
						case CraftPlanner.Hunt h -> "hunt " + h.item() + "x" + h.count();
						case CraftPlanner.Fill f -> "fill x" + f.count();
						case CraftPlanner.Use u -> "use " + u.way() + "x" + u.count();
						case CraftPlanner.Craft c -> "craft " + c.recipe().result() + "x" + c.times() * c.recipe().resultCount();
						case CraftPlanner.Smelt s -> "smelt " + s.recipe().result() + "x" + s.count() * s.recipe().resultCount();
					}).append(" > ");
				}
			}
			HelperBot.LOGGER.info("BENCH {}: {} ms, {} ; {} ; {} problems", item, ms, planner.stats(),
					plan.possible() ? steps : "IMPOSSIBLE " + plan.problems().stream().limit(8).map(c -> c.getString()).toList(),
					plan.problems().size());
		}
		server.halt(false);
	}

	/** Every item from an empty bag: can it be planned, by what kinds of step, and if not why (see scripts/run-sweep.ps1). */
	private static void sweep(ServerLevel level, FakePlayer bot) {
		long start = System.nanoTime();
		// Where each item comes from, as the remote shows it (see WhereFrom).
		try (java.io.PrintWriter out = new java.io.PrintWriter("where-from.txt", java.nio.charset.StandardCharsets.UTF_8)) {
			var sorted = new java.util.TreeMap<String, WhereFrom.Place>();
			WhereFrom.all(level.getServer()).forEach((item, place) -> sorted.put(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath(), place));
			sorted.forEach((item, place) -> out.println(place + "	" + item));
		} catch (java.io.IOException e) {
			HelperBot.LOGGER.error("Where-from list failed", e);
		}
		// What the biome knowledge says, to check against the world: block, then its biomes, richest first.
		try (java.io.PrintWriter out = new java.io.PrintWriter("biome-table.txt", java.nio.charset.StandardCharsets.UTF_8)) {
			var table = new java.util.TreeMap<String, Map<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>, Double>>();
			BiomeHints.table(level).forEach((block, biomes) -> table.put(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath(), biomes));
			table.forEach((block, biomes) -> out.println(block + "	" + biomes.entrySet().stream()
					.sorted(Map.Entry.<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>, Double>comparingByValue().reversed())
					.map(e -> e.getKey().unwrapKey().map(k -> k.identifier().getPath()).orElse("?") + "=" + String.format("%.2f", e.getValue()))
					.toList()));
		} catch (java.io.IOException e) {
			HelperBot.LOGGER.error("Biome table failed", e);
		}
		try (java.io.PrintWriter out = new java.io.PrintWriter("plan-sweep.txt", java.nio.charset.StandardCharsets.UTF_8)) {
			for (Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
				String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
				String refusal = Obtainability.refusal(level, item);
				if (refusal != null) {
					out.println("REFUSED	" + id + "		" + refusal);
					continue;
				}
				CraftPlanner.Plan plan = new CraftPlanner(level, bot, bot.getInventory(), new HashSet<>()).plan(item, 1, Map.of());
				String kinds = !plan.possible() ? "" : plan.actions().stream().map(a -> a.getClass().getSimpleName()).distinct().toList().toString();
				out.println((plan.possible() ? "OK	" : "NO	") + id + "	" + kinds + "	" + (int) (plan.ticks() / 20) + "s	"
						+ plan.problems().stream().limit(3).map(c -> c.getString()).toList());
			}
		} catch (java.io.IOException e) {
			HelperBot.LOGGER.error("Sweep failed", e);
		}
		HelperBot.LOGGER.info("BENCH sweep took {} s", (System.nanoTime() - start) / 1_000_000_000);
	}
}
