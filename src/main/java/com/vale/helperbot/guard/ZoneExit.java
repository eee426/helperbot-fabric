package com.vale.helperbot.guard;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * The way out of a protected area, worked out from the table of protected chunks alone: it needs
 * no loaded blocks, so it knows about all of an area however far it runs past the view distance,
 * whatever its shape. Inside means protected, or a gap walled in by protected chunks all round:
 * standing in such a hole, it's still in the middle of the base.
 *
 * <p>Made on the server thread for one trip, unchanged after that, so a search may read it.
 */
public final class ZoneExit {
	/** A gap in the chunks with more than this many chunks of its own is open country. */
	private static final int MAX_HOLE = 1024;
	/** Most chunks of one area looked at. */
	private static final int MAX_AREA = 40_000;
	private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	/** Chunks of this area, and how many chunks each is from outside. */
	private final Long2IntOpenHashMap steps = new Long2IntOpenHashMap();
	/** Chunks just outside it. */
	private final LongOpenHashSet edge = new LongOpenHashSet();

	private ZoneExit() {
	}

	/** The area around {@code pos}, or null if it's out in the open. */
	public static ZoneExit at(Level level, BlockPos pos) {
		ChunkPos start = new ChunkPos(pos);
		Holes holes = new Holes(level);
		if (!holes.inside(start.toLong())) {
			return null;
		}
		ZoneExit exit = new ZoneExit();
		exit.steps.defaultReturnValue(-1);
		exit.fill(start.toLong(), holes);
		return exit;
	}

	/** Takes in the area, then numbers its chunks going inwards from the edge. */
	private void fill(long start, Holes holes) {
		LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
		steps.put(start, 0);
		queue.enqueue(start);
		while (!queue.isEmpty() && steps.size() < MAX_AREA) {
			long c = queue.dequeueLong();
			for (int[] side : SIDES) {
				long n = ChunkPos.asLong(ChunkPos.getX(c) + side[0], ChunkPos.getZ(c) + side[1]);
				if (steps.containsKey(n) || edge.contains(n)) {
					continue;
				}
				if (holes.inside(n)) {
					steps.put(n, 0);
					queue.enqueue(n);
				} else {
					edge.add(n);
				}
			}
		}
		for (long c : steps.keySet()) {
			steps.put(c, -1);
		}
		LongOpenHashSet from = new LongOpenHashSet(edge);
		int ring = 0;
		while (!from.isEmpty()) {
			ring++;
			LongOpenHashSet next = new LongOpenHashSet();
			for (long c : from) {
				for (int[] side : SIDES) {
					long n = ChunkPos.asLong(ChunkPos.getX(c) + side[0], ChunkPos.getZ(c) + side[1]);
					if (steps.get(n) == -1 && steps.containsKey(n)) {
						steps.put(n, ring);
						next.add(n);
					}
				}
			}
			from = next;
		}
	}

	/** In this area (or in a hole in it). */
	public boolean contains(BlockPos pos) {
		return steps.containsKey(chunk(pos));
	}

	/** Really out: not in this area, and not in another one it walked into. */
	public boolean outside(BlockGetter level, BlockPos pos) {
		return !contains(pos) && !ProtectionZones.isProtected(level, pos);
	}

	/** Blocks to outside at the very least, going by whole chunks; 0 in a chunk at the edge. */
	public double blocksOut(BlockPos pos) {
		return Math.max(0, steps.get(chunk(pos)) - 1) * 16.0;
	}

	/** The middle of the nearest chunk outside, at the height of {@code from}. */
	public BlockPos nearestOutside(BlockPos from) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (long c : edge) {
			BlockPos p = new BlockPos(ChunkPos.getX(c) * 16 + 8, from.getY(), ChunkPos.getZ(c) * 16 + 8);
			double d = p.distSqr(from);
			if (d < bestDist) {
				bestDist = d;
				best = p;
			}
		}
		return best == null ? from : best;
	}

	private static long chunk(BlockPos pos) {
		return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
	}

	/** Which unprotected chunks are shut in by protected ones: what can be walked to from there is small. */
	private static final class Holes {
		private final Level level;
		private final LongOpenHashSet open = new LongOpenHashSet();
		private final LongOpenHashSet shut = new LongOpenHashSet();

		Holes(Level level) {
			this.level = level;
		}

		private boolean isProtected(long c) {
			return ProtectionZones.isProtected(level, new ChunkPos(ChunkPos.getX(c), ChunkPos.getZ(c)));
		}

		boolean inside(long c) {
			if (isProtected(c)) {
				return true;
			}
			if (open.contains(c) || shut.contains(c)) {
				return shut.contains(c);
			}
			LongOpenHashSet seen = new LongOpenHashSet();
			LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
			seen.add(c);
			queue.enqueue(c);
			boolean escaped = false;
			while (!queue.isEmpty() && !escaped) {
				long at = queue.dequeueLong();
				for (int[] side : SIDES) {
					long n = ChunkPos.asLong(ChunkPos.getX(at) + side[0], ChunkPos.getZ(at) + side[1]);
					if (open.contains(n) || seen.size() > MAX_HOLE) {
						escaped = true;
						break;
					}
					if (!isProtected(n) && seen.add(n)) {
						queue.enqueue(n);
					}
				}
			}
			(escaped ? open : shut).addAll(seen);
			return !escaped;
		}
	}
}
