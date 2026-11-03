package com.vale.helperbot.path.moves;

import com.vale.helperbot.path.Air;
import com.vale.helperbot.path.Candidate;
import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.PathStep;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Straight down into the cell underfoot: dig it out and drop in (standing), or, if it's open
 * already (an air pocket left by a block just dug out under water, before the water flows in),
 * just drop in. Without the second, nothing led into an open cell right below: walking only goes
 * sideways, swimming only into water; the bot hovered over a drop in such a pocket and found no way to it.
 */
final class DigDownMove implements Move {
	/** One block down: swimming in water, falling otherwise. */
	static double down(Planner pf, BlockPos p) {
		return pf.water(p) ? Air.swimTicks(0, 1, 0) : LocalPathfinder.fallTicks(1);
	}

	@Override
	public void addSteps(Planner pf, BlockPos p, double cur, From from, List<Candidate> out) {
		BlockPos below = p.below();
		if (!pf.inWindow(below)) {
			return;
		}
		if (pf.passable(below)) {
			double f = pf.floor(below);
			if (!Double.isNaN(f) && f < cur - 1e-3) {
				// In water it goes down at swimming speed, not like a fall (see Air#swimTicks).
				double ticks = pf.water(p) ? Air.swimTicks(0, cur - f, 0) : LocalPathfinder.fallTicks(cur - f);
				out.add(new Candidate(new PathStep(PathStep.Type.DESCEND, p, below, List.of()), f, ticks));
			}
			return;
		}
		if (!from.canAct() || !from.standing()) {
			return;
		}
		List<BlockPos> breaks = new ArrayList<>(1);
		pf.resetDig();
		double dig = pf.clearCost(below, breaks, from.digPenalty());
		if (dig > 0 && pf.support(below.below()) && !pf.nearLava(below)) {
			out.add(new Candidate(new PathStep(PathStep.Type.DESCEND, p, below, breaks), below.getY(),
					down(pf, p) + dig, down(pf, p) + pf.dugTicks()));
		}
	}
}
