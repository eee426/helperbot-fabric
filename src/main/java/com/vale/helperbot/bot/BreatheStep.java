package com.vale.helperbot.bot;

import com.vale.helperbot.path.PathStep;

/**
 * Take a breath where the plan says (PathStep.Type.WAIT): stay put, standing or floating at the
 * surface, until the air is full, then on with the route. The plan put it here and costed the
 * time it takes (see LocalPathfinder#breathe).
 */
final class BreatheStep implements StepExecutor {
	static final BreatheStep INSTANCE = new BreatheStep();

	private BreatheStep() {
	}

	@Override
	public void tick(MovementController m, PathStep step, double ty) {
		BotPlayer bot = m.bot;
		if (m.headUnderwater()) {
			// The plan thought the head would be out here: it isn't (water came in, say).
			m.replan("计划在这里换气，但头在水里");
			return;
		}
		bot.setSprinting(false);
		m.fall(); // stand, or float at the surface
		if (bot.getAirSupply() >= bot.getMaxAirSupply()) {
			m.nextStep();
		}
	}
}
