package com.vale.helperbot.path.moves;

import com.vale.helperbot.path.Candidate;
import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.PathStep;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/** Moves that need mining first, standing: tunnel forward, dig a step up, dig a stair down (4 directions). */
final class DigMove implements Move {
	@Override
	public void addSteps(Planner pf, BlockPos p, double cur, From from, List<Candidate> out) {
		if (!from.canAct() || !from.standing()) {
			return;
		}
		for (Direction d : Direction.Plane.HORIZONTAL) {
			digInto(pf, p, d, from.digPenalty(), out);
		}
	}

	private static void digInto(Planner pf, BlockPos p, Direction d, double pen, List<Candidate> out) {
		BlockPos t = p.relative(d);
		if (!pf.inWindow(t)) {
			return;
		}
		double walk = pf.walkCost();
		// Tunnel forward.
		List<BlockPos> breaks = new ArrayList<>(2);
		pf.resetDig();
		double dig = pf.clearCost(t, breaks, pen) + pf.clearCost(t.above(), breaks, pen);
		if (dig >= 0 && !breaks.isEmpty() && pf.support(t.below()) && !pf.nearLava(t)) {
			out.add(new Candidate(new PathStep(PathStep.Type.WALK, p, t, breaks), t.getY(), walk + dig, walk + pf.dugTicks()));
		}

		// Dig out a step and climb onto it.
		BlockPos up = t.above();
		breaks = new ArrayList<>(3);
		pf.resetDig();
		dig = pf.clearCost(p.above(2), breaks, pen) + pf.clearCost(up, breaks, pen) + pf.clearCost(up.above(), breaks, pen);
		if (dig >= 0 && !breaks.isEmpty() && pf.support(t) && !pf.nearLava(up)) {
			out.add(new Candidate(new PathStep(PathStep.Type.ASCEND, p, up, breaks), up.getY(),
					walk + LocalPathfinder.ASCEND_EXTRA + dig, walk + LocalPathfinder.ASCEND_EXTRA + pf.dugTicks()));
		}

		// Dig a stair down.
		BlockPos down = t.below();
		breaks = new ArrayList<>(3);
		pf.resetDig();
		dig = pf.clearCost(t.above(), breaks, pen) + pf.clearCost(t, breaks, pen) + pf.clearCost(down, breaks, pen);
		if (dig >= 0 && !breaks.isEmpty() && pf.support(down.below()) && !pf.nearLava(down)) {
			out.add(new Candidate(new PathStep(PathStep.Type.DESCEND, p, down, breaks), down.getY(),
					walk + LocalPathfinder.fallTicks(1) + dig, walk + LocalPathfinder.fallTicks(1) + pf.dugTicks()));
		}
	}
}
