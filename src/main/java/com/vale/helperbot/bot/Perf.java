package com.vale.helperbot.bot;

import java.util.Locale;

/**
 * Where the bot's time goes, per server tick, over the last half minute: for /bot perf and the
 * performance scene. Measuring only, it changes nothing. The parts nest as the code does: the
 * task includes the planner, movement includes pathfinding, the survival reflexes include the
 * movement they drive while busy.
 */
public final class Perf {
	public enum Part {
		/** The vanilla player tick: what any player costs (effects, food, air, entity bookkeeping). */
		VANILLA("原版玩家逻辑"),
		/** Air, fighting back, eating, collecting drops, and the movement they drive meanwhile. */
		SURVIVAL("生存反应（换气/打架/吃/捡）"),
		/** The task itself (choosing targets, scanning for blocks, crafting...). */
		TASK("任务"),
		/** Of the task: working out a plan (recipes, sources, costs). */
		PLANNER("规划"),
		/** Walking, digging, placing: following the path. */
		MOVE("移动"),
		/** Of the movement: path search work on the server thread (with searches in the background: copying the blocks around). */
		PATHFIND("寻路"),
		/** The path search itself, on the background thread: not part of the server tick (counted when a search finishes). */
		BACKGROUND("后台寻路（不占主线程）");

		final String label;

		Part(String label) {
			this.label = label;
		}
	}

	/** Ticks per window (30 s). */
	private static final int WINDOW = 600;
	private final long[] sum = new long[Part.values().length];
	private final long[] max = new long[Part.values().length];
	private final long[] thisTick = new long[Part.values().length];
	private int ticks;
	/** Since it appeared: total and worst nanoseconds per part, and ticks (for the test report). */
	private final long[] allSum = new long[Part.values().length];
	private final long[] allMax = new long[Part.values().length];
	private long allWorstTick;
	private int allTicks;
	/** The last complete window: average and worst ms per tick, per part. */
	private double[] lastAvg, lastMax;
	private int lastTicks;

	public void add(Part part, long nanos) {
		if (part == Part.SURVIVAL || part == Part.MOVE) {
			// The movement they drive includes any test-run wait (see PathJob#paceForTests): not the bot's time.
			nanos = Math.max(0, nanos - testWait);
			testWait = 0;
		}
		thisTick[part.ordinal()] += nanos;
	}

	private long testWait;

	/** Test runs: time spent waiting for a background search to stand in for a real tick's length. */
	public void testWait(long nanos) {
		testWait += nanos;
	}

	/** End of a server tick: fold this tick's times into the window. */
	void endTick() {
		long own = thisTick[Part.VANILLA.ordinal()] + thisTick[Part.SURVIVAL.ordinal()]
				+ thisTick[Part.TASK.ordinal()] + thisTick[Part.MOVE.ordinal()];
		allWorstTick = Math.max(allWorstTick, own);
		allTicks++;
		for (int i = 0; i < sum.length; i++) {
			sum[i] += thisTick[i];
			max[i] = Math.max(max[i], thisTick[i]);
			allSum[i] += thisTick[i];
			allMax[i] = Math.max(allMax[i], thisTick[i]);
			thisTick[i] = 0;
		}
		if (++ticks >= WINDOW) {
			roll();
		}
	}

	private void roll() {
		lastAvg = new double[sum.length];
		lastMax = new double[sum.length];
		for (int i = 0; i < sum.length; i++) {
			lastAvg[i] = sum[i] / 1e6 / Math.max(1, ticks);
			lastMax[i] = max[i] / 1e6;
			sum[i] = 0;
			max[i] = 0;
		}
		lastTicks = ticks;
		ticks = 0;
	}

	/** Average ms per tick of a part over the last window (or what there is so far). */
	public double average(Part part) {
		return lastAvg != null ? lastAvg[part.ordinal()] : sum[part.ordinal()] / 1e6 / Math.max(1, ticks);
	}

	public double worst(Part part) {
		return lastAvg != null ? lastMax[part.ordinal()] : max[part.ordinal()] / 1e6;
	}

	/** The bot's own total, ms per tick on average (the top-level parts). */
	public double total() {
		return average(Part.VANILLA) + average(Part.SURVIVAL) + average(Part.TASK) + average(Part.MOVE);
	}

	private double all(Part p) {
		return allSum[p.ordinal()] / 1e6 / Math.max(1, allTicks);
	}

	/** One short line since it appeared, for the test report. */
	public String compact() {
		double total = all(Part.VANILLA) + all(Part.SURVIVAL) + all(Part.TASK) + all(Part.MOVE);
		return String.format(Locale.ROOT, "%.2f ms/刻（原版 %.2f、反应 %.2f、任务 %.2f 含规划 %.2f、移动 %.2f 含寻路 %.2f）；最慢一刻 %.1f ms（规划最慢 %.0f、寻路最慢 %.1f）",
				total, all(Part.VANILLA), all(Part.SURVIVAL), all(Part.TASK), all(Part.PLANNER), all(Part.MOVE), all(Part.PATHFIND),
				allWorstTick / 1e6, allMax[Part.PLANNER.ordinal()] / 1e6, allMax[Part.PATHFIND.ordinal()] / 1e6)
				+ String.format(Locale.ROOT, "；后台寻路 %.2f ms/刻（最多一刻 %.1f）", all(Part.BACKGROUND), allMax[Part.BACKGROUND.ordinal()] / 1e6);
	}

	/** One line per part, and the bot's own total (the top-level parts) against a 50 ms tick. */
	public String summary() {
		StringBuilder sb = new StringBuilder();
		sb.append(lastAvg != null ? "最近 " + lastTicks / 20 + " 秒" : "刚开始统计（" + ticks / 20 + " 秒）").append("，每刻平均 / 最慢一刻（毫秒）：");
		for (Part p : Part.values()) {
			String indent = p == Part.PLANNER || p == Part.PATHFIND ? "  其中" : "";
			sb.append('\n').append(indent).append(p.label).append(String.format(Locale.ROOT, "：%.2f / %.1f", average(p), worst(p)));
		}
		double total = average(Part.VANILLA) + average(Part.SURVIVAL) + average(Part.TASK) + average(Part.MOVE);
		sb.append(String.format(Locale.ROOT, "\n机器人自己合计（不含后台）：%.2f 毫秒/刻，占一刻（50 毫秒）的 %.1f%%", total, total / 50 * 100));
		return sb.toString();
	}
}
