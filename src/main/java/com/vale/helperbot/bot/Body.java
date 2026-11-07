package com.vale.helperbot.bot;

import com.vale.helperbot.path.Air;
import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.Mode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The bot's body: where it is and how it may move, by vanilla's rules for a player. Collisions,
 * gravity and fall damage, floating and sinking, and swimming straight up for air. It knows nothing of
 * routes: the movement controller and the step executors say where to go, a tick at a time.
 */
final class Body {
	static final double GRAVITY = 0.08;
	static final double DRAG = 0.98;
	static final double JUMP_VELOCITY = 0.42;
	private static final double SWIM_UP_SPEED = 0.06;
	/** How fast it sinks when idle under water. */
	private static final double SINK_SPEED = 0.02;
	/** Degrees below the horizon the bot looks while walking. */
	private static final float WALK_PITCH = 20f;
	static final double EPS = 1e-4;

	private final BotPlayer bot;
	double verticalVelocity;
	private double airborneFromY = Double.NaN;

	Body(BotPlayer bot) {
		this.bot = bot;
	}

	private BlockPos feet() {
		return BlockPos.containing(bot.getX(), bot.getY() + 0.01, bot.getZ());
	}

	boolean offCentre(BlockPos cell) {
		double dx = cell.getX() + 0.5 - bot.getX(), dz = cell.getZ() + 0.5 - bot.getZ();
		return dx * dx + dz * dz > 0.05 * 0.05 && feet().getX() == cell.getX() && feet().getZ() == cell.getZ();
	}

	/** Walk towards the centre of {@code cell}. True while still on the way there. */
	boolean recentre(BlockPos cell) {
		double dx = cell.getX() + 0.5 - bot.getX(), dz = cell.getZ() + 0.5 - bot.getZ();
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1e-3) {
			return false;
		}
		double s = Math.min(Mode.WALK_SPEED, len);
		// Blocked (shouldn't happen, the cell's centre is where we planned to stand): go on as is.
		return tryMove(dx / len * s, 0, dz / len * s) && len > s;
	}

	boolean tryMove(double dx, double dy, double dz) {
		if (dx == 0 && dy == 0 && dz == 0) {
			return true;
		}
		AABB box = bot.getBoundingBox().move(dx, dy, dz);
		if (collides(box)) {
			return false;
		}
		setPosition(bot.getX() + dx, bot.getY() + dy, bot.getZ() + dz);
		return true;
	}

	/**
	 * Would the body at {@code box} run into something? Like vanilla, a block the body is already
	 * inside doesn't count: sand that fell onto its legs mustn't freeze it in place (it used to,
	 * and it drowned there), it can still swim up out of it.
	 */
	private boolean collides(AABB box) {
		AABB now = bot.getBoundingBox();
		for (VoxelShape shape : bot.level().getBlockCollisions(bot, box)) {
			for (AABB part : shape.toAabbs()) {
				if (part.intersects(box) && !part.intersects(now)) {
					return true;
				}
			}
		}
		return !bot.level().getEntityCollisions(bot, box).isEmpty();
	}

	/**
	 * Moves down by {@code dy} (negative), or as far as possible if something is in the way.
	 * Returns false if it landed on something before covering the full distance.
	 */
	boolean moveDown(double dy) {
		if (tryMove(0, dy, 0)) {
			return true;
		}
		double lo = 0, hi = dy;
		for (int i = 0; i < 12; i++) {
			double mid = (lo + hi) / 2;
			if (!collides(bot.getBoundingBox().move(0, mid, 0))) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		if (lo < 0) {
			setPosition(bot.getX(), bot.getY() + lo, bot.getZ());
		}
		return false;
	}

	void setPosition(double x, double y, double z) {
		bot.setPos(x, y, z);
		bot.setDeltaMovement(Vec3.ZERO);
		bot.setOnGround(isGrounded());
		bot.resetFallDistance();
	}

	/**
	 * Face the walking direction, eyes slightly down like a player watching their footing. A dead
	 * level gaze lines up with distant endermen's heads, which counts as staring and angers them.
	 */
	void face(double dx, double dz) {
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		bot.setYRot(yaw);
		bot.setYHeadRot(yaw);
		bot.setXRot(WALK_PITCH);
	}

	boolean isGrounded() {
		return !bot.level().noCollision(bot, bot.getBoundingBox().move(0, -0.03, 0));
	}

	boolean isInWater() {
		return LocalPathfinder.isWater(bot.level(), feet());
	}

	/** Standing, or in water (where it can hover between steps, like a player treading water). */
	boolean isSupported() {
		return isGrounded() || isInWater();
	}

	/**
	 * Gravity when not following a step: land exactly on the surface, or float up in water.
	 *
	 * @param working it's where it means to be, at a job: under water it only settles if the bottom is close
	 */
	void fall(boolean working) {
		if (isGrounded()) {
			verticalVelocity = 0;
			return;
		}
		BlockPos f = feet();
		if (LocalPathfinder.isWater(bot.level(), f)) {
			verticalVelocity = 0;
			if (LocalPathfinder.isWater(bot.level(), f.above())) {
				// Fully under water and idle: sink slowly to the bottom, like a player not swimming.
				// (Floating up used to lift it away from whatever it was mining, over and over.)
				// Running out of air is handled separately, see BotPlayer#breathe.
				// While working at a spot (e.g. mining), only settle if the bottom is right there:
				// sinking further could take it out of reach of the work, and back up again, forever.
				boolean bottomClose = !bot.level().noCollision(bot, bot.getBoundingBox().move(0, -1.0, 0));
				if (!working || bottomClose) {
					moveDown(-SINK_SPEED);
				}
				return;
			}
			double surface = f.getY() + LocalPathfinder.SWIM_FLOOR;
			double dy = Math.max(-SWIM_UP_SPEED, Math.min(SWIM_UP_SPEED, surface - bot.getY()));
			if (dy < 0) {
				moveDown(dy);
			} else {
				tryMove(0, dy, 0);
			}
			return;
		}
		verticalVelocity = Math.min(verticalVelocity, 0);
		verticalVelocity = (verticalVelocity - GRAVITY) * DRAG;
		if (!moveDown(verticalVelocity)) {
			verticalVelocity = 0;
		}
	}

	/** Water cells straight above the head, up to 64: how far it is to air. */
	public int waterAbove() {
		BlockPos head = BlockPos.containing(bot.getEyePosition());
		int n = 0;
		while (n < 64 && LocalPathfinder.isWater(bot.level(), head.above(n))) {
			n++;
		}
		return n;
	}

	/** Off a column's centre by at most this (per axis), the 0.6 wide body fits inside it. */
	private static final double IN_COLUMN = 0.18;

	/**
	 * One tick of getting to air: swim straight up; under a ceiling, sidestep towards the nearest
	 * column that's open upwards (its own one too: off its centre, the body is partly under the
	 * next column's ceiling, e.g. the ice beside the hole it just made); with none, dig through
	 * the ceiling. Works while paused. Returns true once the head is out of the water (then it
	 * just floats at the surface).
	 */
	boolean ascendStep(boolean canSprint, Hands hands) {
		ServerLevel level = bot.level();
		if (!bot.isEyeInFluid(FluidTags.WATER)) {
			fall(false);
			return true;
		}
		// As fast as the plan reckons (see Escape), or its way up takes longer than the air was kept for.
		BlockPos f = feet();
		boolean sprint = canSprint && Air.submerged(level, f) && Air.submerged(level, f.above());
		bot.setSprinting(sprint);
		if (tryMove(0, sprint ? Air.SPRINT_SWIM_SPEED : Air.SWIM_UP_SPEED, 0)) {
			return false;
		}
		BlockPos best = null;
		for (int r = 0; r <= 4 && best == null; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					BlockPos c = f.offset(dx, 0, dz);
					if (swimmable(level, c) && swimmable(level, c.above()) && swimmable(level, c.above(2))
							&& (best == null || c.distSqr(f) < best.distSqr(f))) {
						best = c;
					}
				}
			}
		}
		if (best != null) {
			double dx = best.getX() + 0.5 - bot.getX(), dz = best.getZ() + 0.5 - bot.getZ();
			double len = Math.sqrt(dx * dx + dz * dz);
			if (Math.abs(dx) > IN_COLUMN || Math.abs(dz) > IN_COLUMN) {
				double s = Math.min(LocalPathfinder.SWIM_SPEED, len);
				// Can't get over there (something in between): dig up instead of trying forever.
				if (tryMove(dx / len * s, 0, dz / len * s) || tryMove(dx / len * s, 0, 0) || tryMove(0, 0, dz / len * s)) {
					return false;
				}
			}
		}
		// The ceiling over its own column: the first cell above the head that isn't open.
		for (int k = 1; k <= 3; k++) {
			BlockPos c = f.above(k);
			if (!swimmable(level, c)) {
				hands.mineTarget(c, level.getBlockState(c).getBlock(), Hands.AUTO_TOOL, true);
				return false;
			}
		}
		return false;
	}

	private static boolean swimmable(ServerLevel level, BlockPos pos) {
		return LocalPathfinder.isOpenWater(level, pos) || LocalPathfinder.isPassable(level, pos);
	}

	void settleToGround() {
		for (int i = 0; i < 40 && !isSupported(); i++) {
			if (LocalPathfinder.isWater(bot.level(), feet())) {
				fall(false);
			} else {
				moveDown(-0.5);
			}
		}
	}

	/** Vanilla-style fall damage, measured from the highest point since leaving the ground. Water breaks the fall. */
	void trackFallDamage() {
		double y = bot.getY();
		if (LocalPathfinder.isWater(bot.level(), feet())) {
			airborneFromY = Double.NaN;
			return;
		}
		if (!isGrounded()) {
			airborneFromY = Double.isNaN(airborneFromY) ? y : Math.max(airborneFromY, y);
			return;
		}
		if (!Double.isNaN(airborneFromY)) {
			double fallen = airborneFromY - y;
			if (fallen > 3.0) {
				bot.hurtServer(bot.level(), bot.damageSources().fall(), (float) Math.ceil(fallen - 3.0));
			}
		}
		airborneFromY = Double.NaN;
	}
}
