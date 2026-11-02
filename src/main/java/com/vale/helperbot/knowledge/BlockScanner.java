package com.vale.helperbot.knowledge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * Finds blocks in already-loaded chunks around a point, top to bottom of the world. Sections whose
 * palette can't contain a match are skipped without looking at their blocks, so this is cheap.
 */
public final class BlockScanner {
	private BlockScanner() {
	}

	/**
	 * Up to {@code max} matching positions, nearest first. Never loads or generates chunks. Keeps
	 * only the nearest {@code max} while it looks, goes out ring by ring, and skips every section
	 * (and stops at a ring) that can't hold anything nearer than those: for common blocks (stone,
	 * deepslate far below) that's a few sections instead of millions of positions (deepslate alone
	 * took half a second per plan).
	 */
	public static List<BlockPos> find(ServerLevel level, BlockPos origin, int chunkRadius,
									  Predicate<BlockState> match, Predicate<BlockPos> accept, int max) {
		Nearest nearest = new Nearest(origin, max);
		int ocx = origin.getX() >> 4, ocz = origin.getZ() >> 4;
		for (int ring = 0; ring <= chunkRadius; ring++) {
			for (int cx = ocx - ring; cx <= ocx + ring; cx++) {
				for (int cz = ocz - ring; cz <= ocz + ring; cz++) {
					if (Math.max(Math.abs(cx - ocx), Math.abs(cz - ocz)) == ring) {
						scanChunk(level, cx, cz, match, accept, nearest);
					}
				}
			}
			// Everything in the next ring is at least this far away (horizontally).
			double next = ring * 16.0;
			if (nearest.full() && nearest.worst() <= next * next) {
				break;
			}
		}
		List<BlockPos> found = new ArrayList<>(nearest.heap);
		found.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
		return found;
	}

	/** The nearest {@code max} so far (farthest of them on top). */
	private static final class Nearest {
		final BlockPos origin;
		final int max;
		final PriorityQueue<BlockPos> heap;

		Nearest(BlockPos origin, int max) {
			this.origin = origin;
			this.max = max;
			this.heap = new PriorityQueue<>(Comparator.comparingDouble((BlockPos p) -> p.distSqr(origin)).reversed());
		}

		boolean full() {
			return heap.size() >= max;
		}

		double worst() {
			return heap.isEmpty() ? Double.MAX_VALUE : heap.peek().distSqr(origin);
		}

		/** Could anything at least {@code distSq} away still make it in? */
		boolean wants(double distSq) {
			return !full() || distSq < worst();
		}

		void offer(BlockPos pos) {
			if (!full()) {
				heap.add(pos);
			} else if (pos.distSqr(origin) < worst()) {
				heap.poll();
				heap.add(pos);
			}
		}
	}

	private static void scanChunk(ServerLevel level, int cx, int cz, Predicate<BlockState> match,
								  Predicate<BlockPos> accept, Nearest nearest) {
		LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
		if (chunk == null) {
			return;
		}
		BlockPos o = nearest.origin;
		double dx = gap(o.getX(), cx << 4), dz = gap(o.getZ(), cz << 4);
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
			double dy = gap(o.getY(), baseY);
			if (!nearest.wants(dx * dx + dy * dy + dz * dz) || section.hasOnlyAir() || !section.maybeHas(match)) {
				continue;
			}
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (match.test(section.getBlockState(x, y, z))) {
							BlockPos pos = new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z);
							if (nearest.wants(pos.distSqr(o)) && accept.test(pos)) {
								nearest.offer(pos);
							}
						}
					}
				}
			}
		}
	}

	/** Distance from {@code v} to the 16-wide range starting at {@code start} (0 if inside). */
	private static double gap(int v, int start) {
		return v < start ? start - v : v > start + 15 ? v - (start + 15) : 0;
	}
}
