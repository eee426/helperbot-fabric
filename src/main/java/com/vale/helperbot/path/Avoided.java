package com.vale.helperbot.path;

import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * Cells a search steers round for now: what the bot found out by going there, which no plan sees
 * (MovementController#avoidUntil keeps them, each for a while). Every one of them is closed to
 * walking, standing and swimming. For digging there are two kinds, and they mean opposite things:
 *
 * <ul>
 * <li>a wall: the plan thought it open and it wasn't (MovementController#learnObstacle). With a
 * block in it, that block is dug out and gone through like any other.</li>
 * <li>given up: where a step was going that took far too long, or failed again and again, for
 * whatever reason. Nothing leads into it, a way dug into it included: the same step was what got
 * planned again otherwise, over and over.</li>
 * </ul>
 *
 * @param closed  all of them
 * @param givenUp the second kind only (each is in {@code closed} too)
 */
public record Avoided(LongSet closed, LongSet givenUp) {
	public static final Avoided NONE = new Avoided(LongSet.of(), LongSet.of());

	public boolean closed(long cell) {
		return closed.contains(cell);
	}

	public boolean givenUp(long cell) {
		return givenUp.contains(cell);
	}
}
