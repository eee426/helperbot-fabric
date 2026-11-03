package com.vale.helperbot.path;

/**
 * A possible step. {@code cost} is what the search minimises: real time, plus preferences
 * (stay near the surface, dig less in safe mode, avoid risky breaks). {@code ticks} is just the
 * real time it takes, which is what air is charged with: preferences mustn't use up air that
 * isn't really used (it made the bot think it couldn't get back up when it could).
 */
public record Candidate(PathStep step, double floor, double cost, double ticks) {
	public Candidate(PathStep step, double floor, double cost) {
		this(step, floor, cost, cost);
	}
}
