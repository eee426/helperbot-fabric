package com.vale.helperbot.path;

import com.vale.helperbot.guard.InDimension;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.material.FluidState;

/**
 * A copy of the blocks around a path search, taken on the server thread, so the search can run
 * on another thread without touching the live world (which the server keeps changing meanwhile).
 * Copying is cheap: the game keeps each 16x16x16 section as one packed array.
 * <p>
 * Outside the copied box it reads like an unloaded chunk (nothing there, can't go). Block
 * entities aren't copied: nothing a search asks needs them.
 */
public final class Snapshot implements BlockGetter, InDimension {
	private static final BlockState VOID = Blocks.VOID_AIR.defaultBlockState();

	private final ResourceKey<Level> dimension;
	/** The dimension has a roof (the Nether): see Worlds#hasCeiling. */
	final boolean hasCeiling;
	private final int minY, height;
	/** Copied chunks: x from minCX, z from minCZ, sizeX by sizeZ; sections from minSection up. */
	private final int minCX, minCZ, sizeX, sizeZ, minSection, sections;
	/** Per chunk, per copied section: its states, or null if the chunk isn't loaded. Null section = all air. */
	private final PalettedContainer<BlockState>[][] states;
	private final boolean[] loadedChunks;
	/** Per chunk, per column: first free Y above the ground (MOTION_BLOCKING_NO_LEAVES). */
	private final int[][] surface;
	/** For columns outside the copy: the world generator's idea of the ground (thread safe, worldgen uses it so). */
	private final ChunkGenerator generator;
	private final RandomState randomState;
	private final Long2IntOpenHashMap generated = new Long2IntOpenHashMap();

	@SuppressWarnings("unchecked")
	private Snapshot(ServerLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		this.dimension = level.dimension();
		this.hasCeiling = level.dimensionType().hasCeiling();
		this.minY = level.getMinY();
		this.height = level.getHeight();
		this.generator = level.getChunkSource().getGenerator();
		this.randomState = level.getChunkSource().randomState();
		generated.defaultReturnValue(Integer.MIN_VALUE);
		minCX = minX >> 4;
		minCZ = minZ >> 4;
		sizeX = (maxX >> 4) - minCX + 1;
		sizeZ = (maxZ >> 4) - minCZ + 1;
		int lowest = level.getMinSectionY();
		minSection = Math.max(lowest, minY >> 4);
		int top = Math.min(level.getMaxSectionY(), maxY >> 4);
		sections = Math.max(0, top - minSection + 1);
		states = new PalettedContainer[sizeX * sizeZ][];
		loadedChunks = new boolean[sizeX * sizeZ];
		surface = new int[sizeX * sizeZ][];
		for (int i = 0; i < sizeX; i++) {
			for (int j = 0; j < sizeZ; j++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(minCX + i, minCZ + j);
				if (chunk == null) {
					continue;
				}
				int c = i * sizeZ + j;
				loadedChunks[c] = true;
				PalettedContainer<BlockState>[] copied = new PalettedContainer[sections];
				LevelChunkSection[] all = chunk.getSections();
				for (int s = 0; s < sections; s++) {
					LevelChunkSection section = all[minSection + s - lowest];
					if (!section.hasOnlyAir()) {
						copied[s] = section.getStates().copy();
					}
				}
				states[c] = copied;
				int[] heights = new int[256];
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						heights[x * 16 + z] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
					}
				}
				surface[c] = heights;
			}
		}
	}

	/**
	 * Copies what a search from {@code start} within {@code limits} may look at: the window, plus
	 * a margin for looking around a cell (lava nearby, the ground beside a shaft), and under water
	 * the way to air (sideways to an opening, then up: see {@link Escape}).
	 */
	public static Snapshot around(ServerLevel level, BlockPos start, LocalPathfinder.Limits limits, boolean underwater) {
		int side = limits.radiusXZ() + (underwater ? Escape.REACH_SIDEWAYS : 0) + 4;
		int up = limits.up() + (underwater ? Escape.REACH_UP : 0) + 4;
		int down = limits.down() + 4;
		return new Snapshot(level, start.getX() - side, start.getY() - down, start.getZ() - side,
				start.getX() + side, start.getY() + up, start.getZ() + side);
	}

	/** The chunks holding this box, these heights (see MovementController#findWay). */
	public static Snapshot box(ServerLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		return new Snapshot(level, minX, minY, minZ, maxX, maxY, maxZ);
	}

	/** For the log: how much of the copied box is there. */
	public String describe() {
		int loaded = 0;
		for (boolean b : loadedChunks) {
			if (b) {
				loaded++;
			}
		}
		return "快照 " + loaded + "/" + loadedChunks.length + " 个区块已加载，高度 "
				+ (minSection * 16) + "~" + ((minSection + sections) * 16 - 1);
	}

	@Override
	public ResourceKey<Level> dimension() {
		return dimension;
	}

	private int chunk(int x, int z) {
		int i = (x >> 4) - minCX, j = (z >> 4) - minCZ;
		return i < 0 || i >= sizeX || j < 0 || j >= sizeZ ? -1 : i * sizeZ + j;
	}

	public boolean loaded(BlockPos pos) {
		int c = chunk(pos.getX(), pos.getZ());
		int s = (pos.getY() >> 4) - minSection;
		return c >= 0 && loadedChunks[c] && s >= 0 && s < sections;
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		int c = chunk(pos.getX(), pos.getZ());
		int s = (pos.getY() >> 4) - minSection;
		if (c < 0 || !loadedChunks[c] || s < 0 || s >= sections) {
			return VOID;
		}
		PalettedContainer<BlockState> section = states[c][s];
		return section == null ? Blocks.AIR.defaultBlockState()
				: section.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Override
	public BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}

	/** The terrain's top here, as world generation made it, whatever has been built or dug since. */
	public int groundY(int x, int z) {
		long key = BlockPos.asLong(x, 0, z);
		int y = generated.get(key);
		if (y == Integer.MIN_VALUE) {
			y = generator.getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, this, randomState);
			generated.put(key, y);
		}
		return y;
	}

	public int surfaceY(int x, int z) {
		int c = chunk(x, z);
		if (c >= 0 && loadedChunks[c]) {
			return surface[c][(x & 15) * 16 + (z & 15)];
		}
		long key = BlockPos.asLong(x, 0, z);
		int y = generated.get(key);
		if (y == Integer.MIN_VALUE) {
			y = generator.getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, this, randomState);
			generated.put(key, y);
		}
		return y;
	}

	@Override
	public int getHeight() {
		return height;
	}

	@Override
	public int getMinY() {
		return minY;
	}
}
