package com.vale.helperbot.task;

import com.vale.helperbot.guard.ProtectionZones;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Where to look next when what it's after isn't around, for whatever is looking: a map of what
 * the world will have within {@link #RADIUS} of where it started, by the biomes there, and of
 * those places the one to go to from here (see {@link #next}).
 *
 * <p>The biomes come from the seed alone, as the game does before it makes a chunk: nothing is
 * loaded or generated to find them, so it's worked out once, off the server thread. Only what a
 * biome may have is known; what's there it finds by going, like a player. One a trip for each
 * thing wanted: time spent and places seen last across steps of the same order.
 */
final class Explorer {
	/** How far from where it started it may go looking. */
	static final int RADIUS = 2000;
	/** When the biome it wants is nowhere inside {@link #RADIUS}: the one trip to the nearest, up to this far. */
	static final int FAR = 10_000;
	private static final int GRID = 48;
	private static final int MAX_TICKS = 12_000;
	/** Heights looked at besides sea level, for what lives below: cave biomes are under the ones on the surface. */
	private static final int[] BELOW = {48, 16, -16, -48};

	private record Spot(int gx, int gz, double worth, Holder<Biome> biome) {
	}

	private final BlockPos origin;
	private final CompletableFuture<List<Spot>> map;
	/** Places within this of where it has been count as seen (it would have spotted what it's after). */
	private final int sight;
	/** Blocks a tick, as it really goes. */
	private final double speed;
	private final Set<Long> seen = new HashSet<>();
	private Spot heading;
	private int ticks;
	private long tookNanos;

	/**
	 * @param worth what's to be found per chunk of each biome (0: none); read off the server thread, so not changed after this
	 * @param below it lives underground, or anywhere: look at cave biomes too, not just the surface
	 * @param landOnly it's not after what's under water: oceans and rivers are out, and so is what lies below them
	 * @param sight how far around itself it finds what it's after
	 * @param speed blocks a tick, as it really goes
	 */
	Explorer(ServerLevel level, BlockPos origin, Map<Holder<Biome>, Double> worth, boolean below, boolean landOnly, int sight, double speed) {
		this.origin = origin;
		this.sight = sight;
		this.speed = speed;
		BiomeSource biomes = level.getChunkSource().getGenerator().getBiomeSource();
		Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
		int sea = level.getSeaLevel(), lowest = level.getMinY();
		this.map = CompletableFuture.supplyAsync(() -> {
			long started = System.nanoTime();
			List<Spot> spots = new ArrayList<>();
			int cells = RADIUS / GRID;
			for (int gx = -cells; gx <= cells; gx++) {
				for (int gz = -cells; gz <= cells; gz++) {
					if (gx * gx + gz * gz > cells * cells) {
						continue;
					}
					int x = QuartPos.fromBlock(origin.getX() + gx * GRID), z = QuartPos.fromBlock(origin.getZ() + gz * GRID);
					Holder<Biome> best = biomes.getNoiseBiome(x, QuartPos.fromBlock(sea), z, sampler);
					if (landOnly && water(best)) {
						continue;
					}
					double top = worth.getOrDefault(best, 0.0);
					if (below) {
						for (int y : BELOW) {
							Holder<Biome> b = biomes.getNoiseBiome(x, QuartPos.fromBlock(Math.max(lowest, y)), z, sampler);
							double w = worth.getOrDefault(b, 0.0);
							if (w > top) {
								top = w;
								best = b;
							}
						}
					}
					if (top > 0) {
						spots.add(new Spot(gx, gz, top, best));
					}
				}
			}
			tookNanos = System.nanoTime() - started;
			return spots;
		});
	}

	static boolean water(Holder<Biome> biome) {
		return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER);
	}

	boolean ready() {
		return map.isDone();
	}

	/** For the log: milliseconds it took to work out the map. */
	long tookMillis() {
		return tookNanos / 1_000_000;
	}

	/** The map says it's nowhere in range at all (ask only once {@link #ready}). */
	boolean nowhereInRange() {
		return map.getNow(List.of()).isEmpty();
	}

	void tick() {
		ticks++;
	}

	int seconds() {
		return ticks / 20;
	}

	boolean timeUp() {
		return ticks >= MAX_TICKS;
	}

	/**
	 * The next place to go, among those not seen yet and not in a protected chunk (nothing can be
	 * got there); null if there's none left. Two questions, in real ticks, nothing to tune:
	 * <ol>
	 * <li>What kind of place: the one that gets {@code left} more done soonest, the walk there plus
	 * the walking to take in as many places as that takes. So for one or two a poor place nearby is
	 * right, and for a stack the long walk to a rich one pays, being made only once.</li>
	 * <li>Which of those, all worth the same: the one that keeps the walk there and the walk to
	 * {@code back} short. Only here, between equals, and so it can't make a worse place win: the
	 * player may have gone anywhere by the time it's done. It's what keeps it from leaving in a line
	 * (the next place always a bit further along) with all that's near unseen.</li>
	 * </ol>
	 *
	 * @param back where it will go when it's done, as far as it knows now (null: where it started)
	 */
	BlockPos next(ServerLevel level, BlockPos here, BlockPos back, int left) {
		List<Spot> open = open(level, here);
		double soonest = Double.MAX_VALUE, worth = 0;
		for (Spot s : open) {
			double ticks = ticks(s, here, left);
			if (ticks < soonest) {
				soonest = ticks;
				worth = s.worth;
			}
		}
		BlockPos home = back == null ? origin : back;
		heading = null;
		double shortest = Double.MAX_VALUE;
		for (Spot s : open) {
			if (s.worth == worth) {
				BlockPos at = place(s, here);
				double walk = Math.sqrt(at.distSqr(here)) + Math.sqrt(flatDistSq(at, home));
				if (walk < shortest) {
					shortest = walk;
					heading = s;
				}
			}
		}
		return heading == null ? null : place(heading, here);
	}

	/**
	 * Staying is a place to go too, with no walk to it: for monsters, which spawn round wherever it
	 * is, worth {@code hereWorth} (the same measure as the places, see the task's worth). True if
	 * staying gets {@code left} done no later than going to the best place not seen yet (see the
	 * first question of {@link #next}). It used to stay wherever they could spawn at all, the
	 * comparison never made: in the Nether wastes, endermen a hundredth of what spawns, it waited
	 * there twice till it gave up, a warped forest, nearly all endermen, nearby.
	 */
	boolean staying(ServerLevel level, BlockPos here, double hereWorth, int left) {
		if (hereWorth <= 0) {
			return false;
		}
		double stay = left / (hereWorth * chunksSeen()) * perPlace();
		for (Spot s : open(level, here)) {
			if (ticks(s, here, left) < stay) {
				return false;
			}
		}
		return true;
	}

	/** Places not seen yet, and not in a protected chunk (nothing can be got there). */
	private List<Spot> open(ServerLevel level, BlockPos here) {
		List<Spot> open = new ArrayList<>();
		for (Spot s : map.getNow(List.of())) {
			if (!seen.contains(key(s.gx, s.gz)) && !ProtectionZones.isProtected(level, place(s, here))) {
				open.add(s);
			}
		}
		return open;
	}

	/** The walk there, and the walking to take in as many places as {@code left} more takes (see {@link #next}). */
	private double ticks(Spot s, BlockPos here, int left) {
		return Math.sqrt(place(s, here).distSqr(here)) / speed + left / (s.worth * chunksSeen()) * perPlace();
	}

	/** Ticks to walk on to the next place: across what it takes in from one. */
	private double perPlace() {
		return 2.0 * sight / speed;
	}

	/** Chunks it takes in from one place. */
	private double chunksSeen() {
		return (2.0 * sight / 16) * (2.0 * sight / 16);
	}

	private static double flatDistSq(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	private BlockPos place(Spot s, BlockPos here) {
		return new BlockPos(origin.getX() + s.gx * GRID, here.getY(), origin.getZ() + s.gz * GRID);
	}

	/** The biome of the place {@link #next} gave. */
	Holder<Biome> headingBiome() {
		return heading == null ? null : heading.biome;
	}

	/** Done with the place it was heading for: been there, or can't get there. */
	void leavePlace() {
		if (heading != null) {
			seen.add(key(heading.gx, heading.gz));
		}
	}

	/** It's here now: what's around here is seen. */
	void look(BlockPos here) {
		int r = sight / GRID + 1;
		int cx = Math.round((float) (here.getX() - origin.getX()) / GRID);
		int cz = Math.round((float) (here.getZ() - origin.getZ()) / GRID);
		for (int gx = cx - r; gx <= cx + r; gx++) {
			for (int gz = cz - r; gz <= cz + r; gz++) {
				double dx = origin.getX() + gx * GRID - here.getX(), dz = origin.getZ() + gz * GRID - here.getZ();
				if (dx * dx + dz * dz <= sight * sight) {
					seen.add(key(gx, gz));
				}
			}
		}
	}

	private static long key(int gx, int gz) {
		return ((long) gx << 32) | (gz & 0xffffffffL);
	}
}
