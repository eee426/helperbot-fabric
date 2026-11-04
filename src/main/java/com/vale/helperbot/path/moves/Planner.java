package com.vale.helperbot.path.moves;

import com.vale.helperbot.path.LocalPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

import java.util.List;

/**
 * What a {@link Move} may ask of the search it plans for: the world as the search sees it (a copy
 * when it runs in the background), the search window, and the per-search memory of each cell
 * (standable, passable, cost to dig). Implemented by {@link LocalPathfinder}.
 */
public interface Planner {
	BlockGetter world();

	BlockPos origin();

	LocalPathfinder.Limits limits();

	boolean inWindow(BlockPos pos);

	/** Feet may be in this cell (no collision, no liquid, nothing harmful), as far as this search knows. */
	boolean passable(BlockPos pos);

	/** Feet height when standing in this cell, or NaN if it can't stand there. */
	double floor(BlockPos pos);

	/** Passable, or open water it can swim through. */
	boolean swimmable(BlockPos pos);

	boolean water(BlockPos pos);

	/** A full block to stand on. */
	boolean support(BlockPos pos);

	/** Lava within the mode's margin of these feet. */
	boolean nearLava(BlockPos feet);

	/** May the bot put a block here (rules, protection)? */
	boolean canPlace(BlockPos pos);

	/**
	 * Cost to clear this cell: 0 if already open, negative if it may not be cleared. Cells to mine
	 * are added to {@code breaks}; the real mining time adds up in {@link #dugTicks()}.
	 */
	double clearCost(BlockPos pos, List<BlockPos> breaks, double penalty);

	/** Starts counting real mining time for a new step (see {@link #clearCost}). */
	void resetDig();

	double dugTicks();

	/** Ticks per block walking, at this mode's speed. */
	double walkCost();

	/** Highest drop onto solid ground allowed now (limited by health). */
	int maxDrop();

	/** Open rules and something to build with: bridges and pillars are possible. */
	boolean canBuild();

	/** It may sprint-swim where fully under water (see Air#SPRINT_SWIM_SPEED): food enough, a mode that sprints. */
	boolean sprintSwim();
}
