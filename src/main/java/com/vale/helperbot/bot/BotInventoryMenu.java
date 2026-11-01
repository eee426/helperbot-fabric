package com.vale.helperbot.bot;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A 5-row chest screen (works on vanilla clients) showing the bot's inventory:
 * rows 1-3 main inventory, row 4 hotbar, row 5 helmet/chest/legs/boots/offhand + 4 locked fillers.
 */
public class BotInventoryMenu extends AbstractContainerMenu {
	private static final int BOT_SLOTS = 45;

	private final BotPlayer bot;

	public BotInventoryMenu(int containerId, Inventory playerInventory, BotPlayer bot) {
		super(MenuType.GENERIC_9x5, containerId);
		this.bot = bot;
		Inventory botInv = bot.getInventory();

		for (int i = 0; i < 27; i++) {
			addSlot(new Slot(botInv, 9 + i, 8 + (i % 9) * 18, 18 + (i / 9) * 18));
		}
		for (int i = 0; i < 9; i++) {
			addSlot(new Slot(botInv, i, 8 + i * 18, 72));
		}
		EquipmentSlot[] armor = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		int[] armorIndex = {39, 38, 37, 36};
		for (int i = 0; i < 4; i++) {
			addSlot(new ArmorSlot(botInv, armorIndex[i], 8 + i * 18, 90, armor[i], bot));
		}
		addSlot(new Slot(botInv, Inventory.SLOT_OFFHAND, 8 + 4 * 18, 90));
		SimpleContainer filler = new SimpleContainer(4);
		for (int i = 0; i < 4; i++) {
			filler.setItem(i, new ItemStack(Items.GRAY_STAINED_GLASS_PANE));
			addSlot(new LockedSlot(filler, i, 8 + (5 + i) * 18, 90));
		}

		addStandardInventorySlots(playerInventory, 8, 121);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasItem() || slot instanceof LockedSlot) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();
		if (index < BOT_SLOTS) {
			if (!moveItemStackTo(stack, BOT_SLOTS, slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else if (!moveItemStackTo(stack, 0, 36, false)) {
			return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		return original;
	}

	@Override
	public boolean stillValid(Player player) {
		return bot.isAlive() && !bot.isRemoved() && player.distanceToSqr(bot) < 64;
	}

	private static class ArmorSlot extends Slot {
		private final EquipmentSlot equipmentSlot;
		private final BotPlayer bot;

		ArmorSlot(Container container, int index, int x, int y, EquipmentSlot equipmentSlot, BotPlayer bot) {
			super(container, index, x, y);
			this.equipmentSlot = equipmentSlot;
			this.bot = bot;
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return bot.getEquipmentSlotForItem(stack) == equipmentSlot;
		}

		@Override
		public int getMaxStackSize() {
			return 1;
		}
	}

	private static class LockedSlot extends Slot {
		LockedSlot(Container container, int index, int x, int y) {
			super(container, index, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}

		@Override
		public boolean mayPickup(Player player) {
			return false;
		}
	}
}
