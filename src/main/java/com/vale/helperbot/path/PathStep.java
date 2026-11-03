package com.vale.helperbot.path;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * One move between two foot cells: first mine {@code toBreak}, then place a block at
 * {@code place} (bridge: before moving; pillar: mid-jump), then move.
 */
public record PathStep(Type type, BlockPos from, BlockPos to, List<BlockPos> toBreak, BlockPos place) {
	/**
	 * How the bot carries the step out (each has an executor in the movement controller). Planned
	 * by the moves in path/moves: one type may come from several (WALK: walking, swimming,
	 * tunnelling, bridging).
	 */
	public enum Type {
		WALK(false, false),
		ASCEND(false, true),
		DESCEND(false, false),
		PILLAR(true, true);

		/** Places its block during the move (pillar: mid-jump) rather than before setting off (bridge). */
		public final boolean placesWhileMoving;
		/** Rises from where it stands: must start from the middle of the cell (else the head hits the next column's ceiling). */
		public final boolean startsCentred;

		Type(boolean placesWhileMoving, boolean startsCentred) {
			this.placesWhileMoving = placesWhileMoving;
			this.startsCentred = startsCentred;
		}
	}

	public PathStep(Type type, BlockPos from, BlockPos to, List<BlockPos> toBreak) {
		this(type, from, to, toBreak, null);
	}
}
