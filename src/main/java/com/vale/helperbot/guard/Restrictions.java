package com.vale.helperbot.guard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vale.helperbot.HelperBot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Temporary limits a server can switch on, kept apart from how the bot thinks: blocks it must
 * neither mine nor place (e.g. obsidian, so it never takes a nether portal apart or builds one),
 * and no portals (it's never taken to the Nether or the End, even if it's pushed or falls in).
 * All off by default. The rest of the code only asks {@link Guard} (and the bot whether it may use
 * a portal), so taking these out later means taking out this class and its few calls.
 */
public final class Restrictions {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Set<Block> blocked = new LinkedHashSet<>();
	/** What path searches read, possibly on a worker thread: a copy, replaced after each change. */
	private static volatile Set<Block> published = Set.of();
	private static boolean noPortals;
	private static Path file;

	/** What's saved: block ids, and the portal switch. */
	private static final class Saved {
		List<String> blockedBlocks = new ArrayList<>();
		boolean noPortals;
	}

	private Restrictions() {
	}

	public static void load(MinecraftServer server) {
		blocked.clear();
		published = Set.of();
		noPortals = false;
		file = server.getWorldPath(LevelResource.ROOT).resolve("helperbot").resolve("restrictions.json");
		if (!Files.exists(file)) {
			return;
		}
		try {
			Saved saved = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
			if (saved != null) {
				for (String id : saved.blockedBlocks) {
					BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).ifPresent(blocked::add);
				}
				noPortals = saved.noPortals;
			}
		} catch (IOException | RuntimeException e) {
			HelperBot.LOGGER.error("Failed to read restrictions from {}", file, e);
		}
		published = Set.copyOf(blocked);
	}

	private static void save() {
		if (file == null) {
			return;
		}
		Saved saved = new Saved();
		for (Block b : blocked) {
			saved.blockedBlocks.add(BuiltInRegistries.BLOCK.getKey(b).toString());
		}
		saved.noPortals = noPortals;
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(saved), StandardCharsets.UTF_8);
		} catch (IOException e) {
			HelperBot.LOGGER.error("Failed to save restrictions to {}", file, e);
		}
	}

	/** Neither mined nor placed. */
	public static boolean isBlocked(Block block) {
		Set<Block> set = published;
		return !set.isEmpty() && set.contains(block);
	}

	/** An item that would place a blocked block. */
	public static boolean isBlocked(Item item) {
		return item instanceof BlockItem b && isBlocked(b.getBlock());
	}

	/** Blocks it, or unblocks it if it was. Returns whether it's blocked now. */
	public static boolean toggle(Block block) {
		boolean now = !blocked.remove(block);
		if (now) {
			blocked.add(block);
		}
		published = Set.copyOf(blocked);
		save();
		return now;
	}

	public static boolean noPortals() {
		return noPortals;
	}

	public static void setNoPortals(boolean value) {
		noPortals = value;
		save();
	}

	/** For messages: why something can't be had, or null. */
	public static String refusal(Item item) {
		return isBlocked(item) ? "临时限制：不挖也不放" + item.getName().getString() + "（/bot restrict 可以查看和关掉）。" : null;
	}

	public static String describe() {
		List<String> names = new ArrayList<>();
		for (Block b : blocked) {
			names.add(b.getName().getString());
		}
		return "临时限制：不挖不放的方块 " + (names.isEmpty() ? "无" : String.join("、", names))
				+ "；传送门 " + (noPortals ? "不让用（不会去下界、末地）" : "不限制");
	}
}
