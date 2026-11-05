package com.vale.helperbot.task;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * What one order found it can't get to, so it doesn't go back and find out again. It keeps no
 * clock: each entry says what the finding rested on, and holds until that changes, whether that
 * takes a second or an hour. Being wrong costs little either way (a block skipped, or one more
 * short dive that finds the same), so it needn't be right for ever, and it ends with the order.
 */
public final class OutOfReach {
	/** A search that went through everything also rules out what's around the block. */
	private static final int PATCH = 4;
	/** Past this from where it found out, it may come at it some way it never saw; and that's past what it scans for targets. */
	private static final int SEARCHED = 100;

	/** {@code from} null: judged by estimate, for that block alone, wherever it stands. */
	private record Entry(BlockPos pos, int breath, BlockPos from) {
	}

	private final List<Entry> entries = new ArrayList<>();
	private boolean forgotten;

	/** An estimate says this block can't be done on a breath of {@code breath}. */
	public void judged(BlockPos pos, int breath) {
		entries.add(new Entry(pos, breath, null));
	}

	/** A search from {@code from} went through all it could reach, breaths included, and this wasn't there. */
	public void proven(BlockPos pos, int breath, BlockPos from) {
		entries.add(new Entry(pos, breath, from));
	}

	/** Does anything found still hold for this block, with this full breath, standing here? */
	public boolean covers(BlockPos pos, int breath, BlockPos here) {
		for (Entry e : entries) {
			if (breath <= e.breath && (e.from == null ? pos.equals(e.pos) : holds(e, pos, here))) {
				return true;
			}
		}
		return false;
	}

	private static boolean holds(Entry e, BlockPos pos, BlockPos here) {
		return Math.abs(pos.getX() - e.pos.getX()) <= PATCH && Math.abs(pos.getY() - e.pos.getY()) <= PATCH
				&& Math.abs(pos.getZ() - e.pos.getZ()) <= PATCH
				&& Math.abs(here.getX() - e.from.getX()) <= SEARCHED && Math.abs(here.getZ() - e.from.getZ()) <= SEARCHED;
	}

	public int size() {
		return entries.size();
	}

	/**
	 * Nothing else is left: drop it all and look again before giving up, in case something has
	 * changed that it can't see from here (someone dug a way in). Once an order. True if it did.
	 */
	public boolean forgetOnce() {
		if (forgotten || entries.isEmpty()) {
			return false;
		}
		forgotten = true;
		entries.clear();
		return true;
	}
}
