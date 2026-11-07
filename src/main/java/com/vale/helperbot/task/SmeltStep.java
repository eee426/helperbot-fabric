package com.vale.helperbot.task;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.knowledge.CraftPlanner;
import com.vale.helperbot.knowledge.RecipeIndex;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FuelValues;

/**
 * Smelts N items in the bot's own furnace, at vanilla speed: put the furnace down, load inputs and
 * fuel (64 at a time, the slot limit), wait while it cooks, take the results, pick the furnace up.
 */
final class SmeltStep {
	private static final int INPUT = 0;
	private static final int FUEL = 1;
	private static final int RESULT = 2;
	/** Give up if the furnace makes no progress for this long. */
	private static final int STALL_TICKS = 600;

	private enum Phase {
		PLACE, LOAD, WAIT, UNLOAD, PICK_UP
	}

	private final ServerPlayer requester;
	private final RecipeIndex.Smelt recipe;
	private final int total;
	private final Station furnace;
	private Phase phase = Phase.PLACE;
	private int loaded;
	private int collected;
	private int stalled;
	private int lastInputCount = -1;
	private String failure;

	SmeltStep(ServerPlayer requester, CraftPlanner.Smelt action, OutOfReach unreachable) {
		this.furnace = new Station(Items.FURNACE, Blocks.FURNACE, unreachable);
		this.requester = requester;
		this.recipe = action.recipe();
		this.total = action.count();
	}

	String failure() {
		return failure;
	}

	BotTask.Status tick(BotPlayer bot) {
		switch (phase) {
			case PLACE -> {
				BotTask.Status s = furnace.placeTick(bot);
				if (s == BotTask.Status.FAILED) {
					failure = furnace.failure();
					return BotTask.Status.FAILED;
				}
				if (s == BotTask.Status.DONE) {
					phase = Phase.LOAD;
				}
			}
			case LOAD -> {
				return load(bot);
			}
			case WAIT -> {
				return waitForResults(bot);
			}
			case UNLOAD -> {
				AbstractFurnaceBlockEntity entity = entity(bot);
				if (entity != null) {
					for (int slot : new int[]{INPUT, FUEL, RESULT}) {
						give(bot, entity.removeItemNoUpdate(slot));
					}
					entity.setChanged();
				}
				phase = Phase.PICK_UP;
			}
			case PICK_UP -> {
				return furnace.retrieve(bot, requester);
			}
		}
		return BotTask.Status.RUNNING;
	}

	private AbstractFurnaceBlockEntity entity(BotPlayer bot) {
		return bot.level().getBlockEntity(furnace.pos()) instanceof AbstractFurnaceBlockEntity f ? f : null;
	}

	/** Next batch of inputs (up to a stack of one kind) plus enough fuel for it. */
	private BotTask.Status load(BotPlayer bot) {
		AbstractFurnaceBlockEntity entity = entity(bot);
		if (entity == null) {
			failure = "熔炉不见了";
			return BotTask.Status.FAILED;
		}
		Inventory inv = bot.getInventory();
		bot.movement().hands().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(furnace.pos()));
		if (entity.getItem(INPUT).isEmpty()) {
			ItemStack batch = take(inv, s -> recipe.input().test(s), Math.min(64, total - loaded));
			if (batch.isEmpty()) {
				failure = "要熔炼的材料不够了";
				phase = Phase.UNLOAD;
				return collected > 0 ? BotTask.Status.RUNNING : BotTask.Status.FAILED;
			}
			loaded += batch.getCount();
			entity.setItem(INPUT, batch);
		}
		if (!topUpFuel(bot, entity)) {
			failure = "没有燃料了";
			phase = Phase.UNLOAD;
			return BotTask.Status.RUNNING;
		}
		entity.setChanged();
		phase = Phase.WAIT;
		return BotTask.Status.RUNNING;
	}

	/** Make sure there's fuel for what's in the input slot. False if none can be found. */
	private boolean topUpFuel(BotPlayer bot, AbstractFurnaceBlockEntity entity) {
		FuelValues fuel = bot.level().fuelValues();
		ItemStack current = entity.getItem(FUEL);
		int needTicks = entity.getItem(INPUT).getCount() * recipe.cookingTime();
		int haveTicks = current.isEmpty() ? 0 : current.getCount() * fuel.burnDuration(current);
		if (haveTicks >= needTicks || isLit(bot) && !current.isEmpty()) {
			return true;
		}
		Inventory inv = bot.getInventory();
		// The best fuel we have that isn't what we're smelting; same kind as the slot already holds.
		ItemStack best = ItemStack.EMPTY;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && fuel.isFuel(s) && !s.is(Items.LAVA_BUCKET) && !recipe.input().test(s)
					&& (current.isEmpty() || ItemStack.isSameItemSameComponents(s, current))
					&& fuel.burnDuration(s) > (best.isEmpty() ? 0 : fuel.burnDuration(best))) {
				best = s;
			}
		}
		if (best.isEmpty()) {
			return !current.isEmpty();
		}
		int per = fuel.burnDuration(best);
		int units = Math.max(1, (needTicks - haveTicks + per - 1) / per);
		Item fuelItem = best.getItem();
		ItemStack add = take(inv, s -> s.is(fuelItem), Math.min(units, 64 - current.getCount()));
		if (add.isEmpty()) {
			return !current.isEmpty();
		}
		if (current.isEmpty()) {
			entity.setItem(FUEL, add);
		} else {
			current.grow(add.getCount());
		}
		return true;
	}

	private boolean isLit(BotPlayer bot) {
		var state = bot.level().getBlockState(furnace.pos());
		return state.hasProperty(AbstractFurnaceBlock.LIT) && state.getValue(AbstractFurnaceBlock.LIT);
	}

	private BotTask.Status waitForResults(BotPlayer bot) {
		AbstractFurnaceBlockEntity entity = entity(bot);
		if (entity == null) {
			failure = "熔炉不见了";
			return BotTask.Status.FAILED;
		}
		ItemStack result = entity.removeItemNoUpdate(RESULT);
		if (!result.isEmpty()) {
			collected += result.getCount();
			give(bot, result);
			entity.setChanged();
		}
		int inputLeft = entity.getItem(INPUT).getCount();
		if (inputLeft == 0) {
			stalled = 0;
			lastInputCount = -1;
			phase = loaded >= total ? Phase.UNLOAD : Phase.LOAD;
			return BotTask.Status.RUNNING;
		}
		// Ran out of fuel mid-batch: add more.
		if (!isLit(bot) && entity.getItem(FUEL).isEmpty() && !topUpFuel(bot, entity)) {
			failure = "燃料烧完了";
			phase = Phase.UNLOAD;
			return BotTask.Status.RUNNING;
		}
		if (inputLeft == lastInputCount) {
			if (++stalled > STALL_TICKS) {
				failure = "熔炉不工作了";
				phase = Phase.UNLOAD;
			}
		} else {
			stalled = 0;
			lastInputCount = inputLeft;
		}
		return BotTask.Status.RUNNING;
	}

	/** Removes up to {@code max} items matching {@code match}, all of the first matching kind. */
	private static ItemStack take(Inventory inv, java.util.function.Predicate<ItemStack> match, int max) {
		ItemStack result = ItemStack.EMPTY;
		for (int i = 0; i < inv.getContainerSize() && (result.isEmpty() || result.getCount() < max); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || !match.test(s) || (!result.isEmpty() && !ItemStack.isSameItemSameComponents(s, result))) {
				continue;
			}
			int n = Math.min(s.getCount(), max - (result.isEmpty() ? 0 : result.getCount()));
			ItemStack part = s.split(n);
			if (result.isEmpty()) {
				result = part;
			} else {
				result.grow(part.getCount());
			}
		}
		return result;
	}

	private static void give(BotPlayer bot, ItemStack stack) {
		if (!stack.isEmpty() && !bot.getInventory().add(stack)) {
			bot.drop(stack, false);
		}
	}

	Component describe() {
		return Component.literal("熔炼 ").append(CraftPlanner.name(recipe.result()))
				.append("（" + collected + "/" + total * recipe.resultCount() + "）");
	}
}
