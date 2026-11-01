package com.vale.helperbot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Picks the best tool the player gave the bot and estimates mining time with it. */
public final class BreakTimes {
	private BreakTimes() {
	}

	/** Inventory slot (0-35) of the fastest tool for this block, or -1 to use the bare hand. */
	public static int bestToolSlot(Inventory inventory, BlockState state) {
		int best = -1;
		float bestScore = score(ItemStack.EMPTY, state);
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1)) {
				continue;
			}
			float s = score(stack, state);
			if (s > bestScore) {
				bestScore = s;
				best = slot;
			}
		}
		return best;
	}

	private static float score(ItemStack stack, BlockState state) {
		boolean correct = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
		return Math.max(1f, stack.getDestroySpeed(state)) / (correct ? 30f : 100f);
	}

	/** Estimated ticks to mine, ignoring enchantments and effects (planning only). */
	public static int estimateTicks(Inventory inventory, BlockGetter level, BlockPos pos, BlockState state) {
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0) {
			return Integer.MAX_VALUE;
		}
		if (hardness == 0) {
			return 1;
		}
		int slot = bestToolSlot(inventory, state);
		ItemStack tool = slot < 0 ? ItemStack.EMPTY : inventory.getItem(slot);
		float perTick = score(tool, state) / hardness;
		return (int) Math.ceil(1f / perTick);
	}
}
