package com.vale.helperbot.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.vale.helperbot.path.PathGoal;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Where the bot could hit an entity from: within arm's reach of its hitbox AND with a clear line
 * of sight, the same two things vanilla needs for a player. Used as a path goal, so the bot walks
 * to a spot it can actually attack from, not one that's merely close (e.g. with a block in between
 * on the diagonal).
 */
public final class Sight {
	private Sight() {
	}

	public static boolean canHitFrom(ServerLevel level, Vec3 eye, Entity target, double reach, Entity self) {
		return canHitFrom(level, eye, target.getBoundingBox(), target.getEyePosition(), reach);
	}

	private static boolean canHitFrom(BlockGetter world, Vec3 eye, AABB box, Vec3 targetEye, double reach) {
		if (box.distanceToSqr(eye) > reach * reach) {
			return false;
		}
		return world.clip(new ClipContext(eye, targetEye, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, CollisionContext.empty())).getType() == HitResult.Type.MISS;
	}

	/** Path goal: standing in this cell, could it hit {@code target}? */
	public static PathGoal attackSpot(BotPlayer bot, Entity target) {
		// Where the target is now (the goal is made again when it moves off).
		AABB box = target.getBoundingBox();
		Vec3 targetEye = target.getEyePosition();
		double eyeHeight = bot.getEyeHeight();
		// A little inside the real reach, so a slightly moving target is still in range on arrival.
		double reach = bot.entityInteractionRange() - 0.3;
		// Not from inside a protected chunk: it's a ghost there and can't hit anything.
		return (w, p) -> !com.vale.helperbot.guard.ProtectionZones.isProtected(w, p)
				&& canHitFrom(w, com.vale.helperbot.path.MineSpot.eyeAt(w, p, eyeHeight), box, targetEye, reach);
	}
}
