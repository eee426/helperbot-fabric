package com.vale.helperbot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Which inventory blocks the bot may spend on bridges and pillars, cheapest first: the ground's own
 * (see knowledge/BuildingSources, set at server start), then planks. And how many it keeps.
 */
public final class BuildingBlocks {
	/** Picked up as they come, up to this many; never more. */
	public static final int MOST = 120;
	/** Fewer than this: the restock reflex tops up when there's ground to dig around (see BotPlayer#restock). */
	public static final int LOW = 32;
	/** What a restock tops up to. */
	public static final int TOP_UP = 64;

	/** Items the ground gives when dug (see knowledge/BuildingSources), set at server start and on /reload. */
	private static volatile Set<Item> ground = Set.of();

	private BuildingBlocks() {
	}

	public static void setGround(Set<Item> items) {
		ground = Set.copyOf(items);
	}

	/** Lower is spent first; -1 means never use this item for building. */
	private static int priority(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
			return -1;
		}
		Block block = item.getBlock();
		if (com.vale.helperbot.guard.Restrictions.isBlocked(block)) {
			return -1;
		}
		BlockState state = block.defaultBlockState();
		if (block instanceof FallingBlock || state.hasBlockEntity()
				|| !state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
			return -1;
		}
		return ground.contains(item) ? 0 : state.is(BlockTags.PLANKS) ? 1 : -1;
	}

	public static boolean isBuildingBlock(ItemStack stack) {
		return priority(stack) >= 0;
	}

	/** Inventory slot of the block to place next, or -1 if there is none. */
	public static int bestSlot(Inventory inventory) {
		int best = -1;
		int bestPriority = Integer.MAX_VALUE;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			int p = priority(inventory.getItem(slot));
			if (p >= 0 && p < bestPriority) {
				best = slot;
				bestPriority = p;
			}
		}
		return best;
	}

	public static int count(Inventory inventory) {
		int n = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (priority(stack) >= 0) {
				n += stack.getCount();
			}
		}
		return n;
	}
}
