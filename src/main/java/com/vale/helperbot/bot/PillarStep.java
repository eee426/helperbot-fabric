package com.vale.helperbot.bot;

import com.vale.helperbot.path.PathStep;
import net.minecraft.server.level.ServerLevel;

/**
 * Jump and put a block under ourselves, like a player towering up: a full vanilla jump (peak
 * about 1.25 blocks), the block placed once the feet are clearly above the cell, then land on
 * it. Placing at exactly one block up could leave the hitbox overlapping the cell by a rounding
 * error, which vanilla rejects; that used to loop forever (jump, fail, fall, re-plan, jump...).
 */
final class PillarStep implements StepExecutor {
	static final PillarStep INSTANCE = new PillarStep();

	private PillarStep() {
	}

	/** On top of the block it's about to place, which isn't there yet. */
	@Override
	public double targetFloor(ServerLevel level, PathStep step) {
		return step.from().getY() + 1;
	}

	@Override
	public void tick(MovementController m, PathStep step, double ty) {
		BotPlayer bot = m.bot;
		if (!m.placed) {
			if (m.verticalVelocity <= 0 && m.isSupported()) {
				if (++m.pillarJumps > MovementController.MAX_PILLAR_JUMPS) {
					m.replan("垫柱子连跳 3 次都没放上");
					return;
				}
				m.verticalVelocity = MovementController.JUMP_VELOCITY;
				bot.causeFoodExhaustion(0.05f);
			}
			if (m.verticalVelocity > 0) {
				if (!m.tryMove(0, m.verticalVelocity, 0)) {
					// Bumped our head on something the plan thought was open: remember it.
					m.learnObstacle(bot.getBoundingBox().move(0, m.verticalVelocity, 0));
					m.replan("垫柱子时头顶被挡住");
					return;
				} else {
					m.verticalVelocity = (m.verticalVelocity - MovementController.GRAVITY) * MovementController.DRAG;
				}
			} else {
				m.fall();
			}
			if (bot.getY() >= step.place().getY() + 1 + MovementController.PILLAR_CLEARANCE) {
				com.vale.helperbot.guard.Placement.Refusal refused = m.hands.placeBlock(step.place());
				if (refused != null) {
					m.replan("垫柱子放方块失败：" + refused.why);
					return;
				}
				m.placed = true;
			}
			return;
		}
		// Block is in: finish the jump arc and land on it.
		if (m.verticalVelocity > 0) {
			m.tryMove(0, m.verticalVelocity, 0);
			m.verticalVelocity = (m.verticalVelocity - MovementController.GRAVITY) * MovementController.DRAG;
		} else if (bot.getY() > ty + MovementController.EPS) {
			m.verticalVelocity = (m.verticalVelocity - MovementController.GRAVITY) * MovementController.DRAG;
			if (!m.moveDown(Math.max(m.verticalVelocity, ty - bot.getY()))) {
				m.verticalVelocity = 0;
			}
		}
		if (Math.abs(bot.getY() - ty) < 1e-3 && m.isGrounded()) {
			m.pillarJumps = 0;
			m.nextStep();
		}
	}
}
