package com.vale.helperbot.path;

import com.vale.helperbot.HelperBot;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A path search running on the background thread, on a {@link Snapshot} of the world taken when
 * it was made. The server thread polls it once a tick and takes the result when it's there.
 * <p>
 * All bots share one background thread: the server has few cores, and the game's own chunk work
 * needs some. A search no longer wanted is cancelled; it stops at its next check.
 */
public final class PathJob {
	private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "HelperBot pathfinder");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});
	/** Slice of search between checks for cancellation. */
	private static final long SLICE_NANOS = 5_000_000;

	private final LocalPathfinder search;
	private final AtomicBoolean cancelled = new AtomicBoolean();
	private volatile boolean done;
	private volatile boolean failed;
	/** Time the search took on the background thread. */
	private volatile long nanos;

	/** Starts the search (made on the server thread, on a snapshot) in the background. */
	public PathJob(LocalPathfinder search) {
		this.search = search;
		POOL.execute(this::run);
	}

	private void run() {
		long started = System.nanoTime();
		try {
			while (!cancelled.get() && !search.step(SLICE_NANOS)) {
				// The node limit ends every search.
			}
		} catch (RuntimeException e) {
			HelperBot.LOGGER.error("Path search failed", e);
			failed = true;
		} finally {
			nanos = System.nanoTime() - started;
			done = true;
		}
	}

	/**
	 * Waits up to {@code maxNanos} for the search to finish: a short search is then taken in the
	 * same tick, as before it ran in the background.
	 */
	public boolean await(long maxNanos) {
		long until = System.nanoTime() + maxNanos;
		while (!done && System.nanoTime() < until) {
			Thread.onSpinWait();
		}
		return done;
	}

	/**
	 * Test runs only: the scene runner ticks the server as fast as it can, many times faster than
	 * the 20 ticks a second of a real server, while the background search runs at its own real
	 * speed. Without this, a search a real server has back within a tick or two would span dozens
	 * of test ticks, and the scenes would test a much slower bot than players get. Returns the time
	 * waited (not the bot's own time: see Perf#testWait).
	 */
	public long paceForTests() {
		if (TEST_TICK_NANOS == 0 || done) {
			return 0;
		}
		long started = System.nanoTime();
		await(TEST_TICK_NANOS);
		return System.nanoTime() - started;
	}

	/** Wall time of a real tick, less the server's own work in it; 0 outside test runs. */
	private static final long TEST_TICK_NANOS = System.getenv("HELPERBOT_SCENARIOS") != null ? 40_000_000 : 0;

	public boolean done() {
		return done;
	}

	public void cancel() {
		cancelled.set(true);
	}

	/** Background time of the search, the first time it's asked once done (then 0): for Perf. */
	public long takeNanos() {
		if (counted) {
			return 0;
		}
		counted = true;
		return nanos;
	}

	/** Read and written on the server thread only. */
	private boolean counted;

	/** The result once {@link #done()} (empty if the search failed). */
	public LocalPathfinder.Result result() {
		return failed ? LocalPathfinder.Result.EMPTY : search.result();
	}

	public double progressBlocks() {
		return failed ? 0 : search.progressBlocks();
	}

	public LocalPathfinder.Limits limits() {
		return search.limits();
	}
}
