package com.vale.helperbot.guard;

import net.minecraft.world.level.BlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * The hard rules. Every break and placement the bot makes is checked here right before it
 * happens, independently of how the path was planned.
 */
public final class Guard {
	/** Blocks never stepped on or walked into: they trigger machines and traps or are farmland. */
	private static final Set<Block> NO_STEP = Set.of(
			Blocks.FARMLAND, Blocks.TRIPWIRE, Blocks.TRIPWIRE_HOOK,
			Blocks.SCULK_SENSOR, Blocks.CALIBRATED_SCULK_SENSOR, Blocks.SCULK_SHRIEKER,
			Blocks.REDSTONE_WIRE, Blocks.REPEATER, Blocks.COMPARATOR, Blocks.LEVER, Blocks.OBSERVER,
			Blocks.TURTLE_EGG, Blocks.SNIFFER_EGG, Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY,
			Blocks.SCAFFOLDING, Blocks.TNT);

	/** Blocks that hurt or trap whoever stands in or on them. */
	private static final Set<Block> DANGEROUS = Set.of(
			Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CACTUS,
			Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW, Blocks.COBWEB, Blocks.WITHER_ROSE,
			Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.POINTED_DRIPSTONE, Blocks.BUBBLE_COLUMN);

	private Guard() {
	}

	/** Never walk into or onto these, anywhere in the world, under any rules. */
	public static boolean isNoStep(BlockState state) {
		return NO_STEP.contains(state.getBlock())
				|| state.is(BlockTags.PRESSURE_PLATES)
				|| state.is(BlockTags.DOORS)
				|| state.is(BlockTags.TRAPDOORS)
				|| state.is(BlockTags.FENCE_GATES)
				|| state.is(BlockTags.BUTTONS)
				|| state.is(BlockTags.RAILS);
	}

	public static boolean isDangerous(BlockState state) {
		return DANGEROUS.contains(state.getBlock()) || state.getFluidState().is(FluidTags.LAVA);
	}

	/** Strict-mode whitelist: blocks that look like untouched terrain. */
	public static boolean isNaturalTerrain(BlockState state) {
		if (state.hasBlockEntity()) {
			return false;
		}
		if (state.getBlock() instanceof LeavesBlock) {
			return !state.getValue(LeavesBlock.PERSISTENT);
		}
		return state.is(BlockTags.DIRT)
				|| state.is(BlockTags.BASE_STONE_OVERWORLD)
				|| state.is(BlockTags.SAND)
				|| state.is(Blocks.GRAVEL)
				|| state.is(Blocks.CLAY)
				|| state.is(Blocks.SNOW_BLOCK)
				|| state.is(Blocks.SNOW);
	}

	/** May the bot break this block? */
	public static boolean canBreak(BlockGetter level, BlockPos pos, BlockState state) {
		if (ProtectionZones.isProtected(level, pos) || Restrictions.isBlocked(state.getBlock()) || state.getDestroySpeed(level, pos) < 0
				|| state.is(Blocks.TNT) || isNextTo(level, pos, true)) {
			return false;
		}
		if (Rules.open()) {
			return true;
		}
		return isNaturalTerrain(state)
				&& !(level.getBlockState(pos.above()).getBlock() instanceof FallingBlock)
				&& !isNextTo(level, pos, false);
	}

	/**
	 * May the bot break this block because it is what the task is collecting? Strict rules keep
	 * it off anything with a block entity and off player-placed leaves; everything else that a
	 * task targets is allowed (there is no placement log yet, see LAUNCH_CHECKLIST).
	 */
	public static boolean canHarvest(BlockGetter level, BlockPos pos, BlockState state) {
		if (ProtectionZones.isProtected(level, pos) || Restrictions.isBlocked(state.getBlock()) || state.getDestroySpeed(level, pos) < 0
				|| state.is(Blocks.TNT) || isNextTo(level, pos, true)) {
			return false;
		}
		if (Rules.open()) {
			return true;
		}
		if (state.getBlock() instanceof LeavesBlock) {
			return !state.getValue(LeavesBlock.PERSISTENT);
		}
		return !state.hasBlockEntity();
	}

	/**
	 * Extra planning cost (ticks) for risky breaks that OPEN rules allow: a gravity block
	 * coming down, or water flooding in.
	 */
	public static double breakRiskCost(BlockGetter level, BlockPos pos) {
		double cost = 0;
		if (level.getBlockState(pos.above()).getBlock() instanceof FallingBlock) {
			cost += 40;
		}
		if (isNextTo(level, pos, false)) {
			cost += 30;
		}
		return cost;
	}

	/** Lava (lavaOnly) or any liquid next to this block. */
	private static boolean isNextTo(BlockGetter level, BlockPos pos, boolean lavaOnly) {
		for (Direction dir : Direction.values()) {
			var fluid = level.getFluidState(pos.relative(dir));
			if (lavaOnly ? fluid.is(FluidTags.LAVA) : !fluid.isEmpty()) {
				return true;
			}
		}
		return false;
	}
}
