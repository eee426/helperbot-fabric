package com.vale.helperbot.guard;

/**
 * Which rule set the bot runs under.
 *
 * <p>OPEN (beta default, test worlds only): the bot may break any breakable block, place
 * blocks to bridge and pillar, and picks up everything it mines. Only protection zones and
 * its own safety (lava, traps) still stop it.
 *
 * <p>STRICT: only untouched-looking terrain may be broken, no placing, only task items picked up.
 */
public final class Rules {
	public enum Level {
		OPEN, STRICT
	}

	/** Volatile: path searches read it on a worker thread. */
	private static volatile Level current = Level.OPEN;

	private Rules() {
	}

	public static Level current() {
		return current;
	}

	public static void set(Level level) {
		current = level;
	}

	public static boolean open() {
		return current == Level.OPEN;
	}
}
