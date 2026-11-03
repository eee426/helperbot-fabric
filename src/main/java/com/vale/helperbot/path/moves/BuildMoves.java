package com.vale.helperbot.path.moves;

import com.vale.helperbot.path.Candidate;
import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.PathStep;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/** Moves that place a block: bridge forward, pillar up. Open rules and blocks in the bag only. */
final class BuildMoves {
	private BuildMoves() {
	}

	/**
	 * Place a block in front, below foot level, and walk onto it (over a gap or water).
	 *
	 * <p>No "is there something to place against" check against the current world: we only get
	 * here standing on a full block, and the new block goes right next to it. That block may be
	 * one this same path places a step earlier (a bridge or pillar still in the plan), which the
	 * world doesn't contain yet; checking the world would stop every bridge after its first block.
	 */
	static final class Bridge implements Move {
		@Override
		public void addSteps(Planner pf, BlockPos p, double cur, From from, List<Candidate> out) {
			if (!from.canAct() || !from.standing() || !pf.canBuild()) {
				return;
			}
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos t = p.relative(d);
				BlockPos under = t.below();
				if (!pf.inWindow(t) || !pf.passable(t) || !pf.passable(t.above()) || !Double.isNaN(pf.floor(t))
						|| !pf.canPlace(under) || pf.nearLava(t)) {
					continue;
				}
				out.add(new Candidate(new PathStep(PathStep.Type.WALK, p, t, List.of(), under), t.getY(),
						pf.walkCost() + LocalPathfinder.PLACE_COST));
			}
		}
	}

	/** Jump and place a block under ourselves. */
	static final class Pillar implements Move {
		@Override
		public void addSteps(Planner pf, BlockPos p, double cur, From from, List<Candidate> out) {
			if (!from.canAct() || !from.standing() || !pf.canBuild()) {
				return;
			}
			BlockPos up = p.above();
			if (!pf.inWindow(up) || !pf.canPlace(p) || pf.water(p)) {
				return;
			}
			List<BlockPos> breaks = new ArrayList<>(1);
			pf.resetDig();
			double dig = pf.clearCost(p.above(2), breaks, from.digPenalty());
			if (dig >= 0 && !pf.nearLava(up)) {
				out.add(new Candidate(new PathStep(PathStep.Type.PILLAR, p, up, breaks, p), up.getY(),
						LocalPathfinder.ASCEND_EXTRA + LocalPathfinder.PLACE_COST + dig,
						LocalPathfinder.ASCEND_EXTRA + LocalPathfinder.PLACE_COST + pf.dugTicks()));
			}
		}
	}
}
