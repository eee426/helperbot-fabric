package com.vale.helperbot.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

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
		if (target.getBoundingBox().distanceToSqr(eye) > reach * reach) {
			return false;
		}
		return level.clip(new ClipContext(eye, target.getEyePosition(), ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, self)).getType() == HitResult.Type.MISS;
	}

	/** Path goal: standing in this cell, could it hit {@code target}? */
	public static Predicate<BlockPos> attackSpot(BotPlayer bot, Entity target) {
		ServerLevel level = bot.level();
		double eyeHeight = bot.getEyeHeight();
		// A little inside the real reach, so a slightly moving target is still in range on arrival.
		double reach = bot.entityInteractionRange() - 0.3;
		return p -> canHitFrom(level, new Vec3(p.getX() + 0.5, p.getY() + eyeHeight, p.getZ() + 0.5), target, reach, bot);
	}
}
