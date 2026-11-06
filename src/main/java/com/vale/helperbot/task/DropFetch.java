package com.vale.helperbot.task;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.bot.MovementController;
import com.vale.helperbot.guard.ProtectionZones;
import com.vale.helperbot.path.Air;
import com.vale.helperbot.path.Escape;
import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.Mode;
import com.vale.helperbot.path.PathGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Items lying on the ground, as a source like any other: whatever the task wants within its search
 * range is one, whoever dropped it (what the bot just mined, a kill's loot, something a player left
 * behind), weighed against blocks and mobs on the same scale, by what it takes to get each.
 * <p>
 * Fetching one goes through the path planner and follows the item as it moves (sinking, floating,
 * rolling): when it has moved, the plan is made again for where it is now. Nothing moves the bot but
 * the plan. It ends when the item is gone (picked up, or taken or despawned), or when the planner
 * finds no way to it: then that item is set aside and the task picks its next source.
 * <p>
 * It replaced a layer that collected only what the bot itself had dropped, followed each item
 * towards where it was when the chase began, nudged the bot off its planned spot at the end, and gave
 * an item up for good after 10 seconds: under water the bot swam almost up to a flint sinking into
 * the hole it had dug, stopped where the item had been, got stuck off-centre at the hole's edge,
 * and never went back for it.
 */
final class DropFetch {
	/** Re-plan once the item has moved this far from where the plan was made for. */
	private static final double REAIM_DISTANCE = 0.25;
	/** But not more often than this (a plan needs a tick or two to come back from the background). */
	private static final int REAIM_TICKS = 5;
	/** In pickup range this long with nothing picked up: something the plan didn't foresee (logged). */
	private static final int IN_RANGE_TICKS = 40;

	enum Result {
		RUNNING,
		/** It's gone from the ground (picked up, or gone some other way): on to the next source. */
		GONE,
		/** The planner finds no way to it: set aside. */
		UNREACHABLE
	}

	private ItemEntity target;
	private Vec3 aimedAt;
	private int aimedTick;
	private int inRangeTicks;
	/** Times the plan said "there, in reach of it" and it wasn't, the item not having moved. */
	private int falseArrivals;
	private final Set<UUID> unreachable = new HashSet<>();

	/** Wanted items lying within {@code radius}: not in protected chunks, not ones it found no way to. */
	List<ItemEntity> nearby(BotPlayer bot, Item item, double radius) {
		return bot.level().getEntitiesOfClass(ItemEntity.class, bot.getBoundingBox().inflate(radius),
				e -> e.isAlive() && e.getItem().is(item) && !unreachable.contains(e.getUUID())
						&& !ProtectionZones.isProtected(bot.level(), e.blockPosition()));
	}

	/** How many lie within {@code radius} (see {@link #nearby}). */
	int lying(BotPlayer bot, Item item, double radius) {
		int n = 0;
		for (ItemEntity e : nearby(bot, item, radius)) {
			n += e.getItem().getCount();
		}
		return n;
	}

	/**
	 * What getting it takes, per item, on the scale of a block's cost (GatherTask#targetCost): the
	 * walk, and under water a dive for it (see Air#diving; infinite if it's too deep for a breath).
	 */
	static double cost(BotPlayer bot, ItemEntity e, int needed, Escape escape) {
		double ticks = bot.distanceTo(e) * 5;
		BlockPos at = e.blockPosition();
		if (LocalPathfinder.isWater(bot.level(), at)) {
			ticks += Air.diving(bot.movement().airProfile(), 1, Air.PICKUP_TICKS, escape.ticks(at));
		}
		return ticks / Math.max(1, Math.min(e.getItem().getCount(), needed));
	}

	boolean active() {
		return target != null;
	}

	void start(BotPlayer bot, ItemEntity e, Mode mode) {
		target = e;
		inRangeTicks = 0;
		falseArrivals = 0;
		bot.log("去捡地上的" + e.getItem().getHoverName().getString() + "（" + e.blockPosition().toShortString() + "）");
		bot.movement().goTo(pickupSpot(bot, e), e.blockPosition(), mode);
		aimedAt = e.position();
		aimedTick = bot.tickCount;
	}

	void stop() {
		target = null;
	}

	Result tick(BotPlayer bot, Mode mode) {
		ItemEntity e = target;
		if (e == null || !e.isAlive()) {
			target = null;
			return Result.GONE;
		}
		if (inPickupRange(bot, e)) {
			// Vanilla picks it up (once its pickup delay is over, and there's room in the bag).
			if (++inRangeTicks == IN_RANGE_TICKS && !e.hasPickUpDelay()) {
				bot.log("异常：已经在拾取范围里 " + IN_RANGE_TICKS / 20 + " 秒，" + e.getItem().getHoverName().getString()
						+ "还没吸进来（背包空位 " + bot.getInventory().getFreeSlot() + "）。这是 bug，请把这段日志发给开发者");
			}
			return Result.RUNNING;
		}
		inRangeTicks = 0;
		MovementController m = bot.movement();
		if (m.status() == MovementController.Status.FAILED) {
			bot.log("去" + e.getItem().getHoverName().getString() + "那里的路找不到（" + m.failReason() + "），先不管它");
			unreachable.add(e.getUUID());
			target = null;
			return Result.UNREACHABLE;
		}
		// Moved since the plan was made, or the plan is over (its goal was where the item used to be).
		boolean moved = aimedAt == null || e.position().distanceTo(aimedAt) > REAIM_DISTANCE;
		boolean idle = m.status() != MovementController.Status.MOVING;
		if (idle && !moved && m.status() == MovementController.Status.ARRIVED && ++falseArrivals >= 3) {
			// The planner's idea of where it stands and what it reaches isn't what happens: a bug to
			// fix there, not something to work around here.
			bot.log("异常：规划认为到了" + e.getItem().getHoverName().getString() + "的拾取范围，实际差一点（机器人 "
					+ String.format(java.util.Locale.ROOT, "%.2f, %.2f, %.2f，物品 %.2f, %.2f, %.2f", bot.getX(), bot.getY(), bot.getZ(),
					e.getX(), e.getY(), e.getZ()) + "）。这是 bug，请把这段日志发给开发者；先不管它");
			unreachable.add(e.getUUID());
			target = null;
			return Result.UNREACHABLE;
		}
		if (idle || (moved && bot.tickCount - aimedTick >= REAIM_TICKS)) {
			aim(bot, mode, !idle);
		}
		return Result.RUNNING;
	}

	/** Plans (again) for where the item is now. */
	private void aim(BotPlayer bot, Mode mode, boolean moving) {
		PathGoal goal = pickupSpot(bot, target);
		if (moving) {
			bot.movement().retarget(goal, target.blockPosition());
		} else {
			bot.movement().follow(goal, target.blockPosition(), mode);
		}
		aimedAt = target.position();
		aimedTick = bot.tickCount;
	}

	/** The box vanilla uses for a player picking things up. */
	static boolean inPickupRange(BotPlayer bot, ItemEntity e) {
		return bot.getBoundingBox().inflate(1.0, 0.5, 1.0).intersects(e.getBoundingBox());
	}

	/** Path goal: standing (or swimming) in this cell, the pickup box touches the item where it is now. */
	private static PathGoal pickupSpot(BotPlayer bot, ItemEntity e) {
		AABB itemBox = e.getBoundingBox();
		double half = bot.getBbWidth() / 2, height = bot.getBbHeight();
		return (w, p) -> {
			double floor = LocalPathfinder.floorHeight(w, p);
			// A ghost in a protected chunk doesn't pick anything up.
			if (Double.isNaN(floor) || ProtectionZones.isProtected(w, p)) {
				return false;
			}
			double cx = p.getX() + 0.5, cz = p.getZ() + 0.5;
			return new AABB(cx - half, floor, cz - half, cx + half, floor + height, cz + half)
					.inflate(1.0, 0.5, 1.0).intersects(itemBox);
		};
	}
}
