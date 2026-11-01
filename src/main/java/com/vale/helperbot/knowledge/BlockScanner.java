package com.vale.helperbot.knowledge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Finds blocks in already-loaded chunks around a point, top to bottom of the world. Sections whose
 * palette can't contain a match are skipped without looking at their blocks, so this is cheap.
 */
public final class BlockScanner {
	private BlockScanner() {
	}

	/** Up to {@code max} matching positions, nearest first. Never loads or generates chunks. */
	public static List<BlockPos> find(ServerLevel level, BlockPos origin, int chunkRadius,
									  Predicate<BlockState> match, Predicate<BlockPos> accept, int max) {
		List<BlockPos> found = new ArrayList<>();
		int ocx = origin.getX() >> 4, ocz = origin.getZ() >> 4;
		for (int cx = ocx - chunkRadius; cx <= ocx + chunkRadius; cx++) {
			for (int cz = ocz - chunkRadius; cz <= ocz + chunkRadius; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					LevelChunkSection section = sections[i];
					if (section.hasOnlyAir() || !section.maybeHas(match)) {
						continue;
					}
					int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
					for (int y = 0; y < 16; y++) {
						for (int z = 0; z < 16; z++) {
							for (int x = 0; x < 16; x++) {
								if (match.test(section.getBlockState(x, y, z))) {
									BlockPos pos = new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z);
									if (accept.test(pos)) {
										found.add(pos);
									}
								}
							}
						}
					}
				}
			}
		}
		found.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
		return found.size() > max ? new ArrayList<>(found.subList(0, max)) : found;
	}
}
