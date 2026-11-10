package com.vale.helperbot.task;

import com.mojang.datafixers.util.Pair;
import com.vale.helperbot.HelperBot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * The nearest biome of a kind, up to {@link Explorer#FAR} away, when the explorer's map has none in
 * its range: the game's own search, which samples world-generation noise only (no chunk is loaded),
 * so it runs off the server thread. For everything that goes looking further off (gathering,
 * hunting, scooping), so that a search that goes wrong is told apart from one that finds nothing:
 * the hunt used to swallow the error and say there was no such biome.
 */
final class FarBiome {
	/** Found closer than this (horizontally): it's the biome it stands in, not a trip. */
	static final int HERE = 48;
	/** Already in it: how far round to look through what's loaded, chunks. */
	static final int WIDE_SCAN_CHUNKS = 10;
	/** On the trip there, blocked this many times it sets off again from where it got to; once more, it gives the trip up. */
	static final int TRIP_RETRIES = 3;

	enum Result {
		SEARCHING, FOUND, NONE, ERROR
	}

	private final CompletableFuture<Pair<BlockPos, Holder<Biome>>> search;
	private final BlockPos from;
	private Pair<BlockPos, Holder<Biome>> found;

	/** {@code step}: blocks between the points the game samples (finer finds smaller patches, and takes longer). */
	FarBiome(ServerLevel level, Predicate<Holder<Biome>> wanted, BlockPos from, int step) {
		this.from = from;
		this.search = CompletableFuture.supplyAsync(() -> level.findClosestBiome3d(wanted, from, Explorer.FAR, step, 64));
	}

	Result poll() {
		if (!search.isDone()) {
			return Result.SEARCHING;
		}
		try {
			found = search.join();
		} catch (RuntimeException e) {
			HelperBot.LOGGER.error("Biome search failed", e);
			return Result.ERROR;
		}
		return found == null ? Result.NONE : Result.FOUND;
	}

	BlockPos at() {
		return found.getFirst();
	}

	/** Horizontal blocks from where the search started. */
	int distance() {
		long dx = at().getX() - from.getX(), dz = at().getZ() - from.getZ();
		return (int) Math.sqrt(dx * dx + dz * dz);
	}

	/** Already standing in it (see {@link #HERE}). */
	boolean here() {
		return distance() < HERE;
	}

	String biomeName() {
		return found.getSecond().unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
	}

	void cancel() {
		search.cancel(false);
	}

	/** What to tell the player when the search itself went wrong. */
	static String errorText() {
		return "找群系时出错了（不是没有这种群系），服务器日志里有详细原因";
	}
}
