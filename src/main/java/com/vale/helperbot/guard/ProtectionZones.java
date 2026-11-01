package com.vale.helperbot.guard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.vale.helperbot.HelperBot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Box-shaped zones where the bot may walk through but never break, place or interact. */
public final class ProtectionZones {
	public record Zone(String name, String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		public boolean contains(String dim, BlockPos pos) {
			return dimension.equals(dim)
					&& pos.getX() >= minX && pos.getX() <= maxX
					&& pos.getY() >= minY && pos.getY() <= maxY
					&& pos.getZ() >= minZ && pos.getZ() <= maxZ;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final List<Zone> zones = new ArrayList<>();
	private static Path file;

	private ProtectionZones() {
	}

	public static void load(MinecraftServer server) {
		zones.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("helperbot").resolve("zones.json");
		if (!Files.exists(file)) {
			return;
		}
		try {
			List<Zone> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Zone>>() {}.getType());
			if (loaded != null) {
				zones.addAll(loaded);
			}
		} catch (IOException | RuntimeException e) {
			HelperBot.LOGGER.error("Failed to read protection zones from {}", file, e);
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(zones), StandardCharsets.UTF_8);
		} catch (IOException e) {
			HelperBot.LOGGER.error("Failed to save protection zones to {}", file, e);
		}
	}

	public static boolean add(String name, Level level, BlockPos a, BlockPos b) {
		if (find(name) != null) {
			return false;
		}
		zones.add(new Zone(name, dimensionId(level),
				Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
				Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
		save();
		return true;
	}

	public static boolean remove(String name) {
		boolean removed = zones.removeIf(z -> z.name().equals(name));
		if (removed) {
			save();
		}
		return removed;
	}

	public static Zone find(String name) {
		return zones.stream().filter(z -> z.name().equals(name)).findFirst().orElse(null);
	}

	public static List<Zone> all() {
		return List.copyOf(zones);
	}

	public static boolean isProtected(Level level, BlockPos pos) {
		if (zones.isEmpty()) {
			return false;
		}
		String dim = dimensionId(level);
		for (Zone zone : zones) {
			if (zone.contains(dim, pos)) {
				return true;
			}
		}
		return false;
	}

	private static String dimensionId(Level level) {
		return level.dimension().identifier().toString();
	}
}
