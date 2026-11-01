package com.vale.helperbot.path;

/**
 * How much the bot will spend (health, terrain) to get there faster. All path costs are real
 * ticks, so the cheapest path is the fastest one; a mode only adds penalties on top and changes
 * which moves are allowed. It never makes anything look faster than it really is.
 */
public enum Mode {
	SAFE(3, false, 2.5, 2),
	NORMAL(5, true, 1.5, 1),
	AGGRESSIVE(12, true, 1.0, 1);

	/** Highest drop the bot will consider. Further limited by current health. */
	public final int maxDrop;
	public final boolean sprint;
	/**
	 * Multiplier on real mining time: 1.0 = pure time; above 1 adds reluctance to dig up terrain.
	 * Never below 1, or slow digging routes would look faster than they are.
	 */
	public final double digCostFactor;
	/** Blocks of clearance to keep from lava. */
	public final int lavaMargin;

	Mode(int maxDrop, boolean sprint, double digCostFactor, int lavaMargin) {
		this.maxDrop = maxDrop;
		this.sprint = sprint;
		this.digCostFactor = digCostFactor;
		this.lavaMargin = lavaMargin;
	}

	public static final Mode DEFAULT = AGGRESSIVE;

	/** Vanilla ground speeds in blocks per tick. */
	public static final double WALK_SPEED = 0.2158;
	public static final double SPRINT_SPEED = 0.2806;

	/** Ground speed used for planning (the bot drops to walking when it's too hungry to sprint). */
	public double speed() {
		return sprint ? SPRINT_SPEED : WALK_SPEED;
	}

	/**
	 * Drop limit for the current health: falls over 3 blocks deal (height - 3) damage, and the
	 * bot always keeps at least 6 health (3 hearts) after landing.
	 */
	public int maxDropFor(float health) {
		int safeExtra = Math.max(0, (int) Math.floor(health) - 6);
		return Math.min(maxDrop, 3 + (this == SAFE ? 0 : safeExtra));
	}
}
