package com.vale.helperbot.path;

import com.vale.helperbot.HelperBot;
import com.vale.helperbot.guard.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * What the pathfinder asks of a block state over and over, worked out once per state: is its
 * collision shape empty, how tall is it, and is it "clean" (no liquid, no hazard, nothing the
 * bot must not step on). Built at server start and after a data pack reload (tags may change).
 * <p>
 * Only states whose shape doesn't depend on where they stand are in the table: the game itself
 * caches their shape the same way. The rest (bamboo, scaffolding...) are looked at in place.
 * Rules that depend on position (protected chunks, loaded chunks, what may be dug) stay outside.
 */
public final class BlockTable {
	private static final byte KNOWN = 1;
	private static final byte EMPTY = 2;
	private static final byte CLEAN = 4;
	private static final byte DANGEROUS = 8;

	private record Table(byte[] flags, double[] top) {
	}

	private static volatile Table table;

	private BlockTable() {
	}

	public static void build() {
		int n = Block.BLOCK_STATE_REGISTRY.size();
		byte[] flags = new byte[n];
		double[] top = new double[n];
		for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
			int id = Block.getId(state);
			byte f = 0;
			if (Guard.isDangerous(state)) {
				f |= DANGEROUS;
			} else if (state.getFluidState().isEmpty() && !Guard.isNoStep(state)) {
				f |= CLEAN;
			}
			if (!state.getBlock().hasDynamicShape()) {
				f |= KNOWN;
				VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
				if (shape.isEmpty()) {
					f |= EMPTY;
				} else {
					top[id] = shape.max(Direction.Axis.Y);
				}
			}
			flags[id] = f;
		}
		table = new Table(flags, top);
		if (System.getenv("HELPERBOT_SCENARIOS") != null) {
			selfCheck();
		}
	}

	/** True if the state has no collision box. */
	public static boolean empty(BlockState state, BlockGetter level, BlockPos pos) {
		Table t = table;
		if (t != null) {
			byte f = t.flags[Block.getId(state)];
			if ((f & KNOWN) != 0) {
				return (f & EMPTY) != 0;
			}
		}
		return state.getCollisionShape(level, pos).isEmpty();
	}

	/** Top of the collision box (0 to 1.5), or 0 if it has none. */
	public static double top(BlockState state, BlockGetter level, BlockPos pos) {
		Table t = table;
		if (t != null) {
			int id = Block.getId(state);
			if ((t.flags[id] & KNOWN) != 0) {
				return t.top[id];
			}
		}
		VoxelShape shape = state.getCollisionShape(level, pos);
		return shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
	}

	/** No liquid, nothing that hurts, nothing the bot must not step on (see {@link Guard}). */
	public static boolean clean(BlockState state) {
		Table t = table;
		if (t != null) {
			return (t.flags[Block.getId(state)] & CLEAN) != 0;
		}
		return state.getFluidState().isEmpty() && !Guard.isDangerous(state) && !Guard.isNoStep(state);
	}

	public static boolean dangerous(BlockState state) {
		Table t = table;
		if (t != null) {
			return (t.flags[Block.getId(state)] & DANGEROUS) != 0;
		}
		return Guard.isDangerous(state);
	}

	/**
	 * Test runs only: the table must agree with asking the block directly, at a position other
	 * than the one it was built at (catches shapes that move with position after all).
	 */
	private static void selfCheck() {
		BlockPos far = new BlockPos(12345, 64, -6789);
		int wrong = 0;
		for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
			VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, far);
			double realTop = shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
			boolean realClean = state.getFluidState().isEmpty() && !Guard.isDangerous(state) && !Guard.isNoStep(state);
			if (empty(state, EmptyBlockGetter.INSTANCE, far) != shape.isEmpty()
					|| Math.abs(top(state, EmptyBlockGetter.INSTANCE, far) - realTop) > 1e-6
					|| clean(state) != realClean
					|| dangerous(state) != Guard.isDangerous(state)) {
				wrong++;
				HelperBot.LOGGER.warn("BlockTable disagrees with the block itself: {}", state);
			}
		}
		HelperBot.LOGGER.info("BlockTable self-check: {} states, {} wrong", Block.BLOCK_STATE_REGISTRY.size(), wrong);
	}
}
