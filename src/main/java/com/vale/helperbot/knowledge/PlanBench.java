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
}
