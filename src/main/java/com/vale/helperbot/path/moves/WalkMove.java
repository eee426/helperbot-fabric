package com.vale.helperbot.path.moves;

import com.vale.helperbot.path.Air;
import com.vale.helperbot.path.Candidate;
import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.PathStep;
import net.minecraft.core.BlockPos;

import java.util.List;

/** Into a neighbouring column (8 directions) without digging: walk, step up, jump up, or drop. */
final class WalkMove implements Move {
	@Override
	public void addSteps(Planner pf, BlockPos p, double cur, From from, List<Candidate> out) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx != 0 || dz != 0) {
					walkInto(pf, p, cur, dx, dz, out);
				}
			}
		}
	}

	private static void walkInto(Planner pf, BlockPos p, double cur, int dx, int dz, List<Candidate> out) {
		boolean diagonal = dx != 0 && dz != 0;
		BlockPos col = p.offset(dx, 0, dz);
		if (diagonal && !(columnOpen(pf, p.offset(dx, 0, 0)) && columnOpen(pf, p.offset(0, 0, dz)))) {
			return;
		}
		boolean entryClear = pf.passable(col) && pf.passable(col.above());
		int top = (int) Math.floor(cur + LocalPathfinder.JUMP_HEIGHT);
		// Scan the whole window down: solid landings are capped by maxDrop below, water isn't.
		for (int y = top; y >= pf.origin().getY() - pf.limits().down(); y--) {
			BlockPos c = new BlockPos(col.getX(), y, col.getZ());
			if (!pf.inWindow(c)) {
				continue;
			}
			double f = pf.floor(c);
			if (!Double.isNaN(f)) {
				double dy = f - cur;
				boolean intoWater = pf.water(c);
				if (dy > LocalPathfinder.JUMP_HEIGHT || (dy < -LocalPathfinder.STEP_HEIGHT && !entryClear)
						|| (diagonal && Math.abs(dy) > LocalPathfinder.STEP_HEIGHT)
						|| (-dy > pf.maxDrop() + 0.01 && !intoWater)) {
					return;
				}
				PathStep.Type type;
				boolean swimming = intoWater || pf.water(p);
				double cost = (swimming ? 1.0 / LocalPathfinder.SWIM_SPEED : pf.walkCost()) * (diagonal ? Math.sqrt(2) : 1);
				if (dy > LocalPathfinder.STEP_HEIGHT) {
					// Going up a stair from its low side is two half steps, no jump: headroom for
					// standing up a block higher is enough. Anything else is jumped, and a jump arcs
					// higher than the ledge. (Stairs used to count as a jump too, so under a low
					// ceiling, a cellar staircase say, it could walk down but never back up.)
					boolean stair = !diagonal && LocalPathfinder.stairStep(pf.world(), c, dx, dz) && dy <= 1 + 1e-3;
					int headTop = (int) Math.floor(cur + (stair ? dy : LocalPathfinder.JUMP_HEIGHT) + LocalPathfinder.PLAYER_HEIGHT - 1e-4);
					for (int hy = p.getY() + 1; hy <= headTop; hy++) {
						if (!pf.passable(new BlockPos(p.getX(), hy, p.getZ()))) {
							return;
						}
					}
					type = PathStep.Type.ASCEND;
					cost += stair ? 0 : LocalPathfinder.ASCEND_EXTRA;
				} else if (dy < -LocalPathfinder.STEP_HEIGHT) {
					type = PathStep.Type.DESCEND;
					double drop = -dy;
					// Water breaks the fall, so only solid landings pay for damage. Starting in water it goes
					// down at swimming speed, not like a fall: charged as a fall, every block down under
					// water was planned 7 ticks short, and deep down the breath ran out before the plan's.
					cost += (pf.water(p) ? Air.swimTicks(0, drop, 0) : LocalPathfinder.fallTicks(drop))
							+ (intoWater ? 0 : Math.max(0, drop - 3) * 10);
				} else {
					type = PathStep.Type.WALK;
				}
				out.add(new Candidate(new PathStep(type, p, c, List.of()), f, cost));
				return;
			}
			if (!pf.passable(c) && y <= p.getY()) {
				return;
			}
		}
	}

	private static boolean columnOpen(Planner pf, BlockPos pos) {
		return pf.passable(pos) && pf.passable(pos.above());
	}
}
