package com.vale.helperbot.bot;

import com.vale.helperbot.path.LocalPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The bot's drops: whatever came out of the blocks a task mined and the mobs it killed, followed
 * until picked up, wherever they roll, slide or float to. An item counts as one of its drops if it
 * appeared near where the block broke (or the mob died) at or after that moment: told apart by its
 * age, not where it lies now, so it isn't lost when kelp floats up to the surface or a drop rolls
 * down a slope. (Merged stacks keep the younger age, so merging doesn't lose them either.)
 *
 * <p>Collecting runs as its own layer before the task. Under water, drops still floating up are
 * left to surface and collected up there in one go, like a player would, instead of chasing each.
 */
public final class Loot {
	/** Forget an event after this long. */
	private static final int MEMORY_TICKS = 2400;
	private static final int MAX_EVENTS = 96;
	/** How far drops end up from where they came out: scattered, rolled, floated up in water. */
	private static final double SPREAD_XZ = 8, SPREAD_DOWN = 4, SPREAD_UP = 32;
	/** Items that appear this soon after an event are what it dropped (for {@link #dropped}). */
	private static final int SPAWN_WINDOW = 5;
	/** Stop trying for one item after this long (out of reach, keeps drifting off...). */
	private static final int ITEM_GIVE_UP_TICKS = 200;
	/** With its head under water it only goes for drops this close; the rest wait for the surface. */
	private static final double UNDERWATER_REACH = 3;
	private static final double MAX_DISTANCE = 48;

	private static final class Event {
		final Vec3 pos;
		final int tick;
		final Set<Item> spawned = new HashSet<>();

		Event(Vec3 pos, int tick) {
			this.pos = pos;
			this.tick = tick;
		}

		AABB area() {
			return new AABB(pos.x - SPREAD_XZ, pos.y - SPREAD_DOWN, pos.z - SPREAD_XZ,
					pos.x + SPREAD_XZ, pos.y + SPREAD_UP, pos.z + SPREAD_XZ);
		}
	}

	private final BotPlayer bot;
	private final ArrayDeque<Event> events = new ArrayDeque<>();
	private final Set<UUID> abandoned = new HashSet<>();
	private final Map<UUID, Integer> aimTicks = new HashMap<>();
	private boolean collecting;
	private UUID aimedAt;

	Loot(BotPlayer bot) {
		this.bot = bot;
	}

	void onKill(Entity killed) {
		add(killed.position());
	}

	/**
	 * A block mined for a task at {@code tick} (when it broke, which may be a while before the
	 * task got round to reporting it): collect what it drops.
	 */
	public void onBreak(BlockPos pos, int tick) {
		Event e = add(Vec3.atCenterOf(pos), tick);
		// Note what came out right away: by the time it's reported, the spawn window may be over.
		noteSpawned(e);
	}

	private Event add(Vec3 pos) {
		return add(pos, bot.tickCount);
	}

	private Event add(Vec3 pos, int tick) {
		Event e = new Event(pos, tick);
		events.addLast(e);
		eventCount++;
		while (events.size() > MAX_EVENTS) {
			events.removeFirst();
		}
		return e;
	}

	/** Items that appeared within the spawn window after the event, as far as can be told now. */
	private void noteSpawned(Event e) {
		int since = bot.tickCount - e.tick;
		for (ItemEntity item : bot.level().getEntitiesOfClass(ItemEntity.class, e.area(),
				i -> i.isAlive() && i.getAge() <= since + 2 && i.getAge() >= since - SPAWN_WINDOW - 2)) {
			e.spawned.add(item.getItem().getItem());
		}
	}

	/** Every tick, whatever else is going on: note what each fresh event dropped. */
	void observe() {
		events.removeIf(e -> bot.tickCount - e.tick > MEMORY_TICKS || e.pos.distanceTo(bot.position()) > MAX_DISTANCE);
		for (Event e : events) {
			if (bot.tickCount - e.tick <= SPAWN_WINDOW) {
				noteSpawned(e);
			}
		}
	}

	/**
	 * Did breaking {@code pos} at {@code tick} drop {@code item}? Looks at what appeared right
	 * then, so it's true even if the drop has floated off or been picked up since.
	 */
	public boolean dropped(BlockPos pos, int tick, Item item) {
		Vec3 c = Vec3.atCenterOf(pos);
		for (Event e : events) {
			if (e.tick == tick && e.pos.equals(c)) {
				// Or it was picked up (walking past it) before the break was even reported.
				return e.spawned.contains(item) || lastPickup.getOrDefault(item, Integer.MIN_VALUE) >= tick;
			}
		}
		return false;
	}

	/** When it last picked each item up. */
	private final Map<Item, Integer> lastPickup = new HashMap<>();

	void onPickup(Item item) {
		lastPickup.put(item, bot.tickCount);
	}

	/** Its drops of {@code item} still lying (or floating) around. */
	public int pending(Item item) {
		int n = 0;
		for (ItemEntity e : tracked()) {
			if (e.getItem().is(item)) {
				n += e.getItem().getCount();
			}
		}
		return n;
	}

	/** Its drops that it wants and hasn't given up on. */
	private List<ItemEntity> tracked() {
		List<ItemEntity> out = new ArrayList<>();
		if (events.isEmpty()) {
			return out;
		}
		AABB all = null;
		for (Event e : events) {
			all = all == null ? e.area() : all.minmax(e.area());
		}
		for (ItemEntity item : bot.level().getEntitiesOfClass(ItemEntity.class, all,
				// Only what the task needs: spare building blocks are picked up in passing, never
				// fetched (it used to turn back, or dig out its own pillar, for one dropped block).
				i -> i.isAlive() && !abandoned.contains(i.getUUID()) && bot.taskWants(i.getItem())
						// Whatever lies in a protected chunk is left alone (it may be someone's).
						&& !com.vale.helperbot.guard.ProtectionZones.isProtected(bot.level(), i.blockPosition()))) {
			for (Event e : events) {
				if (item.getAge() <= bot.tickCount - e.tick + 2 && e.area().contains(item.position())) {
					out.add(item);
					break;
				}
			}
		}
		return out;
	}

	/**
	 * After handing control back, this long before taking it again for drops it already knew
	 * about; a new break or kill may take it right away. (Without this, drops it couldn't get to
	 * made it go "collect, done, collect" with the task every few ticks.)
	 */
	private static final int RETAKE_COOLDOWN = 100;
	private int releasedAt = Integer.MIN_VALUE / 2;
	/** Events added so far, and how many there were when it last handed control back. */
	private int eventCount, eventsAtRelease;

	/** One tick of collecting. Returns true while busy with that (the task waits meanwhile). */
	boolean tick() {
		if (!collecting && bot.tickCount - releasedAt < RETAKE_COOLDOWN && eventCount == eventsAtRelease) {
			return false;
		}
		ItemEntity drop = nextDrop();
		if (drop == null) {
			if (collecting) {
				finish("掉落物捡完了，继续");
			}
			return false;
		}
		if (!collecting) {
			collecting = true;
			bot.log("去捡掉落物：" + drop.getItem().getHoverName().getString());
		}
		if (!drop.getUUID().equals(aimedAt)) {
			aimedAt = drop.getUUID();
		}
		int ticks = aimTicks.merge(drop.getUUID(), 1, Integer::sum);
		if (ticks > ITEM_GIVE_UP_TICKS) {
			bot.log("一个" + drop.getItem().getHoverName().getString() + " " + ITEM_GIVE_UP_TICKS / 20 + " 秒都没捡到，不要了");
			abandoned.add(drop.getUUID());
			aimedAt = null;
			return true;
		}
		if (inPickupRange(drop)) {
			return true; // vanilla pickup does the rest (once the pickup delay is over)
		}
		MovementController m = bot.movement();
		if (m.isEngaged() && m.status() == MovementController.Status.FAILED) {
			bot.log("走不到那个掉落物，不要了");
			abandoned.add(drop.getUUID());
			aimedAt = null;
			return true;
		}
		if (m.status() != MovementController.Status.MOVING && m.nudgeToward(drop.position())) {
			return true;
		}
		// Re-aim when it drifted to another cell (floating, rolling), not every tick.
		if (!m.isEngaged() || m.status() != MovementController.Status.MOVING || !drop.blockPosition().equals(aimedCell)) {
			m.engage(pickupSpot(drop), drop.blockPosition(), "去捡掉落物");
			aimedCell = drop.blockPosition();
		}
		return true;
	}

	private BlockPos aimedCell;

	/**
	 * Nearest drop to go for now. With the head under water only close ones: the rest come up.
	 * The one it's already after stays wanted though: going under and coming up (or the drop
	 * drifting) mustn't switch it between "go for it" and "nothing to collect" every few ticks.
	 */
	private ItemEntity nextDrop() {
		boolean underwater = bot.movement().headUnderwater();
		ItemEntity best = null;
		for (ItemEntity e : tracked()) {
			if (underwater && bot.distanceTo(e) > UNDERWATER_REACH && !e.getUUID().equals(aimedAt)) {
				continue;
			}
			if (best == null || bot.distanceToSqr(e) < bot.distanceToSqr(best)) {
				best = e;
			}
		}
		return best;
	}

	private void finish(String why) {
		collecting = false;
		aimedAt = null;
		aimedCell = null;
		releasedAt = bot.tickCount;
		eventsAtRelease = eventCount;
		// Time spent on each drop is kept (it used to be reset here, so a drop it kept going back
		// and forth over was never given up on): only forget the ones that are gone.
		aimTicks.keySet().removeIf(id -> !(bot.level().getEntity(id) instanceof ItemEntity i) || !i.isAlive());
		bot.log(why);
		bot.movement().disengage();
	}

	private boolean inPickupRange(ItemEntity drop) {
		// The box vanilla uses for a player picking things up.
		return bot.getBoundingBox().inflate(1.0, 0.5, 1.0).intersects(drop.getBoundingBox());
	}

	/** Path goal: standing (or swimming) in this cell, the pickup box would touch the item. */
	private Predicate<BlockPos> pickupSpot(ItemEntity drop) {
		ServerLevel level = bot.level();
		AABB itemBox = drop.getBoundingBox();
		double half = bot.getBbWidth() / 2, height = bot.getBbHeight();
		return p -> {
			double floor = LocalPathfinder.floorHeight(level, p);
			// A ghost in a protected chunk doesn't pick anything up.
			if (Double.isNaN(floor) || com.vale.helperbot.guard.ProtectionZones.isProtected(level, p)) {
				return false;
			}
			double cx = p.getX() + 0.5, cz = p.getZ() + 0.5;
			return new AABB(cx - half, floor, cz - half, cx + half, floor + height, cz + half)
					.inflate(1.0, 0.5, 1.0).intersects(itemBox);
		};
	}
}
