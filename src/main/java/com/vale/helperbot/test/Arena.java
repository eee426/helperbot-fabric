package com.vale.helperbot.test;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * A scenario's patch of the test world: a superflat plains (bedrock, stone up to y=36, dirt,
 * grass on top at y=40, so you stand at y=41), far from every other scenario. Coordinates given
 * to the helpers are relative to {@link #origin}, a point on the grass surface (y=41 is the air
 * above it, y=0 is 41 blocks down in solid stone).
 *
 * <p>A superflat world generates no trees or ores, so "which biome makes this" has no answer
 * here: whatever a scene needs must be within the bot's scan range (4 chunks).
 */
public final class Arena {
	/** Where you stand on the surface of the flat world. */
	public static final int SURFACE = 41;

	public final ServerLevel level;
	public final BlockPos origin;

	Arena(ServerLevel level, BlockPos origin) {
		this.level = level;
		this.origin = origin;
	}

	public BlockPos at(int x, int y, int z) {
		return new BlockPos(origin.getX() + x, y, origin.getZ() + z);
	}

	public void set(int x, int y, int z, Block block) {
		level.setBlock(at(x, y, z), block.defaultBlockState(), Block.UPDATE_ALL);
	}

	public void set(int x, int y, int z, BlockState state) {
		level.setBlock(at(x, y, z), state, Block.UPDATE_ALL);
	}

	/** Fill the box between two corners (inclusive) with a block. */
	public void fill(int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
					set(x, y, z, block);
				}
			}
		}
	}

	/** An oak tree: a trunk of {@code height} logs on the grass and a blob of leaves around the top. */
	public void tree(int x, int z, int height) {
		int top = SURFACE + height - 1;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = -1; dy <= 1; dy++) {
					if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy) <= 3) {
						set(x + dx, top + dy, z + dz, Blocks.OAK_LEAVES.defaultBlockState()
								.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
					}
				}
			}
		}
		set(x, top + 2, z, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
		for (int y = SURFACE; y <= top; y++) {
			set(x, y, z, Blocks.OAK_LOG);
		}
	}

	/** Spawned like a natural mob (not marked persistent: the bot leaves those alone, they may be someone's). */
	public <T extends Entity> T spawn(EntityType<T> type, int x, int y, int z) {
		return type.spawn(level, at(x, y, z), EntitySpawnReason.NATURAL);
	}

	/** Remove the animals and monsters the world generated around here, so only the scenario's own count. */
	void clearMobs(int radius) {
		AABB box = new AABB(at(-radius, level.getMinY(), -radius).getCenter(), at(radius, level.getMaxY(), radius).getCenter());
		for (Mob mob : level.getEntitiesOfClass(Mob.class, box)) {
			mob.discard();
		}
	}
}
