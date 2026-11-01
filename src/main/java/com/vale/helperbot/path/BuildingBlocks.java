package com.vale.helperbot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/** Which inventory blocks the bot may spend on bridges and pillars, cheapest first. */
public final class BuildingBlocks {
	private static final Set<Block> JUNK = Set.of(
			Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE,
			Blocks.NETHERRACK, Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.TUFF,
			Blocks.DEEPSLATE, Blocks.BLACKSTONE, Blocks.END_STONE, Blocks.SANDSTONE, Blocks.MUD,
			Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK, Blocks.MOSS_BLOCK);

	private BuildingBlocks() {
	}

	/** Lower is spent first; -1 means never use this item for building. */
	private static int priority(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
			return -1;
		}
		Block block = item.getBlock();
		if (JUNK.contains(block)) {
			return 0;
		}
		BlockState state = block.defaultBlockState();
		if (block instanceof FallingBlock || state.hasBlockEntity()
				|| !state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
			return -1;
		}
		return state.is(BlockTags.PLANKS) ? 1 : -1;
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
