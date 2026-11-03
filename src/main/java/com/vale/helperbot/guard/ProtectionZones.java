package com.vale.helperbot.guard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.vale.helperbot.HelperBot;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Protected chunks (bases, farms, machines): whole chunks, bedrock to sky. The bot may pass
 * through but never breaks, places, picks up, fights or uses anything there, and while inside
 * it's a ghost (see BotPlayer#isGhost): nothing there notices it. Chunks rather than boxes:
 * nobody wants to count blocks, and a whole chunk leaves some room around what's protected.
 *
 * <p>Anyone may protect a chunk; each gets a number, and only the server console (permission 4)
 * takes protection off again, by that number. So nobody can unprotect someone else's base.
 */
public final class ProtectionZones {
	/** One protected chunk. */
	public record Entry(int id, String dimension, int x, int z) {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Per dimension id: chunk position (ChunkPos#toLong) to its number. */
	private static final Map<String, Long2IntOpenHashMap> chunks = new HashMap<>();
	/**
	 * What lookups read: a copy of {@link #chunks}, replaced (never changed) after each change, so
	 * path searches on a worker thread can read it while a command changes the zones.
	 */
	private static volatile Map<String, Long2IntOpenHashMap> published = Map.of();
	private static int nextId = 1;
	private static Path file;

	private ProtectionZones() {
	}

	/** What's saved. */
	private static final class Saved {
		int nextId = 1;
		List<Entry> chunks = new ArrayList<>();
	}

	public static void load(MinecraftServer server) {
		chunks.clear();
		published = Map.of();
		nextId = 1;
		file = server.getWorldPath(LevelResource.ROOT).resolve("helperbot").resolve("protected_chunks.json");
		if (!Files.exists(file)) {
			return;
		}
		try {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			JsonObject json = JsonParser.parseString(text).getAsJsonObject();
			if (json.has("chunks")) {
				Saved saved = GSON.fromJson(json, Saved.class);
				for (Entry e : saved.chunks) {
					map(e.dimension()).put(ChunkPos.asLong(e.x(), e.z()), e.id());
					nextId = Math.max(nextId, e.id() + 1);
				}
				nextId = Math.max(nextId, saved.nextId);
			} else {
				// The first version: dimension -> [[x, z], ...], no numbers yet.
				Map<String, List<int[]>> old = GSON.fromJson(text, new TypeToken<Map<String, List<int[]>>>() {}.getType());
				old.forEach((dim, list) -> list.forEach(c -> map(dim).put(ChunkPos.asLong(c[0], c[1]), nextId++)));
				save();
			}
		} catch (IOException | RuntimeException e) {
			HelperBot.LOGGER.error("Failed to read protected chunks from {}", file, e);
		}
		publish();
	}

	private static void publish() {
		Map<String, Long2IntOpenHashMap> copy = new HashMap<>();
		chunks.forEach((dim, m) -> {
			Long2IntOpenHashMap c = new Long2IntOpenHashMap(m);
			c.defaultReturnValue(0);
			copy.put(dim, c);
		});
		published = copy;
	}

	private static Long2IntOpenHashMap map(String dim) {
		return chunks.computeIfAbsent(dim, d -> {
			Long2IntOpenHashMap m = new Long2IntOpenHashMap();
			m.defaultReturnValue(0);
			return m;
		});
	}

	private static void save() {
		if (file == null) {
			return;
		}
		Saved saved = new Saved();
		saved.nextId = nextId;
		saved.chunks = entries(null);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(saved), StandardCharsets.UTF_8);
		} catch (IOException e) {
			HelperBot.LOGGER.error("Failed to save protected chunks to {}", file, e);
		}
	}

	/** Protects the chunk (if it isn't already). Returns its number. */
	public static int protect(Level level, ChunkPos chunk) {
		Long2IntOpenHashMap m = map(dimensionId(level));
		int id = m.get(chunk.toLong());
		if (id != 0) {
			return id;
		}
		id = nextId++;
		m.put(chunk.toLong(), id);
		publish();
		save();
		return id;
	}

	/** Takes protection off the chunk with this number. False if there's none. */
	public static boolean remove(int id) {
		for (Long2IntOpenHashMap m : chunks.values()) {
			for (Long2IntMap.Entry e : m.long2IntEntrySet()) {
				if (e.getIntValue() == id) {
					m.remove(e.getLongKey());
					publish();
					save();
					return true;
				}
			}
		}
		return false;
	}

	/** The chunk's number, or 0 if it isn't protected. */
	public static int idOf(Level level, ChunkPos chunk) {
		Long2IntOpenHashMap m = published.get(dimensionId(level));
		return m == null ? 0 : m.get(chunk.toLong());
	}

	/** Protected chunks in this level's dimension (or all, with a null level), by number. */
	public static List<Entry> entries(Level level) {
		List<Entry> out = new ArrayList<>();
		chunks.forEach((dim, m) -> {
			if (level == null || dim.equals(dimensionId(level))) {
				for (Long2IntMap.Entry e : m.long2IntEntrySet()) {
					out.add(new Entry(e.getIntValue(), dim, ChunkPos.getX(e.getLongKey()), ChunkPos.getZ(e.getLongKey())));
				}
			}
		});
		out.sort(Comparator.comparingInt(Entry::id));
		return out;
	}

	/** Protected chunks within {@code radius} chunks of {@code center}, in this level's dimension. */
	public static List<Entry> near(Level level, ChunkPos center, int radius) {
		List<Entry> out = new ArrayList<>();
		for (Entry e : entries(level)) {
			if (Math.abs(e.x() - center.x) <= radius && Math.abs(e.z() - center.z) <= radius) {
				out.add(e);
			}
		}
		return out;
	}

	/** Also takes a path search's copy of the world (see com.vale.helperbot.path.Snapshot). */
	public static boolean isProtected(BlockGetter level, BlockPos pos) {
		Map<String, Long2IntOpenHashMap> all = published;
		if (all.isEmpty()) {
			return false;
		}
		Long2IntOpenHashMap m = all.get(com.vale.helperbot.path.Worlds.dimension(level).identifier().toString());
		return m != null && m.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)) != 0;
	}

	public static boolean isProtected(Level level, ChunkPos chunk) {
		return idOf(level, chunk) != 0;
	}

	private static String dimensionId(Level level) {
		return level.dimension().identifier().toString();
	}
}
