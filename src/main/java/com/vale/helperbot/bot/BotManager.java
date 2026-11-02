package com.vale.helperbot.bot;

import com.mojang.authlib.GameProfile;
import com.vale.helperbot.HelperBot;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

import java.util.Set;

/** Owns the single global bot (multiple bots are a later phase). */
public final class BotManager {
	public static final String BOT_NAME = "HelperBot";

	private static BotPlayer bot;

	private BotManager() {
	}

	public static BotPlayer get() {
		return bot != null && !bot.isRemoved() ? bot : null;
	}

	public static BotPlayer spawn(MinecraftServer server, ServerPlayer near) {
		BotPlayer existing = get();
		if (existing != null) {
			return existing;
		}
		if (server.getPlayerList().getPlayerByName(BOT_NAME) != null) {
			return null;
		}
		GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(BOT_NAME), BOT_NAME);
		BotPlayer created = new BotPlayer(server, near.level(), profile);
		// A real client's saved data (inventory, health, food...) is loaded during login, which the
		// bot skips, so load it here. Without this the bot spawns empty and then saves that over
		// its old data when it leaves.
		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(created.problemPath(), HelperBot.LOGGER)) {
			server.getPlayerList().loadPlayerData(new NameAndId(profile))
					.map(tag -> TagValueInput.create(reporter, server.registryAccess(), tag))
					.ifPresent(created::load);
		}
		if (created.getHealth() <= 0) {
			created.resetAfterDeath(); // saved while dead: come back like a respawned player
		}
		server.getPlayerList().placeNewPlayer(new FakeConnection(), created,
				new CommonListenerCookie(profile, 0, created.clientInformation(), false));
		created.markLoaded();
		created.teleportTo(near.level(), near.getX(), near.getY(), near.getZ(), Set.of(), near.getYRot(), 0, true);
		created.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		bot = created;
		return created;
	}

	public static void remove(MinecraftServer server, String reason) {
		BotPlayer current = get();
		if (current == null) {
			return;
		}
		current.cancelTask();
		bot = null;
		current.connection.onDisconnect(new DisconnectionDetails(Component.literal(reason)));
		server.getPlayerList().broadcastSystemMessage(Component.literal("[" + BOT_NAME + "] " + reason), false);
	}
}
