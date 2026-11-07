package com.vale.helperbot.task;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.knowledge.CraftPlanner;
import com.vale.helperbot.knowledge.RecipeIndex;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Crafts a recipe N times through vanilla's own recipe-book placement: the same code that fills
 * the grid when a player clicks a recipe, then a shift-click on the result. Mixed ingredients
 * (oak and birch planks), container remainders (buckets from cake) etc. are all handled by vanilla.
 * 3x3 recipes use the bot's own crafting table, put down for the job and picked up afterwards.
 */
final class CraftStep {
	/** Ticks between crafts, so it doesn't look instant. */
	private static final int CRAFT_INTERVAL = 4;
	private static final int RESULT_SLOT = 0;

	private enum Phase {
		PLACE_TABLE, CRAFT, PICK_UP_TABLE
	}

	private final ServerPlayer requester;
	private final RecipeIndex.Craft recipe;
	private final int times;
	private final Station table;
	private Phase phase;
	private AbstractCraftingMenu menu;
	private int done;
	private int cooldown;
	private String failure;

	CraftStep(ServerPlayer requester, CraftPlanner.Craft action, OutOfReach unreachable) {
		this.table = new Station(Items.CRAFTING_TABLE, Blocks.CRAFTING_TABLE, unreachable);
		this.requester = requester;
		this.recipe = action.recipe();
		this.times = action.times();
		this.phase = recipe.needsTable() ? Phase.PLACE_TABLE : Phase.CRAFT;
	}

	String failure() {
		return failure;
	}

	BotTask.Status tick(BotPlayer bot) {
		return switch (phase) {
			case PLACE_TABLE -> {
				BotTask.Status s = table.placeTick(bot);
				if (s == BotTask.Status.FAILED) {
					failure = table.failure();
					yield BotTask.Status.FAILED;
				}
				if (s == BotTask.Status.DONE) {
					phase = Phase.CRAFT;
				}
				yield BotTask.Status.RUNNING;
			}
			case CRAFT -> craft(bot);
			case PICK_UP_TABLE -> table.retrieve(bot, requester);
		};
	}

	private BotTask.Status craft(BotPlayer bot) {
		if (cooldown-- > 0) {
			return BotTask.Status.RUNNING;
		}
		cooldown = CRAFT_INTERVAL;
		Inventory inv = bot.getInventory();
		if (menu == null) {
			menu = recipe.needsTable()
					? new CraftingMenu(0, inv, ContainerLevelAccess.create(bot.level(), table.pos()))
					: bot.inventoryMenu;
		}
		int before = count(inv);
		bot.movement().hands().lookAt(table.pos() != null
				? net.minecraft.world.phys.Vec3.atCenterOf(table.pos()) : bot.getEyePosition().add(bot.getLookAngle()));
		menu.handlePlacement(false, false, recipe.holder(), bot.level(), inv);
		menu.quickMoveStack(bot, RESULT_SLOT);
		bot.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		if (count(inv) <= before) {
			finish(bot);
			failure = "合成" + CraftPlanner.name(recipe.result()).getString() + "失败（材料不够或摆不进去）";
			return BotTask.Status.FAILED;
		}
		if (++done >= times) {
			finish(bot);
			if (recipe.needsTable()) {
				phase = Phase.PICK_UP_TABLE;
				return BotTask.Status.RUNNING;
			}
			return BotTask.Status.DONE;
		}
		return BotTask.Status.RUNNING;
	}

	/** Puts anything left in the crafting grid back into the inventory. */
	private void finish(BotPlayer bot) {
		if (menu != null) {
			menu.removed(bot);
		}
	}

	private int count(Inventory inv) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).is(recipe.result())) {
				n += inv.getItem(i).getCount();
			}
		}
		return n;
	}

	Component describe() {
		return Component.literal("合成 ").append(CraftPlanner.name(recipe.result()))
				.append("（" + done * recipe.resultCount() + "/" + times * recipe.resultCount() + "）");
	}
}
