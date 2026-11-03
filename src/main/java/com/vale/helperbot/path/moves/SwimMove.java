package com.vale.helperbot.path.moves;

import com.vale.helperbot.path.Air;
import com.vale.helperbot.path.Candidate;
import com.vale.helperbot.path.Escape;
import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.PathStep;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * From water, swim to any neighbouring water cell: level, diving down, or rising (3D). Also
 * dig straight up through a ceiling (sand that fell in, the sea floor from below).
 */
final class SwimMove implements Move {
	@Override
	public void addSteps(Planner pf, BlockPos p, double cur, From from, List<Candidate> out) {
		if (!pf.water(p)) {
			return;
		}
		BlockPos up = p.above();
		BlockPos overHead = up.above();
		if (from.canAct() && pf.inWindow(overHead) && pf.swimmable(up) && !pf.swimmable(overHead)) {
			List<BlockPos> breaks = new ArrayList<>(1);
			pf.resetDig();
			double dig = pf.clearCost(overHead, breaks, from.digPenalty());
			if (dig > 0) {
				// Once dug, water pours in (or it's air, if the ceiling was the last thing in the way).
				double f = Escape.floodsWhenDug(pf.world(), overHead) || pf.water(up) ? up.getY() : up.getY() + LocalPathfinder.SWIM_FLOOR;
				out.add(new Candidate(new PathStep(PathStep.Type.WALK, p, up, breaks), f,
						dig + 1 / Air.SWIM_UP_SPEED, pf.dugTicks() + 1 / Air.SWIM_UP_SPEED));
			}
		}
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					if ((dx == 0 && dy == 0 && dz == 0) || (dx != 0 && dz != 0)) {
						continue;
					}
					BlockPos c = p.offset(dx, dy, dz);
					if (!pf.inWindow(c) || !pf.water(c)) {
						continue;
					}
					// Moving up/down and sideways at once: both corner cells must be open too.
					if (dy != 0 && (dx != 0 || dz != 0)
							&& !(pf.swimmable(p.offset(dx, 0, dz)) && pf.swimmable(p.offset(0, dy, 0)))) {
						continue;
					}
					double f = pf.floor(c);
					if (Double.isNaN(f)) {
						continue;
					}
					boolean submerged = pf.water(c.above());
					double ticks = Air.swimTicks(dx, dy, dz);
					out.add(new Candidate(new PathStep(PathStep.Type.WALK, p, c, List.of()), f,
							ticks * (submerged ? LocalPathfinder.DIVE_COST_FACTOR : 1), ticks));
				}
			}
		}
	}
}
