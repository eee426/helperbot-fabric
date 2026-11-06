package com.vale.helperbot.path.moves;

import com.vale.helperbot.path.Air;
import com.vale.helperbot.path.Candidate;
import com.vale.helperbot.path.PathStep;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * From water, swim to any neighbouring water cell: level, diving down, or rising (3D).
 */
final class SwimMove implements Move {
	@Override
	public void addSteps(Planner pf, BlockPos p, double cur, From from, List<Candidate> out) {
		// Feet or head in water: with the feet in an air pocket (a block just dug out) and the head in
		// the water above, it swims up like from anywhere else. It used to need the feet in water: from
		// such a pocket nothing led up, and the breathing layer had to pull it out.
		if (!pf.water(p) && !pf.water(p.above())) {
			return;
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
					boolean sprint = pf.sprintSwim() && Air.submerged(pf.world(), p) && Air.submerged(pf.world(), c);
					double ticks = Air.swimTicks(dx, dy, dz, sprint);
					out.add(new Candidate(new PathStep(PathStep.Type.WALK, p, c, List.of()), f, ticks));
				}
			}
		}
	}
}
