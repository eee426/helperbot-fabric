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
		return followTick(bot, player, mode, this);
	}

	/**
	 * Shared "walk to player" logic; returns DONE on arrival. Follows the player as they are now,
	 * going by who they are: dying (or logging back in) makes a new player object, and the one the
	 * task was given stays behind where they died, so the bot walked back to that spot. Elsewhere
	 * (offline, another dimension) it stops: coordinates from another dimension mean nothing here.
	 */
	static Status followTick(BotPlayer bot, ServerPlayer asked, Mode mode, ComeTask state) {
		ServerPlayer player = bot.current(asked);
		if (player == null || player.level() != bot.level()) {
			bot.say(asked, "你不在这个维度或已下线，我先停下。");
			return Status.FAILED;
		}
		BlockPos playerPos = player.blockPosition();
		MovementController movement = bot.movement();
		if (bot.distanceTo(player) <= ARRIVE_DISTANCE && movement.status() != MovementController.Status.MOVING) {
			return Status.DONE;
		}
		if (state.lastTarget == null || movement.status() == MovementController.Status.IDLE) {
			movement.goTo((w, p) -> p.closerThan(playerPos, ARRIVE_DISTANCE), playerPos, mode);
			state.lastTarget = playerPos;
		} else if (state.lastTarget.distSqr(playerPos) > 16) {
			movement.retarget((w, p) -> p.closerThan(playerPos, ARRIVE_DISTANCE), playerPos);
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
