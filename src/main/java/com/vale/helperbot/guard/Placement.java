package com.vale.helperbot.guard;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;

/**
 * May a block go here: the one answer, for the planner laying out a bridge and for the hand putting
 * it down, so the two can't disagree. A search reads this on its copy of the world; what only the
 * live world can tell (who's standing there, what's in the bag) is checked at the moment of placing.
 * Every refusal has a reason, for the log: "failed to place" alone told nothing of what went wrong.
 */
public final class Placement {
	/** What for: the rules aren't the same. */
	public enum Kind {
		/** Left in the world (a bridge, a pillar, a plug): open rules only. May replace water. */
		BUILD,
		/** Its own crafting table or furnace, taken back right after: allowed under strict rules too. Not in liquid. */
		STATION
	}

	public enum Refusal {
		RULES("规则不允许放方块"),
		PROTECTED("这里是保护区块"),
		TAKEN("这一格已经有东西了"),
		LIQUID("这一格有液体"),
		GHOST("人在保护区块里，什么都不能放"),
		BANNED_ITEM("这种方块被禁止使用"),
		NO_BLOCKS("背包里没有能放的方块"),
		NOTHING_BESIDE("旁边没有能贴着放的方块"),
		IN_THE_WAY("有人或生物挡在那里"),
		NOT_PLACED("放了，但方块没有出现");

		public final String why;

		Refusal(String why) {
			this.why = why;
		}
	}

	private Placement() {
	}

	/** Null if a block may go here as far as the world goes, otherwise why not. */
	public static Refusal check(BlockGetter level, BlockPos pos, Kind kind) {
		if (kind == Kind.BUILD && !Rules.open()) {
			return Refusal.RULES;
		}
		if (ProtectionZones.isProtected(level, pos)) {
			return Refusal.PROTECTED;
		}
		if (!level.getBlockState(pos).canBeReplaced()) {
			return Refusal.TAKEN;
		}
		boolean liquid = kind == Kind.STATION ? !level.getFluidState(pos).isEmpty() : level.getFluidState(pos).is(FluidTags.LAVA);
		return liquid ? Refusal.LIQUID : null;
	}

	public static boolean allowed(BlockGetter level, BlockPos pos, Kind kind) {
		return check(level, pos, kind) == null;
	}
}
