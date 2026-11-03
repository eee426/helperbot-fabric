package com.vale.helperbot.path;

import net.minecraft.world.level.BlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/**
 * Breath as a resource, the one model everything under water uses: the planner (where may it
 * go), the breathing layer (when must it head up), and task target choice (is that kelp worth
 * it). Two numbers: the budget (ticks it can stay under from now) and, from {@link Escape}, the
 * ticks it needs from a spot to get its head out of the water. Rule: budget >= that, always.
 */
public final class Air {
	/** Vanilla air supply. */
	public static final int MAX_AIR = 300;
	/** Effectively unlimited (water breathing, conduit power). */
	public static final int UNLIMITED = 1_000_000;
	/**
	 * Health it may spend drowning to finish a dive (vanilla: 2 damage per second with no air
	 * left), never going below this. A player does the same: "one more block, then up".
	 */
	public static final float KEEP_HEALTH = 12;
	private static final int TICKS_PER_HIT = 20;
	private static final float DAMAGE_PER_HIT = 2;
	/**
	 * Swimming speeds, blocks/tick: what the bot really moves at (the movement controller) and what
	 * planning and the way-up estimate charge, from this one place. Up or down is slower than
	 * across; the planner once charged vertical swimming at the across speed, so a dive took half
	 * as long again as planned, and the bot turned back for air half way along a planned route.
	 */
	public static final double SWIM_SPEED = 0.12;
	/** Up or down: swimming straight up for air, and the vertical part of any swim. */
	public static final double SWIM_UP_SPEED = 0.08;
	/**
	 * Breath kept on top of {@link #reserve} when planning: room for moving a little slower than
	 * planned, so the bot doesn't reach the breathing layer's limit on a route it was given.
	 */
	public static final int PLAN_SLACK = 20;

	/**
	 * Time to keep for collecting what the work drops, on the same dive: it lands within a block or
	 * two (in the hole just dug, say). Without it the bot mined the block, found too little air to
	 * swim the one block down to the drop, went up for air and came back for it.
	 */
	public static final int PICKUP_TICKS = 40;

	/** Air a piece of work costs where mining goes {@code penalty} times slower: the work, and picking up its drops. */
	public static double workUnderWater(double dryTicks, double penalty) {
		return dryTicks * penalty + PICKUP_TICKS;
	}

	/** Ticks to swim (dx, dy, dz) blocks: at the swimming speed, but no faster up or down than {@link #SWIM_UP_SPEED}. */
	public static double swimTicks(double dx, double dy, double dz) {
		return Math.max(Math.sqrt(dx * dx + dy * dy + dz * dz) / SWIM_SPEED, Math.abs(dy) / SWIM_UP_SPEED);
	}

	/**
	 * What the planner needs to know about the bot's breath.
	 *
	 * @param now            budget right now
	 * @param full           budget with a full breath (what it has after surfacing)
	 * @param waterDigFactor how much slower mining is with the head under water (5 without aqua affinity)
	 * @param limited        false: ignore air (e.g. while heading for air: any way up will do)
	 * @param workTicks      what it will do once there (mining a block, say), in ticks on dry land:
	 *                       the goal must leave air for that too, at the speed it works there
	 */
	public record Profile(int now, int full, double waterDigFactor, boolean limited, double workTicks) {
	}

	private Air() {
	}

	public static Profile profile(LivingEntity e, boolean limited, double workTicks) {
		return new Profile(budget(e), fullBudget(e), waterDigFactor(e), limited, workTicks);
	}

	/**
	 * The one rule, used by the planner, the breathing layer and the tasks alike: with {@code budget}
	 * ticks of breath, may it spend {@code spend} more where getting back to air takes
	 * {@code escape}? Each used to work it out its own way, and where they disagreed the bot
	 * dithered: planned a dive, then turned back half way; got to a block, then found too little
	 * air to mine it.
	 */
	public static boolean enough(double budget, double escape, double spend) {
		return budget - spend > reserve(escape);
	}

	/** Ticks it can stay under water from now: air left, plus health it may spend. */
	public static int budget(LivingEntity e) {
		if (unlimited(e)) {
			return UNLIMITED;
		}
		return Math.max(0, e.getAirSupply()) + spareHealthTicks(e);
	}

	/** Same, after a full breath. */
	public static int fullBudget(LivingEntity e) {
		return unlimited(e) ? UNLIMITED : e.getMaxAirSupply() + spareHealthTicks(e);
	}

	private static boolean unlimited(LivingEntity e) {
		return e.canBreatheUnderwater() || MobEffectUtil.hasWaterBreathing(e);
	}

	private static int spareHealthTicks(LivingEntity e) {
		int hits = (int) Math.floor((e.getHealth() - KEEP_HEALTH) / DAMAGE_PER_HIT);
		return Math.max(0, hits) * TICKS_PER_HIT;
	}

	/** Vanilla: mining with the head under water is this much slower (aqua affinity removes it). */
	public static double waterDigFactor(LivingEntity e) {
		return 1.0 / Math.max(0.01, e.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED));
	}

	/**
	 * Mining slowdown with feet in {@code feet}: head under water, and (vanilla) 5x again when not
	 * standing on anything, i.e. floating.
	 */
	public static double digPenalty(BlockGetter level, BlockPos feet, double waterDigFactor) {
		if (breathable(level, feet)) {
			return 1;
		}
		return waterDigFactor * (LocalPathfinder.isSupport(level, feet.below()) ? 1 : 5);
	}

	/** With feet in this cell (standing, or floating at the surface), is the head out of the water? */
	public static boolean breathable(BlockGetter level, BlockPos feet) {
		return !LocalPathfinder.isWater(level, feet.above());
	}

	/**
	 * Budget to keep in hand at a spot that's {@code escape} ticks from air: the estimate plus a
	 * quarter (it's an estimate) plus two seconds.
	 */
	public static double reserve(double escape) {
		return escape * 1.25 + 40;
	}
}
