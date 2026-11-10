package com.vale.helperbot.bot;

import com.vale.helperbot.guard.Guard;
import com.vale.helperbot.guard.Placement;
import com.vale.helperbot.path.BreakTimes;
import com.vale.helperbot.path.BuildingBlocks;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * The bot's hands: mining and placing, one block at a time, through the survival flows a player's
 * client drives (so nothing goes faster than for a player), and what's held for it. Every block it
 * ever breaks or puts down comes through here, which is why the gate is here (see {@link #mine}).
 * Where to go is the movement controller's; this only does what's asked, where it stands.
 */
public final class Hands {
	private final BotPlayer bot;
	private BlockPos breakingPos;
	private float breakProgress;
	private int breakAttempts;

	Hands(BotPlayer bot) {
		this.bot = bot;
	}

	/** The block being mined now, null if none. */
	public BlockPos breaking() {
		return breakingPos;
	}

	/** Already hitting this block (some progress made), as opposed to about to start on it. */
	public boolean isBreaking(BlockPos pos) {
		return pos.equals(breakingPos);
	}

	void resetAttempts() {
		breakAttempts = 0;
	}

	void forgetTarget() {
		harvestTarget = null;
	}

	private boolean headUnderwater() {
		return bot.isEyeInFluid(FluidTags.WATER);
	}

	/** No way out by swimming: the one time it may dig with its head under water. */
	private boolean digOut;

	/** See {@link #digOut}: only the breathing layer sets this, and no plan counts on it. */
	public void digOut(boolean allowed) {
		digOut = allowed;
	}

	/**
	 * DONE: it broke the block itself. GONE: the block changed or went without it breaking it (a
	 * kelp stalk falling off when its root went, a kelp stalk turning into a kelp tip, another
	 * player): nothing of its doing, so nothing to collect or check from it.
	 */
	public enum MineResult {
		RUNNING, DONE, GONE, FAILED,
		/** The hand check passed to {@link #mineTarget} said no: nothing was hit. */
		WRONG_HAND
	}

	/** The last block this bot broke itself (the server accepted its break), and the tick it broke. */
	private BlockPos brokeLast;
	private int brokeAt;

	/**
	 * The tick the block last reported DONE actually broke. The task may only notice a tick or
	 * many later (other layers can run first), and what dropped is told apart by that moment.
	 */
	public int lastBreakTick() {
		return brokeAt;
	}

	private MineResult finished(BlockPos pos) {
		breakingPos = null;
		harvestTarget = null;
		boolean mine = pos.equals(brokeLast);
		brokeLast = null;
		return mine ? MineResult.DONE : MineResult.GONE;
	}

	/** Tool choice for {@link #mine}: pick the fastest tool automatically. */
	public static final int AUTO_TOOL = -3;
	/** Tool choice for {@link #mine}: bare hand (an empty hotbar slot). */
	public static final int HAND = -1;

	private BlockPos harvestTarget;

	/**
	 * Mines the block a task wants, with a specific tool (inventory slot, or {@link #HAND}), going
	 * through the normal survival break flow. Call every tick until it returns something other
	 * than RUNNING: DONE once it has broken the block, GONE if the block stopped being
	 * {@code expected} some other way.
	 */
	public MineResult mineTarget(BlockPos pos, net.minecraft.world.level.block.Block expected, int toolSlot) {
		return mineTarget(pos, expected, toolSlot, false);
	}

	/**
	 * @param obstacle the block is in the way (e.g. hiding the target), not the thing being
	 *                 collected: checked against the stricter {@link Guard#canBreak} rules
	 */
	public MineResult mineTarget(BlockPos pos, net.minecraft.world.level.block.Block expected, int toolSlot, boolean obstacle) {
		return mineTarget(pos, expected, toolSlot, obstacle, null);
	}

	/**
	 * @param handOk checked on exactly what is in hand once the tool has been taken out, right
	 *               before the first hit (taking a tool out of the bag swaps it with the hand, so
	 *               checking anywhere else may look at a different item); null: no check
	 */
	public MineResult mineTarget(BlockPos pos, net.minecraft.world.level.block.Block expected, int toolSlot, boolean obstacle,
								 Predicate<ItemStack> handOk) {
		if (!pos.equals(harvestTarget)) {
			abortBreaking();
			harvestTarget = pos;
			breakAttempts = 0;
		}
		BlockState state = bot.level().getBlockState(pos);
		if (!state.is(expected)) {
			return finished(pos);
		}
		boolean allowed = obstacle ? Guard.canBreak(bot.level(), pos, state) : Guard.canHarvest(bot.level(), pos, state);
		if (!allowed) {
			return MineResult.FAILED;
		}
		return mine(pos, state, toolSlot, handOk);
	}

	/**
	 * Breaks a block the bot placed itself (its crafting table or furnace) to take it back. Skips
	 * the terrain rules, which would forbid it under strict rules; protection zones still apply.
	 */
	public MineResult mineOwnBlock(BlockPos pos, net.minecraft.world.level.block.Block expected) {
		if (!pos.equals(harvestTarget)) {
			abortBreaking();
			harvestTarget = pos;
			breakAttempts = 0;
		}
		BlockState state = bot.level().getBlockState(pos);
		if (!state.is(expected)) {
			return finished(pos);
		}
		if (com.vale.helperbot.guard.ProtectionZones.isProtected(bot.level(), pos)) {
			return MineResult.FAILED;
		}
		return mine(pos, state, AUTO_TOOL);
	}

	MineResult mine(BlockPos pos, BlockState state, int tool) {
		return mine(pos, state, tool, null);
	}

	private MineResult mine(BlockPos pos, BlockState state, int tool, Predicate<ItemStack> handOk) {
		ServerLevel level = bot.level();
		if (bot.isGhost()) {
			return MineResult.FAILED; // in a protected chunk it touches nothing (plans shouldn't ask)
		}
		// Checked every tick: water may come in halfway through. Plans never get here (see
		// LocalPathfinder#clearCost): it's for what they couldn't know.
		if (headUnderwater() && !Guard.ofTheSea(state) && !digOut) {
			abortBreaking();
			return MineResult.FAILED;
		}
		if (!pos.equals(breakingPos)) {
			abortBreaking();
			if (++breakAttempts > 3) {
				return MineResult.FAILED;
			}
			holdForMining(tool, state);
			if (handOk != null && !handOk.test(bot.getMainHandItem())) {
				breakAttempts--;
				return MineResult.WRONG_HAND;
			}
			lookAt(Vec3.atCenterOf(pos));
			breakingPos = pos;
			breakProgress = 0;
			bot.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
					faceToward(pos), level.getMaxY(), 0);
			bot.swing(InteractionHand.MAIN_HAND);
			// Instant break (kelp, grass...): it's gone already. Not just "is it air": under water
			// a broken block turns into water.
			if (level.getBlockState(pos) != state) {
				breakingPos = null;
				brokeLast = pos;
				brokeAt = bot.tickCount;
			}
			return MineResult.RUNNING;
		}
		lookAt(Vec3.atCenterOf(pos));
		breakProgress += state.getDestroyProgress(bot, level, pos);
		bot.swing(InteractionHand.MAIN_HAND);
		if (breakProgress >= 1f) {
			bot.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
					faceToward(pos), level.getMaxY(), 0);
			level.destroyBlockProgress(bot.getId(), pos, -1);
			// If the server didn't accept it, the next attempt starts over (counted by breakAttempts).
			// If it did, whatever is there next (sand falling in from above) is a fresh block.
			if (level.getBlockState(pos) != state) {
				breakAttempts = 0;
				laid.remove(pos.asLong()); // whatever is there next isn't its own
				brokeLast = pos;
				brokeAt = bot.tickCount;
			}
			breakingPos = null;
		} else {
			level.destroyBlockProgress(bot.getId(), pos, (int) (breakProgress * 10));
		}
		return MineResult.RUNNING;
	}

	/**
	 * Bare hand. It matters for drops (clay with a silk-touch pickaxe in hand gives the block, not
	 * the balls), so with a full hotbar it really empties the hand instead of keeping whatever is
	 * held: put that away in the bag, or failing that hold something that acts like a hand.
	 */
	/** Takes out what {@link #mineTarget} would use for this block: {@link #AUTO_TOOL}, {@link #HAND} or a slot. */
	public void holdForMining(int tool, BlockState state) {
		if (tool == AUTO_TOOL) {
			selectTool(state);
		} else if (tool == HAND) {
			selectEmptyHand();
		} else {
			selectSlot(tool);
		}
	}

	private void selectEmptyHand() {
		Inventory inv = bot.getInventory();
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			if (inv.getItem(i).isEmpty()) {
				inv.setSelectedSlot(i);
				return;
			}
		}
		int selected = inv.getSelectedSlot();
		for (int i = Inventory.getSelectionSize(); i < Inventory.INVENTORY_SIZE; i++) {
			if (inv.getItem(i).isEmpty()) {
				inv.setItem(i, inv.getItem(selected));
				inv.setItem(selected, ItemStack.EMPTY);
				return;
			}
		}
		// Bag full too: a plain item (no enchantment, not a tool) breaks blocks like a bare hand.
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEnchanted() && !stack.isDamageableItem()) {
				inv.setSelectedSlot(i);
				return;
			}
		}
	}

	void abortBreaking() {
		if (breakingPos != null) {
			bot.gameMode.handleBlockBreakAction(breakingPos, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
					Direction.UP, bot.level().getMaxY(), 0);
			bot.level().destroyBlockProgress(bot.getId(), breakingPos, -1);
			breakingPos = null;
		}
	}

	/** Places a building block at {@code pos} through the normal survival "use item on block" flow. */
	Placement.Refusal placeBlock(BlockPos pos) {
		ServerLevel level = bot.level();
		if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
			return null; // something solid is there already: what the step wanted
		}
		Placement.Refusal refused = Placement.check(level, pos, Placement.Kind.BUILD);
		return refused != null ? refused : place(pos, BuildingBlocks.bestSlot(bot.getInventory()));
	}

	/**
	 * Places the block item in inventory {@code slot} at {@code pos}, against any solid neighbour,
	 * through the normal survival "use item on block" flow: every block it ever puts down comes
	 * through here. The caller has asked {@link Placement#check}; this adds what only the live world
	 * can tell. Null if it's there now, otherwise why not.
	 */
	/** Where it has put blocks down itself (bridges, pillars, tables), until mined again. */
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet laid = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

	/**
	 * Did it put this block down itself? A restock digs the ground, never its own bridge: the dirt it
	 * had laid over a chasm was the nearest dirt there was, and it dug it out from under its feet.
	 */
	public boolean laid(BlockPos pos) {
		return laid.contains(pos.asLong());
	}

	public Placement.Refusal place(BlockPos pos, int slot) {
		ServerLevel level = bot.level();
		if (slot < 0) {
			return Placement.Refusal.NO_BLOCKS;
		}
		if (bot.isGhost()) {
			return Placement.Refusal.GHOST;
		}
		ItemStack stack = bot.getInventory().getItem(slot);
		if (com.vale.helperbot.guard.Restrictions.isBlocked(stack.getItem())) {
			return Placement.Refusal.BANNED_ITEM;
		}
		if (stack.getItem() instanceof net.minecraft.world.item.BlockItem item
				&& !level.isUnobstructed(item.getBlock().defaultBlockState(), pos, net.minecraft.world.phys.shapes.CollisionContext.empty())) {
			return Placement.Refusal.IN_THE_WAY;
		}
		Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
		for (Direction dir : order) {
			BlockPos against = pos.relative(dir);
			if (level.getBlockState(against).getCollisionShape(level, against).isEmpty()) {
				continue;
			}
			Direction face = dir.getOpposite();
			Vec3 hit = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			selectSlot(slot);
			lookAt(hit);
			// Sneak so clicking a chest or door places against it instead of using it.
			bot.setShiftKeyDown(true);
			bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND,
					new BlockHitResult(hit, face, against, false));
			bot.setShiftKeyDown(false);
			bot.swing(InteractionHand.MAIN_HAND);
			if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
				return Placement.Refusal.NOT_PLACED;
			}
			laid.add(pos.asLong());
			return null;
		}
		return Placement.Refusal.NOTHING_BESIDE;
	}

	private void selectTool(BlockState state) {
		Inventory inv = bot.getInventory();
		int slot = BreakTimes.bestToolSlot(inv, state);
		if (slot < 0) {
			selectEmptyHand();
			return;
		}
		selectSlot(slot);
	}

	/** Puts the item in {@code slot} into the main hand, swapping it into the hotbar if needed. */
	public void selectSlot(int slot) {
		Inventory inv = bot.getInventory();
		if (Inventory.isHotbarSlot(slot)) {
			inv.setSelectedSlot(slot);
			return;
		}
		int selected = inv.getSelectedSlot();
		ItemStack held = inv.getItem(selected);
		inv.setItem(selected, inv.getItem(slot));
		inv.setItem(slot, held);
	}

	private Direction faceToward(BlockPos pos) {
		Vec3 d = bot.getEyePosition().subtract(Vec3.atCenterOf(pos));
		double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
		if (ay >= ax && ay >= az) {
			return d.y > 0 ? Direction.UP : Direction.DOWN;
		}
		if (ax >= az) {
			return d.x > 0 ? Direction.EAST : Direction.WEST;
		}
		return d.z > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	public void lookAt(Vec3 point) {
		bot.lookAt(EntityAnchorArgument.Anchor.EYES, point);
		bot.setYHeadRot(bot.getYRot());
	}
}
