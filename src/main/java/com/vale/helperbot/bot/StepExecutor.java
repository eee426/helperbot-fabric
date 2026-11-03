package com.vale.helperbot.bot;

import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.PathStep;
import net.minecraft.server.level.ServerLevel;

/**
 * Carries out one type of path step, a tick at a time, once the step's mining (and bridge block)
 * is done; swimming water to water is the movement controller's own. One per
 * {@link PathStep.Type}, registered in the movement controller. A new way of moving (a ladder, say)
 * gets a step type, an executor here, and a planner move (path/moves).
 */
interface StepExecutor {
	/** Feet height the step ends at, or NaN if there's nowhere to stand there any more. */
	default double targetFloor(ServerLevel level, PathStep step) {
		return LocalPathfinder.floorHeight(level, step.to());
	}

	/** One tick of the move towards {@code ty}; calls {@code m.nextStep()} on arrival. */
	void tick(MovementController m, PathStep step, double ty);
}
