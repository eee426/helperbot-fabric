package com.vale.helperbot.task;

import com.vale.helperbot.bot.BotPlayer;
import net.minecraft.world.item.ItemStack;

public interface BotTask {
	enum Status {
		RUNNING, DONE, FAILED, CANCELLED
	}

	/** Called every tick while the task is active. */
	Status tick(BotPlayer bot);

	/** Called once when the task leaves RUNNING for any reason. */
	void onEnd(BotPlayer bot, Status status);

	String describe();

	default boolean wantsPickup(ItemStack stack) {
		return false;
	}
}
