package com.vale.helperbot.bot;

import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.Mode;
import com.vale.helperbot.path.PathStep;
import net.minecraft.core.BlockPos;

/**
 * Walk, step up, jump up, drop: one tick in three parts. Get up to the target floor (vanilla
 * step-up, half steps on stairs, or a jump arc), move across at walking/sprinting/swimming speed,
 * then come down onto the target floor under gravity.
 */
final class WalkStep implements StepExecutor {
	static final WalkStep INSTANCE = new WalkStep();

	private WalkStep() {
	}

	@Override
	public void tick(MovementController m, PathStep step, double ty) {
		BotPlayer bot = m.bot;
		double EPS = MovementController.EPS;
		BlockPos to = step.to();
		double tx = to.getX() + 0.5;
		double tz = to.getZ() + 0.5;
		double yAtStart = bot.getY();

		// 1. Up: vanilla step-up for small rises, a real jump arc for bigger ones.
		double rise = ty - bot.getY();
		BlockPos from = step.from();
		boolean stair = LocalPathfinder.stairStep(bot.level(), to, to.getX() - from.getX(), to.getZ() - from.getZ());
		if (rise > EPS) {
			boolean grounded = m.isSupported();
			if (rise <= LocalPathfinder.STEP_HEIGHT + EPS && grounded) {
				m.tryMove(0, rise, 0);
			} else if (stair && rise <= 1 + EPS && (grounded || m.verticalVelocity == 0)) {
				// Up a stair: half a block at a time, like a player walking up it (no jump: under
				// a low ceiling a jump bumps the head).
				m.tryMove(0, Math.min(rise, 0.5), 0);
			} else if (grounded || m.verticalVelocity > 0) {
				if (grounded) {
					m.verticalVelocity = MovementController.JUMP_VELOCITY;
					bot.causeFoodExhaustion(bot.isSprinting() ? 0.2f : 0.05f); // vanilla jump cost
				}
				m.tryMove(0, Math.min(m.verticalVelocity, rise), 0);
				m.verticalVelocity = (m.verticalVelocity - MovementController.GRAVITY) * MovementController.DRAG;
			} else {
				// Jump peaked below the ledge: fall back down and jump again.
				m.fall();
			}
		}

		// 2. Across, at vanilla walking/sprinting/swimming speed.
		double hx = tx - bot.getX(), hz = tz - bot.getZ();
		double horizontal = Math.sqrt(hx * hx + hz * hz);
		if (horizontal > EPS) {
			boolean swimming = m.isInWater();
			// Vanilla: no sprinting with 3 drumsticks (6 food) or less.
			// Vanilla: no sprinting while eating either, and eating slows you to a fifth.
			boolean eating = bot.isUsingItem();
			boolean sprint = !swimming && !eating && m.mode.sprint && bot.getFoodData().getFoodLevel() > 6;
			bot.setSprinting(sprint);
			double speed = swimming ? LocalPathfinder.SWIM_SPEED : sprint ? Mode.SPRINT_SPEED : Mode.WALK_SPEED;
			if (eating) {
				speed *= 0.2;
			}
			double s = Math.min(speed, horizontal);
			m.face(hx, hz);
			double mx = hx / horizontal * s, mz = hz / horizontal * s;
			// Like vanilla collision: if the full move is blocked, slide along whichever axis is free
			// (e.g. a block at head height brushing the side of the body when off-centre).
			if (m.tryMove(mx, 0, mz) || (Math.abs(mx) > EPS && m.tryMove(mx, 0, 0)) || (Math.abs(mz) > EPS && m.tryMove(0, 0, mz))) {
				// Vanilla hunger cost per block: 0.1 sprinting, 0.01 swimming, nothing for walking.
				bot.causeFoodExhaustion((float) (s * (swimming ? 0.01 : sprint ? 0.1 : 0.0)));
				m.blockedTicks = 0;
			} else if (m.offCentre(step.from()) && m.recentre(step.from())) {
				// Still stuck while off the middle of the cell: the body pokes into the next column.
				// Step back to the middle, where the plan assumed it would be.
				m.blockedTicks = 0;
			} else if (Math.abs(bot.getY() - yAtStart) < EPS && ++m.blockedTicks >= MovementController.BLOCKED_LEARN_TICKS) {
				// Can't go sideways and not going up either: something the plan thought was open
				// is in the way. Remember what, and route around it.
				m.learnObstacle(bot.getBoundingBox().move(mx, 0, mz));
				m.replan("前进方向被挡住");
				return;
			}
		} else {
			bot.setSprinting(false);
		}

		// 3. Down: gravity (or sinking to the surface level in water), landing exactly on the floor.
		if (bot.getY() > ty + EPS && rise <= EPS) {
			m.verticalVelocity = Math.min(m.verticalVelocity, 0);
			m.verticalVelocity = (m.verticalVelocity - MovementController.GRAVITY) * MovementController.DRAG;
			double dy = Math.max(m.verticalVelocity, ty - bot.getY());
			if (!m.moveDown(dy)) {
				m.verticalVelocity = 0;
			}
		}

		if (Math.abs(bot.getX() - tx) < 1e-3 && Math.abs(bot.getZ() - tz) < 1e-3 && Math.abs(bot.getY() - ty) < 1e-3) {
			m.nextStep();
		}
	}
}
