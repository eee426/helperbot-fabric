package com.vale.helperbot.mixin;

import com.vale.helperbot.bot.BotPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/** The bot doesn't count towards the players needed to skip the night. */
@Mixin(SleepStatus.class)
public abstract class SleepStatusMixin {
	@ModifyVariable(method = "update", at = @At("HEAD"), argsOnly = true)
	private List<ServerPlayer> helperbot$skipBotInUpdate(List<ServerPlayer> players) {
		return withoutBots(players);
	}

	@ModifyVariable(method = "areEnoughDeepSleeping", at = @At("HEAD"), argsOnly = true)
	private List<ServerPlayer> helperbot$skipBotInDeepSleep(List<ServerPlayer> players) {
		return withoutBots(players);
	}

	private static List<ServerPlayer> withoutBots(List<ServerPlayer> players) {
		return players.stream().filter(p -> !(p instanceof BotPlayer)).toList();
	}
}
