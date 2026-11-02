package com.vale.helperbot.guard;

import com.vale.helperbot.bot.BotManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * While the bot is out and nobody has protected a single chunk yet, say so to everyone, in red:
 * right when it appears, then every five minutes. Under open rules it digs anything outside
 * protected chunks, so bases need protecting first.
 */
public final class ZoneReminder {
	private static final int EVERY_TICKS = 20 * 60 * 5;
	/** Tick of the last warning; -1 when the bot isn't out (so the next one warns at once). */
	private static int lastWarned = -1;

	private ZoneReminder() {
	}

	/** Every server tick. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) {
			return;
		}
		if (BotManager.get() == null || !ProtectionZones.entries(null).isEmpty()) {
			lastWarned = -1;
			return;
		}
		if (lastWarned >= 0 && server.getTickCount() - lastWarned < EVERY_TICKS) {
			return;
		}
		lastWarned = server.getTickCount();
		server.getPlayerList().broadcastSystemMessage(Component.literal(
				"[HelperBot] 警告：现在一个保护区块都没有！机器人干活时会挖开路上的方块（包括建筑），可能造成破坏。"
						+ "请先站在自己的基地里输入 /bot protect，或用遥控模组（按 H）的\"保护区块\"地图把基地保护起来。"
						+ "（只要机器人在、保护区块还是 0 个，每 5 分钟提醒一次）")
				.withStyle(ChatFormatting.RED), false);
	}
}
