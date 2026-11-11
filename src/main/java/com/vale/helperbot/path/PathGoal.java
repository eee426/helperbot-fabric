package com.vale.helperbot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

/**
 * Where a path may end. It's asked about many cells during a search, which may run on another
 * thread on a copy of the world ({@link Snapshot}): read blocks only through {@code world}, and
 * take anything else it needs (where a mob or player is) when it's made, not while it's asked.
 */
@FunctionalInterface
public interface PathGoal {
	boolean test(BlockGetter world, BlockPos feet);

	/**
	 * The same, knowing the height the feet are at in that cell: the plan's for a cell it would go
	 * to, the bot's own for where it is. Only a goal about what it reaches from there needs it (see
	 * {@link MineSpot#reachGoal}); the rest ignore it. NaN: not known, the cell's floor.
	 */
	default boolean test(BlockGetter world, BlockPos feet, double floor) {
		return test(world, feet);
	}

	/** A goal that goes by the height of the feet (see {@link #test(BlockGetter, BlockPos, double)}). */
	@FunctionalInterface
	interface AtFloor {
		boolean test(BlockGetter world, BlockPos feet, double floor);
	}

	static PathGoal atFloor(AtFloor goal) {
		return new PathGoal() {
			@Override
			public boolean test(BlockGetter world, BlockPos feet) {
				return goal.test(world, feet, Double.NaN);
			}

			@Override
			public boolean test(BlockGetter world, BlockPos feet, double floor) {
				return goal.test(world, feet, floor);
			}
		};
	}

	/** Ends at any of these: for one search to many targets (see MovementController#findWay). */
	static PathGoal anyOf(java.util.Collection<PathGoal> goals) {
		java.util.List<PathGoal> all = java.util.List.copyOf(goals);
		return atFloor((w, p, f) -> {
			for (PathGoal g : all) {
				if (g.test(w, p, f)) {
					return true;
				}
			}
			return false;
		});
	}
}
