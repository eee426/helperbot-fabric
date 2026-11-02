package com.vale.helperbot.guard;

import com.vale.helperbot.bot.BotManager;
import com.vale.helperbot.bot.BotPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Anyone in the player list under the bot's name who isn't the bot (a player who got past the
 * login check, a Carpet fake player, which doesn't log in) takes damage equal to their maximum
 * health, once a second: they die and leave the normal way, without being kicked (kicking can
 * upset other mods). The damage is the /kill kind: no armour, enchantment, effect or totem stops
 * it, and armour isn't worn by it, but it's a finite amount, not infinite. Only players: a mob
 * with that name tag is left alone.
 */
public final class NameGuard {
	private NameGuard() {
	}

	/** Every server tick. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player instanceof BotPlayer || !player.isAlive()
					|| !BotManager.BOT_NAME.equalsIgnoreCase(player.getGameProfile().name())) {
				continue;
			}
			player.sendSystemMessage(Component.literal("\"" + BotManager.BOT_NAME + "\" 是机器人专用的名字，请换一个名字。")
					.withStyle(ChatFormatting.RED));
			player.hurtServer(player.level(), player.damageSources().genericKill(), player.getMaxHealth());
		}
	}
}
