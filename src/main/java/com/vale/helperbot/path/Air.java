package com.vale.helperbot.path;

import net.minecraft.world.level.BlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

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

	/**
	 * Real ticks for {@code blocks} pieces of work at a spot {@code up} ticks from air, each costing
	 * {@code work} air (see {@link #workUnderWater}), counting everything it really takes: as many
	 * dives as one breath divides the work into, each with the way down (about as long as the way up),
	 * the way up, and the air won back at the surface. Infinite if not even one piece fits in a breath.
	 * This is what lets water be weighed against land: 50 clay by hand from 5 deep isn't twice the
	 * trouble of a cave, it's a dozen times, and a little work from 2 deep is hardly any.
	 */
	public static double diving(Profile air, int blocks, double work, double up) {
		double room = air.full() - up - reserve(up) - PLAN_SLACK; // the rule of enough, for a full dive
		if (room <= work) {
			return Double.POSITIVE_INFINITY;
		}
		int perDive = (int) Math.min(blocks, room / work);
		int dives = (blocks + perDive - 1) / perDive;
		double breath = 2 * up + perDive * work;
		return blocks * work + dives * (2 * up + breath / air.refill());
	}

	/**
	 * Sprint-swimming (vanilla's swimming pose: sprinting with the head under water), blocks/tick, in
	 * any direction: vanilla works out to about 0.18 across (friction 0.9 instead of 0.8, pull 0.02),
	 * as fast or faster up and down (it swims where it looks). A touch under that, to be safe.
	 */
	public static final double SPRINT_SWIM_SPEED = 0.17;

	/**
	 * Ticks to swim (dx, dy, dz) blocks sprinting, when it can: fully under water at both ends (in the
	 * swimming pose the eyes are low, so at the surface they'd be under and the air would run).
	 */
	public static double swimTicks(double dx, double dy, double dz, boolean sprint) {
		return sprint ? Math.sqrt(dx * dx + dy * dy + dz * dz) / SPRINT_SWIM_SPEED : swimTicks(dx, dy, dz);
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
	 * @param limited        false: ignore air (e.g. while heading for air: any way up will do)
	 * @param workTicks      what it will do once there (a fight, say): the goal must leave air for that too
	 * @param refill         budget won back per tick with the head out of the water (see {@link #REFILL})
	 * @param sprintBlocks   blocks it can sprint-swim on the food it has (see {@link #sprintBlocks}); 0: not at all
	 */
	public record Profile(int now, int full, boolean limited, double workTicks, double refill, double sprintBlocks) {
	}

	/** Vanilla: air comes back 4 points a tick with the head out of the water. */
	public static final int REFILL = 4;

	private Air() {
	}

	public static Profile profile(LivingEntity e, boolean limited, double workTicks, double sprintBlocks) {
		return new Profile(budget(e), fullBudget(e), limited, workTicks, REFILL * airStretch(e), sprintBlocks);
	}

	/** Vanilla: swimming costs this much exhaustion a block, and 4 exhaustion cost a point of saturation, then of food. */
	private static final double EXHAUSTION_PER_BLOCK = 0.01, EXHAUSTION_PER_POINT = 4;
	/** Vanilla: no sprinting at this much food or less. */
	private static final int SPRINT_FOOD = 6;

	/**
	 * Blocks it can sprint-swim before hunger stops it: what's left above {@link #SPRINT_FOOD}, in
	 * saturation and food. How far the current point has gone isn't told, so one point is left out.
	 */
	public static double sprintBlocks(Player p) {
		FoodData food = p.getFoodData();
		if (food.getFoodLevel() <= SPRINT_FOOD) {
			return 0;
		}
		double points = food.getFoodLevel() - SPRINT_FOOD + food.getSaturationLevel() - 1;
		return Math.max(0, points) * EXHAUSTION_PER_POINT / EXHAUSTION_PER_BLOCK;
	}

	/**
	 * Ticks up through {@code depth} blocks of open water: sprinting as far as the food goes, not
	 * for the last block (its head comes out, and that's where sprint-swimming ends). See
	 * {@link Escape}, which works this out cell by cell.
	 */
	public static double ascentTicks(Profile air, int depth) {
		int fast = (int) Math.min(Math.max(0, depth - 1), air.sprintBlocks());
		return fast / SPRINT_SWIM_SPEED + (depth - fast) / SWIM_UP_SPEED;
	}

	/** Fully under water: feet and head cells both water (where it may sprint-swim). */
	public static boolean submerged(BlockGetter level, BlockPos feet) {
		return LocalPathfinder.isWater(level, feet) && LocalPathfinder.isWater(level, feet.above());
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
		return (int) (Math.max(0, e.getAirSupply()) * airStretch(e)) + spareHealthTicks(e);
	}

	/** Same, after a full breath. */
	public static int fullBudget(LivingEntity e) {
		return unlimited(e) ? UNLIMITED : (int) (e.getMaxAirSupply() * airStretch(e)) + spareHealthTicks(e);
	}

	/**
	 * How many ticks one point of air lasts: Respiration (the oxygen bonus attribute, b) makes vanilla
	 * skip using air on a tick with chance 1 - 1/(b+1), so on average air lasts b+1 times as long.
	 */
	private static double airStretch(LivingEntity e) {
		var bonus = e.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.OXYGEN_BONUS);
		return 1 + Math.max(0, bonus == null ? 0 : bonus.getValue());
	}

	private static boolean unlimited(LivingEntity e) {
		return e.canBreatheUnderwater() || MobEffectUtil.hasWaterBreathing(e);
	}

	private static int spareHealthTicks(LivingEntity e) {
		int hits = (int) Math.floor((e.getHealth() - KEEP_HEALTH) / DAMAGE_PER_HIT);
		return Math.max(0, hits) * TICKS_PER_HIT;
	}

	/**
	 * Mining slowdown with feet in {@code feet}: vanilla's 5x for not standing on anything. That goes
	 * for floating at the surface too, head out: it used to count as dry land, so a block under 3 of
	 * water looked as cheap as one on the shore. On the bottom of water a block deep, or on a block in
	 * the water (a chest in a wreck), it stands: full speed. (With the head under, it doesn't mine:
	 * see Guard#ofTheSea.)
	 */
	public static double digPenalty(BlockGetter level, BlockPos feet) {
		if (!LocalPathfinder.isWater(level, feet)) {
			return 1;
		}
		BlockState in = level.getBlockState(feet);
		boolean onBlock = !BlockTable.empty(in, level, feet) && LocalPathfinder.ground(in);
		return onBlock || !Double.isNaN(LocalPathfinder.groundUnder(level, feet)) ? 1 : 5;
	}

	/** With feet in this cell (standing, or floating at the surface), is the head out of the water? */
	public static boolean breathable(BlockGetter level, BlockPos feet) {
		BlockPos head = feet.above();
		return !LocalPathfinder.isWater(level, head) && !waterComing(level, head);
	}

	/** Most open cells looked up through for water about to pour in (see {@link #waterComing}). */
	private static final int POCKET_HEIGHT = 8;

	/**
	 * Is there water straight above through open cells? Then this air is a pocket about to be filled
	 * (water always flows down): a block just dug out under water, before the water came in. Taken
	 * for air, the bot planned a long dig there expecting a breath at the end of it, and ran out halfway.
	 */
	public static boolean waterComing(BlockGetter level, BlockPos cell) {
		BlockPos p = cell.above();
		for (int i = 0; i < POCKET_HEIGHT; i++, p = p.above()) {
			if (LocalPathfinder.isWater(level, p)) {
				return true;
			}
			if (!LocalPathfinder.isPassable(level, p)) {
				return false;
			}
		}
		return false;
	}

	/**
	 * Budget to keep in hand at a spot that's {@code escape} ticks from air: the estimate plus a
	 * quarter (it's an estimate) plus two seconds.
	 */
	public static double reserve(double escape) {
		return escape * 1.25 + 40;
	}
}
