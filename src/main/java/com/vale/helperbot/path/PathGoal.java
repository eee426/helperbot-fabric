package com.vale.helperbot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

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

	/**
	 * Blocks to mine from there, standing still, for the goal to hold: what's between the eye and a
	 * buried block (see MineSpot#reachGoal). The search makes them the route's last step (see
	 * PathStep.Type.MINE) and holds that to every rule a dig on the way is held to. Null or empty:
	 * no such work makes it the goal from there.
	 */
	default List<BlockPos> toClear(BlockGetter world, BlockPos feet, double floor) {
		return null;
	}

	/** Any tool will do: the fastest (see Hands#AUTO_TOOL). */
	int ANY_TOOL = -3;

	/**
	 * What to mine one of the {@link #toClear} blocks with: {@link #ANY_TOOL}, or a bag slot when it
	 * matters what comes out of it (a block the task is after too, in the way of another).
	 */
	default int toolFor(BlockState state) {
		return ANY_TOOL;
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
		List<PathGoal> all = List.copyOf(goals);
		return new PathGoal() {
			@Override
			public boolean test(BlockGetter world, BlockPos feet) {
				return test(world, feet, Double.NaN);
			}

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

			@Override
			public int toolFor(BlockState state) {
				for (PathGoal g : all) {
					int tool = g.toolFor(state);
					if (tool != ANY_TOOL) {
						return tool;
					}
				}
				return ANY_TOOL;
			}
		};
	}

	/** This goal, or also where {@code also} holds (the work in place stays this goal's). */
	static PathGoal orAlso(PathGoal goal, AtFloor also) {
		return new PathGoal() {
			@Override
			public boolean test(BlockGetter world, BlockPos feet) {
				return test(world, feet, Double.NaN);
			}

			@Override
			public boolean test(BlockGetter world, BlockPos feet, double floor) {
				return goal.test(world, feet, floor) || also.test(world, feet, floor);
			}

			@Override
			public List<BlockPos> toClear(BlockGetter world, BlockPos feet, double floor) {
				return goal.toClear(world, feet, floor);
			}

			@Override
			public int toolFor(BlockState state) {
				return goal.toolFor(state);
			}
		};
	}
}
