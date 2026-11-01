package com.vale.helperbot.task;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.bot.MovementController;
import com.vale.helperbot.path.Mode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Walk back to a player. Also the last leg of every gathering task. */
public class ComeTask implements BotTask {
	private static final double ARRIVE_DISTANCE = 3.0;

	private final ServerPlayer player;
	private final Mode mode;
	private BlockPos lastTarget;

	public ComeTask(ServerPlayer player, Mode mode) {
		this.player = player;
		this.mode = mode;
	}

	@Override
	public Status tick(BotPlayer bot) {
		if (player.hasDisconnected() || player.level() != bot.level()) {
			bot.say(player, "你不在这个维度或已下线，我先停下。");
			return Status.FAILED;
		}
		return followTick(bot, player, mode, this);
	}

	/** Shared "walk to player" logic; returns DONE on arrival. */
	static Status followTick(BotPlayer bot, ServerPlayer player, Mode mode, ComeTask state) {
		BlockPos playerPos = player.blockPosition();
		MovementController movement = bot.movement();
		if (bot.distanceTo(player) <= ARRIVE_DISTANCE && movement.status() != MovementController.Status.MOVING) {
			return Status.DONE;
		}
		if (state.lastTarget == null || movement.status() == MovementController.Status.IDLE) {
			movement.goTo(p -> p.closerThan(player.blockPosition(), ARRIVE_DISTANCE), playerPos, mode);
			state.lastTarget = playerPos;
		} else if (state.lastTarget.distSqr(playerPos) > 16) {
			movement.retarget(p -> p.closerThan(player.blockPosition(), ARRIVE_DISTANCE), playerPos);
			state.lastTarget = playerPos;
		}
		return switch (movement.status()) {
			case ARRIVED -> Status.DONE;
			case FAILED -> {
				bot.say(player, "回不去：" + movement.failReason());
				yield Status.FAILED;
			}
			default -> Status.RUNNING;
		};
	}

	@Override
	public void onEnd(BotPlayer bot, Status status) {
		if (status == Status.DONE) {
			bot.say(player, "我到了。");
		}
	}

	@Override
	public String describe() {
		return "回到 " + player.getGameProfile().name() + " 身边";
	}
}
