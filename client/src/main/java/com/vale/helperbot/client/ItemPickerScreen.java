package com.vale.helperbot.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Second level: pick an item. The categories are the game's own creative-inventory tabs (building
 * blocks, natural blocks, tools, food...), so other mods' items show up too; a search box looks
 * through all of them. Clicking an item asks how many.
 */
public class ItemPickerScreen extends Screen {
	private static final int CELL = 18;
	private static final int TAB_W = 96, TAB_H = 18;

	private final Screen parent;
	private final List<CreativeModeTab> tabs = new ArrayList<>();
	private CreativeModeTab selected;
	private EditBox search;
	private List<ItemStack> shown = List.of();
	private int scroll;
	// Grid area, worked out in init().
	private int gridX, gridY, cols, rows;

	public ItemPickerScreen(Screen parent) {
		super(Component.literal("获取物品"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		// The creative tabs fill themselves on demand; make sure they're filled for this world.
		if (minecraft.player != null && minecraft.level != null) {
			CreativeModeTabs.tryRebuildTabContents(minecraft.player.connection.enabledFeatures(), false,
					minecraft.level.registryAccess());
		}
		tabs.clear();
		for (CreativeModeTab tab : CreativeModeTabs.tabs()) {
			if (tab.getType() == CreativeModeTab.Type.CATEGORY && tab.shouldDisplay() && !items(tab).isEmpty()) {
				tabs.add(tab);
			}
		}
		int y = 30;
		for (CreativeModeTab tab : tabs) {
			addRenderableWidget(Button.builder(tab.getDisplayName(), b -> select(tab)).bounds(8, y, TAB_W, TAB_H).build());
			y += TAB_H + 2;
		}
		addRenderableWidget(Button.builder(Component.literal("返回"), b -> minecraft.setScreen(parent))
				.bounds(8, height - 26, TAB_W, 20).build());

		gridX = TAB_W + 18;
		gridY = 34;
		cols = Math.max(1, (width - gridX - 10) / CELL);
		rows = Math.max(1, (height - gridY - 12) / CELL);
		String old = search == null ? "" : search.getValue();
		search = new EditBox(font, gridX, 10, Math.min(cols * CELL, 220), 16, Component.literal("搜索"));
		search.setHint(Component.literal("搜索物品（名字或 ID）"));
		search.setValue(old);
		search.setResponder(text -> refresh());
		addRenderableWidget(search);
		if (selected == null && !tabs.isEmpty()) {
			selected = tabs.get(0);
		}
		refresh();
	}

	private void select(CreativeModeTab tab) {
		selected = tab;
		search.setValue("");
		refresh();
	}

	/** What a tab offers that a bot could be asked for: no spawn eggs, nothing creative-only. */
	private static List<ItemStack> items(CreativeModeTab tab) {
		List<ItemStack> out = new ArrayList<>();
		for (ItemStack stack : tab.getDisplayItems()) {
			if (!(stack.getItem() instanceof SpawnEggItem) && !stack.is(Items.AIR)) {
				out.add(stack);
			}
		}
		return out;
	}

	private void refresh() {
		scroll = 0;
		String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) {
			shown = selected == null ? List.of() : items(selected);
			return;
		}
		// Search through every category; the same item can be listed in several.
		Set<String> seen = new LinkedHashSet<>();
		List<ItemStack> found = new ArrayList<>();
		for (CreativeModeTab tab : tabs) {
			for (ItemStack stack : items(tab)) {
				String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
				if ((name.contains(q) || id.contains(q)) && seen.add(id + stack.getComponentsPatch())) {
					found.add(stack);
				}
			}
		}
		shown = found;
	}

	private int maxScroll() {
		int totalRows = (shown.size() + cols - 1) / cols;
		return Math.max(0, totalRows - rows);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawString(font, selected != null && search.getValue().isBlank() ? selected.getDisplayName() : Component.literal("搜索结果"),
				8, 14, 0xFFFFFFFF);
		ItemStack hovered = null;
		for (int i = 0; i < rows * cols; i++) {
			int index = (scroll * cols) + i;
			if (index >= shown.size()) {
				break;
			}
			int x = gridX + (i % cols) * CELL, y = gridY + (i / cols) * CELL;
			boolean over = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
			g.fill(x, y, x + CELL - 1, y + CELL - 1, over ? 0x80FFFFFF : 0x40000000);
			g.renderItem(shown.get(index), x + 1, y + 1);
			if (over) {
				hovered = shown.get(index);
			}
		}
		if (shown.isEmpty()) {
			g.drawString(font, "没有找到", gridX, gridY + 4, 0xFFAAAAAA);
		}
		if (maxScroll() > 0) {
			g.drawString(font, "滚轮翻页 " + (scroll + 1) + "/" + (maxScroll() + 1), width - 90, 14, 0xFFAAAAAA);
		}
		if (hovered != null) {
			g.setTooltipForNextFrame(font, hovered, mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		if (mx >= gridX && mx < gridX + cols * CELL && my >= gridY && my < gridY + rows * CELL) {
			int i = ((int) (my - gridY) / CELL) * cols + (int) (mx - gridX) / CELL;
			int index = scroll * cols + i;
			if (index < shown.size()) {
				minecraft.setScreen(new AmountScreen(this, shown.get(index).copy()));
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY)));
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
