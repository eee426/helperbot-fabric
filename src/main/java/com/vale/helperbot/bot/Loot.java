package com.vale.helperbot.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What the blocks a task mined dropped, as seen right when they broke: for checking a block dropped
 * what its loot table says (GatherTask#checkDrop), even if the drop has floated off or been picked
 * up since. Items that appear near the block at that moment are its drops (told apart by their age).
 * <p>
 * Collecting items is no part of this: whatever lies on the ground is a source for the task like
 * the blocks are (see task/DropFetch). This used to be a layer of its own, collecting only what the
 * bot itself had dropped and giving an item up for good after 10 seconds.
 */
public final class Loot {
	/** Forget a break after this long. */
	private static final int MEMORY_TICKS = 2400;
	private static final int MAX_EVENTS = 96;
	/** How far drops end up from where they came out: scattered, rolled, floated up in water. */
	private static final double SPREAD_XZ = 8, SPREAD_DOWN = 4, SPREAD_UP = 32;
	/** Items that appear this soon after a break are what it dropped. */
	private static final int SPAWN_WINDOW = 5;
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

	Loot(BotPlayer bot) {
		this.bot = bot;
	}

	/**
	 * A block mined for a task at {@code tick} (when it broke, which may be a while before the
	 * task got round to reporting it).
	 */
	public void onBreak(BlockPos pos, int tick) {
		Event e = new Event(Vec3.atCenterOf(pos), tick);
		events.addLast(e);
		while (events.size() > MAX_EVENTS) {
			events.removeFirst();
		}
		// Note what came out right away: by the time it's reported, the spawn window may be over.
		noteSpawned(e);
	}

	/** Items that appeared within the spawn window after the event, as far as can be told now. */
	private void noteSpawned(Event e) {
		int since = bot.tickCount - e.tick;
		for (ItemEntity item : bot.level().getEntitiesOfClass(ItemEntity.class, e.area(),
				i -> i.isAlive() && i.getAge() <= since + 2 && i.getAge() >= since - SPAWN_WINDOW - 2)) {
			e.spawned.add(item.getItem().getItem());
		}
	}

	/** Every tick, whatever else is going on: note what each fresh break dropped. */
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
}
