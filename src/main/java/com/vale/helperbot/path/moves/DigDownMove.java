package com.vale.helperbot.path.moves;

import com.vale.helperbot.path.Candidate;
import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.PathStep;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Dig out the block underfoot and drop into its cell, standing. */
final class DigDownMove implements Move {
	@Override
	public void addSteps(Planner pf, BlockPos p, double cur, From from, List<Candidate> out) {
		if (!from.canAct() || !from.standing()) {
			return;
		}
		BlockPos below = p.below();
		if (!pf.inWindow(below)) {
			return;
		}
		List<BlockPos> breaks = new ArrayList<>(1);
		pf.resetDig();
		double dig = pf.clearCost(below, breaks, from.digPenalty());
		if (dig > 0 && pf.support(below.below()) && !pf.nearLava(below)) {
			out.add(new Candidate(new PathStep(PathStep.Type.DESCEND, p, below, breaks), below.getY(),
					LocalPathfinder.fallTicks(1) + dig, LocalPathfinder.fallTicks(1) + pf.dugTicks()));
		}
	}
}
