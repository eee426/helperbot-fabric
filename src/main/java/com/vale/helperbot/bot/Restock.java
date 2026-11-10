package com.vale.helperbot.bot;

/**
 * Topping up building blocks, for the restock reflex (see {@link BotPlayer#restock}). The fetching
 * is the task layer's (the gathering task, see task/RestockStep), registered at start (see
 * HelperBot), since the body can't reach up to it.
 */
public interface Restock {
	/** One tick of it: RUNNING while at it; DONE once it got some, FAILED if it got none. */
	BotTask.Status tick(BotPlayer bot);

	/** It's over, done or broken off: let go of what it holds (a search in the background, say). */
	void end(BotPlayer bot);

	/** How a restock is found. */
	interface Finder {
		/** Up to {@code wanted} building blocks in the bag, from ground in reach around here; null if there's none to dig. */
		Restock find(BotPlayer bot, int wanted);
	}
}
