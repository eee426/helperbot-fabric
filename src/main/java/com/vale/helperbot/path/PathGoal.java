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

	/** Ends at any of these: for one search to many targets (see MovementController#findWay). */
	static PathGoal anyOf(java.util.Collection<PathGoal> goals) {
		java.util.List<PathGoal> all = java.util.List.copyOf(goals);
		return (w, p) -> {
			for (PathGoal g : all) {
				if (g.test(w, p)) {
					return true;
				}
			}
			return false;
		};
	}
}
