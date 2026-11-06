package com.vale.helperbot.path.moves;

import com.vale.helperbot.path.Candidate;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * One kind of move the bot knows (walk, swim, tunnel, bridge, pillar...): which steps of that
 * kind lead out of a cell, and what each costs. The search asks every move in {@link Moves#ALL}
 * about every cell it expands; carrying a step out is up to the executor of its
 * {@link com.vale.helperbot.path.PathStep.Type} in the movement controller.
 * <p>
 * To add a move: a class here, an entry in {@link Moves#ALL}, and, if no existing step type moves
 * the bot the right way, a new step type with its executor (see bot/StepExecutor).
 */
public interface Move {
	/**
	 * Adds this kind's steps out of {@code feet}.
	 *
	 * @param floor feet height there
	 * @param from  where it stands: what it may do from there (see {@link From})
	 */
	void addSteps(Planner pf, BlockPos feet, double floor, From from, List<Candidate> out);

	/**
	 * @param digPenalty how much slower mining is from here (head under water, floating)
	 * @param canAct     not in a protected chunk: it may dig and build (there it's a ghost)
	 * @param standing   on a full block: digging and building assume a firm stand
	 * @param afloat     at the surface with its head out: it can dig from there (the gate only asks
	 *                   where the head is, see Guard#ofTheSea), at digPenalty, but not build
	 */
	record From(double digPenalty, boolean canAct, boolean standing, boolean afloat) {
	}
}
