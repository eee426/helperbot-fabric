package com.vale.helperbot.test;

import com.vale.helperbot.HelperBot;
import com.vale.helperbot.bot.BotManager;
import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.task.BotTask;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Runs the test scenes one after another on a dev server, as fast as the server can tick, then
 * writes a report and stops the server. Started by setting the environment variable
 * HELPERBOT_SCENARIOS ("all", or ids / id prefixes separated by commas); see
 * scripts/run-scenarios.ps1, which also makes the superflat test world.
 */
public final class ScenarioRunner {
	/** Scenarios are this far apart (blocks along x), so they never see each other. */
	private static final int SPACING = 1000;
	/** Ticks between scenes (bot removed and joined again, chunks settle). */
	private static final int GAP = 20;

	private enum Step {
		WAIT, SETUP, SETTLE, RUN
	}

	private final MinecraftServer server;
	private final List<Scenario> scenarios;
	private final List<String> rows = new ArrayList<>();
	private final List<String> details = new ArrayList<>();
	private int index = -1;
	private Step step = Step.WAIT;
	private int ticks;
	private Arena arena;
	private StringBuilder log;
	private int passed;

	private ScenarioRunner(MinecraftServer server, List<Scenario> scenarios) {
		this.server = server;
		this.scenarios = scenarios;
	}

	public static void startIfRequested(MinecraftServer server) {
		String wanted = System.getenv("HELPERBOT_SCENARIOS");
		if (wanted == null || wanted.isBlank()) {
			return;
		}
		List<Scenario> chosen = new ArrayList<>();
		for (Scenario s : Scenarios.all()) {
			if (wanted.equalsIgnoreCase("all")) {
				chosen.add(s);
				continue;
			}
			for (String w : wanted.split(",")) {
				if (!w.isBlank() && s.id().toLowerCase().startsWith(w.trim().toLowerCase())) {
					chosen.add(s);
					break;
				}
			}
		}
		HelperBot.LOGGER.info("SCENARIOS: running {} of {}", chosen.size(), Scenarios.all().size());
		ServerLevel level = server.overworld();
		level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
		level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
		level.setDayTime(1000);
		level.setWeatherParameters(1_000_000, 0, false, false);
		server.tickRateManager().requestGameToSprint(Integer.MAX_VALUE / 2);
		ScenarioRunner runner = new ScenarioRunner(server, chosen);
		ServerTickEvents.END_SERVER_TICK.register(s -> runner.tick());
	}

	private void tick() {
		if (index >= scenarios.size()) {
			return;
		}
		ticks++;
		switch (step) {
			case WAIT -> {
				BotManager.remove(server, "换下一个测试场景");
				if (ticks >= GAP) {
					index++;
					if (index >= scenarios.size()) {
						finish();
						return;
					}
					step = Step.SETUP;
					ticks = 0;
				}
			}
			case SETUP -> setup(scenarios.get(index));
			case SETTLE -> settle();
			case RUN -> run(scenarios.get(index));
		}
	}

	private void setup(Scenario s) {
		ServerLevel level = server.overworld();
		BlockPos origin = new BlockPos((index + 1) * SPACING, Arena.SURFACE, 0);
		// Kept loaded for the whole scene, like the chunks around a player who has been there a while
		// (the bot's own view only loads over the next few ticks, and the first scan comes sooner).
		for (int cx = -LOADED; cx <= LOADED; cx++) {
			for (int cz = -LOADED; cz <= LOADED; cz++) {
				level.setChunkForced((origin.getX() >> 4) + cx, (origin.getZ() >> 4) + cz, true);
			}
		}
		arena = new Arena(level, origin);
		arena.clearMobs(64);
		FakePlayer requester = FakePlayer.get(level);
		requester.teleportTo(level, origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, Set.of(), 0, 0, true);
		BotPlayer bot = BotManager.spawn(server, requester);
		if (bot == null) {
			record(s, false, "机器人召唤失败");
			return;
		}
		bot.getInventory().clearContent();
		bot.resetAfterDeath();
		log = new StringBuilder();
		BotPlayer.logTap = e -> log.append(e).append('\n');
		HelperBot.LOGGER.info("SCENARIO {} start: {}", s.id(), s.title());
		pending = s.setup().start(arena, bot, requester);
		step = Step.SETTLE;
		ticks = 0;
	}

	/** Chunks loaded around each scene (radius, in chunks). */
	private static final int LOADED = 6;
	/** The task given by the scene's setup, started once the scene has settled. */
	private BotTask pending;

	/** A moment for the scene to settle (blocks updating, water flowing) before the task starts. */
	private void settle() {
		if (ticks < SETTLE) {
			return;
		}
		BotPlayer bot = BotManager.get();
		if (bot != null && pending != null) {
			bot.startTask(pending);
		}
		pending = null;
		step = Step.RUN;
		ticks = 0;
		startedAt = System.nanoTime();
	}

	private static final int SETTLE = 40;

	private void unload() {
		if (arena == null) {
			return;
		}
		for (int cx = -LOADED; cx <= LOADED; cx++) {
			for (int cz = -LOADED; cz <= LOADED; cz++) {
				arena.level.setChunkForced((arena.origin.getX() >> 4) + cx, (arena.origin.getZ() >> 4) + cz, false);
			}
		}
	}

	/** A scene may take at most this much real time (it normally runs many times faster than the game). */
	private static final long REAL_TIME_LIMIT = 3 * 60_000_000_000L;
	private long startedAt;

	private void run(Scenario s) {
		BotPlayer bot = BotManager.get();
		if (bot == null || !bot.isAlive()) {
			record(s, false, "机器人死了（" + (bot == null ? "已移除" : bot.getLastDamageSource()) + "）");
			return;
		}
		Scenario.Outcome outcome = s.check().check(new Scenario.Context(arena, bot, ticks, log.toString()));
		if (outcome != null) {
			record(s, outcome.pass(), outcome.reason());
		} else if (System.nanoTime() - startedAt > REAL_TIME_LIMIT) {
			// Ticks that slow mean the bot is doing something heavy every tick (a busy loop): a bug too.
			record(s, false, "现实时间超过 " + REAL_TIME_LIMIT / 60_000_000_000L + " 分钟，只跑了 " + ticks / 20
					+ " 秒游戏时间（每刻约 " + (System.nanoTime() - startedAt) / 1_000_000 / Math.max(1, ticks) + " 毫秒，可能在空转）");
		} else if (ticks >= s.timeout()) {
			record(s, false, "超时（" + s.timeout() / 20 + " 秒游戏时间）；任务：" + (bot.getTask() == null ? "无" : bot.getTask().describe()));
		}
	}

	private void record(Scenario s, boolean pass, String reason) {
		if (pass) {
			passed++;
		}
		String seconds = String.format("%.0f", ticks / 20.0);
		rows.add("| " + s.id() + " | " + (pass ? "✅" : "❌") + " | " + s.title() + " | " + seconds + " 秒 | " + reason + " |");
		HelperBot.LOGGER.info("SCENARIO {} {} after {} s: {}", s.id(), pass ? "PASS" : "FAIL", seconds, reason);
		if (!pass && log != null) {
			String[] lines = log.toString().split("\n");
			StringBuilder tail = new StringBuilder("### " + s.id() + " " + s.title() + "\n\n" + reason + "\n\n最后的日志：\n\n```\n");
			for (int i = Math.max(0, lines.length - 40); i < lines.length; i++) {
				tail.append(lines[i]).append('\n');
			}
			details.add(tail.append("```\n").toString());
		}
		BotPlayer.logTap = null;
		unload();
		step = Step.WAIT;
		ticks = 0;
	}

	private void finish() {
		server.tickRateManager().stopSprinting();
		StringBuilder report = new StringBuilder("# 测试场景结果\n\n通过 " + passed + " / " + rows.size() + "\n\n");
		report.append("| 编号 | 结果 | 场景 | 用时（游戏时间） | 说明 |\n|---|---|---|---|---|\n");
		rows.forEach(r -> report.append(r).append('\n'));
		report.append('\n');
		details.forEach(d -> report.append(d).append('\n'));
		Path file = server.getServerDirectory().resolve("scenario-report.md");
		try {
			Files.writeString(file, report.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			HelperBot.LOGGER.error("Could not write {}", file, e);
		}
		HelperBot.LOGGER.info("SCENARIOS done: {} / {} passed, report in {}", passed, rows.size(), file.toAbsolutePath());
		server.halt(false);
	}
}
