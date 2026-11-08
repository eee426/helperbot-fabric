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
	 * The terrain's top in this column, as world generation made it: what's built on it (a house's
	 * roof) or dug out of it doesn't count. For where open ground is, not for what's there now.
	 */
	public static int groundY(BlockGetter world, int x, int z) {
		if (world instanceof Snapshot s) {
			return s.groundY(x, z);
		}
		ServerLevel level = (ServerLevel) world;
		return level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				level, level.getChunkSource().randomState());
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
