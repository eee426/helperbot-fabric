package com.vale.helperbot.guard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.vale.helperbot.HelperBot;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Protected chunks (bases, farms, machines): whole chunks, bedrock to sky, marked by a player
 * standing in them. The bot may pass through but never breaks, places, picks up, fights or uses
 * anything there, and while inside it's a ghost (see BotPlayer#updateGhost): nothing there
 * notices it. Chunks rather than boxes: nobody wants to count blocks, and a whole chunk leaves
 * some room around what's being protected.
 */
public final class ProtectionZones {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Per dimension id: chunk positions (ChunkPos#toLong). */
	private static final Map<String, LongSet> chunks = new HashMap<>();
	private static Path file;

	private ProtectionZones() {
	}

	public static void load(MinecraftServer server) {
		chunks.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("helperbot").resolve("protected_chunks.json");
		if (!Files.exists(file)) {
			return;
		}
		try {
			Map<String, List<int[]>> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
					new TypeToken<Map<String, List<int[]>>>() {}.getType());
			if (loaded != null) {
				loaded.forEach((dim, list) -> {
					LongSet set = new LongOpenHashSet();
					for (int[] c : list) {
						set.add(ChunkPos.asLong(c[0], c[1]));
					}
					chunks.put(dim, set);
				});
			}
		} catch (IOException | RuntimeException e) {
			HelperBot.LOGGER.error("Failed to read protected chunks from {}", file, e);
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}
		Map<String, List<int[]>> out = new HashMap<>();
		chunks.forEach((dim, set) -> {
			List<int[]> list = new ArrayList<>();
			for (long c : set) {
				list.add(new int[]{ChunkPos.getX(c), ChunkPos.getZ(c)});
			}
			out.put(dim, list);
		});
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(out), StandardCharsets.UTF_8);
		} catch (IOException e) {
			HelperBot.LOGGER.error("Failed to save protected chunks to {}", file, e);
		}
	}

	/** Protects the chunk if it wasn't, unprotects it if it was. Returns whether it's protected now. */
	public static boolean toggle(Level level, ChunkPos chunk) {
		LongSet set = chunks.computeIfAbsent(dimensionId(level), d -> new LongOpenHashSet());
		boolean now = !set.remove(chunk.toLong());
		if (now) {
			set.add(chunk.toLong());
		}
		save();
		return now;
	}

	/** Protected chunks in this level's dimension. */
	public static List<ChunkPos> all(Level level) {
		List<ChunkPos> out = new ArrayList<>();
		LongSet set = chunks.get(dimensionId(level));
		if (set != null) {
			for (long c : set) {
				out.add(new ChunkPos(c));
			}
		}
		return out;
	}

	public static boolean isProtected(Level level, BlockPos pos) {
		if (chunks.isEmpty()) {
			return false;
		}
		LongSet set = chunks.get(dimensionId(level));
		return set != null && set.contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
	}

	public static boolean isProtected(Level level, ChunkPos chunk) {
		LongSet set = chunks.get(dimensionId(level));
		return set != null && set.contains(chunk.toLong());
	}

	private static String dimensionId(Level level) {
		return level.dimension().identifier().toString();
	}
}
