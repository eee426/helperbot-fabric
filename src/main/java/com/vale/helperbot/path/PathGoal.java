package com.vale.helperbot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

import java.util.List;

/**
 * Where a path may end. It's asked about many cells during a search, which may run on another
 * thread on a copy of the world ({@link Snapshot}): read blocks only through {@code world}, and
 * take anything else it needs (where a mob or player is) when it's made, not while it's asked.
 */
@FunctionalInterface
public interface PathGoal {
	/**
	 * Does the path end well with the feet in this cell, at {@code floor}: the height the plan has the
	 * feet at there, or for the cell the bot is in, where it really is. NaN: not known, the cell's
	 * floor in the world as it is (see {@link #floorAt}).
	 *
	 * <p>It's the one way to ask, so that every goal has the plan's height in hand. A goal that looks
	 * for itself where the feet would be, in the world as it is now, gets the cells wrong that the
	 * route changes: one it stands in only once it has laid a block under it, or dug it out, isn't
	 * one at all now. Picking up went by the world like that, and an item up a tree, with nowhere by
	 * it to stand but on a pillar, was searched for to the end and left. A goal that's about where it
	 * is, not how high (near a player, out of a protected chunk), takes the floor and leaves it.
	 */
	boolean test(BlockGetter world, BlockPos feet, double floor);

	/** The same, the height not known. */
	default boolean test(BlockGetter world, BlockPos feet) {
		return test(world, feet, Double.NaN);
	}

	/**
	 * Where the feet are in that cell, for a goal: the plan's height if it gave one, else the cell's
	 * floor as the world has it now (NaN if it can't stand there now). The one place that decides it.
	 */
	static double floorAt(BlockGetter world, BlockPos feet, double floor) {
		return Double.isNaN(floor) ? LocalPathfinder.floorHeight(world, feet) : floor;
	}

	/**
	 * Blocks to mine from there, standing still, for the goal to hold: what's between the eye and a
	 * buried block (see MineSpot#reachGoal). The search holds them to every rule a dig on the way is
	 * held to, and a route that ends there hands them over with it (see LocalPathfinder.Result#work):
	 * whoever the goal is for mines them, the route doesn't. Null or empty: no such work makes it the
	 * goal from there.
	 */
	default List<BlockPos> toClear(BlockGetter world, BlockPos feet, double floor) {
		return null;
	}

	/** Ends at any of these: for one search to many targets (see MovementController#findWay). */
	static PathGoal anyOf(java.util.Collection<PathGoal> goals) {
		List<PathGoal> all = List.copyOf(goals);
		return new PathGoal() {
			@Override
			public boolean test(BlockGetter world, BlockPos feet, double floor) {
				for (PathGoal g : all) {
					if (g.test(world, feet, floor)) {
						return true;
					}
				}
				return false;
			}

			@Override
			public List<BlockPos> toClear(BlockGetter world, BlockPos feet, double floor) {
				for (PathGoal g : all) {
					List<BlockPos> clear = g.toClear(world, feet, floor);
					if (clear != null && !clear.isEmpty()) {
						return clear;
					}
				}
				return null;
			}
		};
	}

	/** This goal, or also where {@code also} holds (the work in place stays this goal's). */
	static PathGoal orAlso(PathGoal goal, PathGoal also) {
		return new PathGoal() {
			@Override
			public boolean test(BlockGetter world, BlockPos feet, double floor) {
				return goal.test(world, feet, floor) || also.test(world, feet, floor);
			}

			@Override
			public List<BlockPos> toClear(BlockGetter world, BlockPos feet, double floor) {
				return goal.toClear(world, feet, floor);
			}
		};
	}
}
