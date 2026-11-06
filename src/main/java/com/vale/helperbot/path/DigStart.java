package com.vale.helperbot.path;

import com.vale.helperbot.guard.Guard;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;

import java.util.ArrayDeque;

/**
 * Is there anywhere around to start digging? The head must be out of the water for that (see
 * Guard#ofTheSea), so what's buried is only got at from such a place, wherever it lies. In the
 * middle of the sea there is none, and everything under the sea bed is out of reach at one stroke:
 * that's no estimate, and it doesn't take a search for each block, which went on for minutes on end.
 * <p>
 * It goes over what it can walk or float on with its head out, and never dives: a sea this wide is a
 * few thousand cells. What can only be come at by swimming through a tunnel isn't seen.
 */
public final class DigStart {
	/** How far it looks: the land past this is for exploring to find. */
	private static final int RADIUS = 64;
	/** Blocks it can step up, and drop, going from cell to cell. */
	private static final int STEP_UP = 1, STEP_DOWN = 2;
	/** Blocks looked up through for the surface, from under water. */
	private static final int MAX_DEPTH = 64;

	private DigStart() {
	}

	/** Can it get from {@code feet} to somewhere it could dig from, head out? If it can't tell, yes. */
	public static boolean near(BlockGetter level, BlockPos feet) {
		BlockPos start = surfaced(level, feet);
		if (start == null) {
			return true; // under a roof: the planner will say
		}
		LongOpenHashSet seen = new LongOpenHashSet();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		seen.add(start.asLong());
		while (!queue.isEmpty()) {
			BlockPos c = queue.poll();
			if (canStart(level, c)) {
				return true;
			}
			for (Direction d : Direction.Plane.HORIZONTAL) {
				for (int dy = STEP_UP; dy >= -STEP_DOWN; dy--) {
					BlockPos n = c.relative(d).above(dy);
					if (Math.abs(n.getX() - start.getX()) <= RADIUS && Math.abs(n.getZ() - start.getZ()) <= RADIUS
							&& !seen.contains(n.asLong()) && headOut(level, n)) {
						seen.add(n.asLong());
						queue.add(n);
					}
				}
			}
		}
		return false;
	}

	/** Where it comes up from here, null if there's no way straight up. */
	private static BlockPos surfaced(BlockGetter level, BlockPos feet) {
		BlockPos c = feet;
		for (int i = 0; i < MAX_DEPTH; i++, c = c.above()) {
			if (headOut(level, c)) {
				return c;
			}
			if (!LocalPathfinder.isOpenWater(level, c.above())) {
				return null;
			}
		}
		return null;
	}

	private static boolean headOut(BlockGetter level, BlockPos c) {
		return !Double.isNaN(LocalPathfinder.floorHeight(level, c)) && Air.breathable(level, c);
	}

	/** On dry ground, on the bottom of the shallows, or afloat right next to something it can dig. */
	private static boolean canStart(BlockGetter level, BlockPos c) {
		if (!LocalPathfinder.isWater(level, c) || LocalPathfinder.isSupport(level, c.below())) {
			return true;
		}
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (diggable(level, c.relative(d)) || diggable(level, c.above().relative(d))) {
				return true;
			}
		}
		return false;
	}

	private static boolean diggable(BlockGetter level, BlockPos p) {
		return !LocalPathfinder.isPassable(level, p) && !LocalPathfinder.isWater(level, p) && Worlds.loaded(level, p)
				&& Guard.canBreak(level, p, level.getBlockState(p));
	}
}
