package com.vale.helperbot.task;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What one order found it can't get to, so it doesn't go back and find out again. It keeps no
 * clock: each entry says what the finding rested on, and holds until that changes, whether that
 * takes a second or an hour. Being wrong costs little either way (a block skipped, or one more
 * short dive that finds the same), so it needn't be right for ever, and it ends with the order.
 * There's no wiping it to look again when nothing else is left: having gone looking elsewhere,
 * past {@link #SEARCHED} it no longer holds anyway (it used to, and went straight back to what had
 * just failed before going anywhere else).
 */
public final class OutOfReach {
	/**
	 * What a thorough finding rules out around the block, this far each way (5 x 5 x 5 with the block
	 * in the middle): the rest of that pocket, or it dives once for each. It was 4 each way, 9 x 9 x 9:
	 * more than a pocket, and a lot to lose when the finding was wrong.
	 */
	private static final int PATCH = 2;
	/** Past this from where it found out, it may come at it some way it never saw; and that's past what it scans for targets. */
	private static final int SEARCHED = 100;

	private record Entry(BlockPos pos, int breath, BlockPos from, int patch) {
	}

	private final List<Entry> entries = new ArrayList<>();
	/** Judged by estimate, for that block alone, wherever it stands; with the breath it had. Thousands under a sea: looked up, not gone through. */
	private final Map<BlockPos, Integer> judged = new HashMap<>();

	/** An estimate says this block can't be done on a breath of {@code breath}. */
	public void judged(BlockPos pos, int breath) {
		judged.merge(pos, breath, Math::max);
	}

	/** Found out at {@code from}, for this block and what's around it: a search went through everything, or a whole breath wasn't enough. */
	public void proven(BlockPos pos, int breath, BlockPos from) {
		entries.add(new Entry(pos, breath, from, PATCH));
	}

	/** Went wrong at {@code from}, for this block alone: the way was blocked, it couldn't be mined. */
	public void failed(BlockPos pos, int breath, BlockPos from) {
		entries.add(new Entry(pos, breath, from, 0));
	}

	/** Does anything found still hold for this block, with this full breath, standing here? */
	public boolean covers(BlockPos pos, int breath, BlockPos here) {
		Integer estimate = judged.get(pos);
		if (estimate != null && breath <= estimate) {
			return true;
		}
		for (Entry e : entries) {
			if (breath <= e.breath && holds(e, pos, here)) {
				return true;
			}
		}
		return false;
	}

	private static boolean holds(Entry e, BlockPos pos, BlockPos here) {
		return Math.abs(pos.getX() - e.pos.getX()) <= e.patch && Math.abs(pos.getY() - e.pos.getY()) <= e.patch
				&& Math.abs(pos.getZ() - e.pos.getZ()) <= e.patch
				&& Math.abs(here.getX() - e.from.getX()) <= SEARCHED && Math.abs(here.getZ() - e.from.getZ()) <= SEARCHED;
	}

	public int size() {
		return entries.size() + judged.size();
	}

}
