package com.vale.helperbot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The few things path code asks of "the world" beyond block and fluid states, answered for the
 * live level and for a search's {@link Snapshot} alike, so the same code runs on either.
 */
public final class Worlds {
	private Worlds() {
	}

	/** Is the chunk there (loaded live, or copied into the snapshot)? */
	public static boolean loaded(BlockGetter world, BlockPos pos) {
		if (world instanceof Snapshot s) {
			return s.loaded(pos);
		}
		return !(world instanceof LevelReader r) || r.hasChunkAt(pos);
	}

	/**
	 * Y of the first free cell above the ground in this column, where you'd stand: read from the
	 * world if that chunk is loaded, otherwise worked out from world generation without loading
	 * anything (like the biome search does).
	 */
	public static int surfaceY(BlockGetter world, int x, int z) {
		if (world instanceof Snapshot s) {
			return s.surfaceY(x, z);
		}
		ServerLevel level = (ServerLevel) world;
		if (level.hasChunkAt(new BlockPos(x, level.getMinY(), z))) {
			return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		}
		return level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				level, level.getChunkSource().randomState());
	}
}
