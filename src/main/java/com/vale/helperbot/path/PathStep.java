package com.vale.helperbot.path;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * One move between two foot cells: first mine {@code toBreak}, then place a block at
 * {@code place} (bridge: before moving; pillar: mid-jump), then move.
 */
public record PathStep(Type type, BlockPos from, BlockPos to, List<BlockPos> toBreak, BlockPos place) {
	public enum Type {
		WALK, ASCEND, DESCEND, PILLAR
	}

	public PathStep(Type type, BlockPos from, BlockPos to, List<BlockPos> toBreak) {
		this(type, from, to, toBreak, null);
	}
}
