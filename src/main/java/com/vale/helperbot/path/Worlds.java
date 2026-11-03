package com.vale.helperbot.path;

import com.vale.helperbot.knowledge.Habitat;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;

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

	public static ResourceKey<Level> dimension(BlockGetter world) {
		if (world instanceof Snapshot s) {
			return s.dimension();
		}
		return ((Level) world).dimension();
	}

	/** Y of the first free cell above the ground in this column (see {@link Habitat#surfaceY}). */
	public static int surfaceY(BlockGetter world, int x, int z) {
		if (world instanceof Snapshot s) {
			return s.surfaceY(x, z);
		}
		return Habitat.surfaceY((ServerLevel) world, x, z);
	}
}
