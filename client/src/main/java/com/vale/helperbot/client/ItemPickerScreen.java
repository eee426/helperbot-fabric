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
 * through all of them. Touching an item only selects it; "下一步" goes on to how many. (Going on
 * at the first touch made a finger jump straight through to "send to the bot?".)
 */
public class ItemPickerScreen extends TouchScreen {
	private static final int CELL = 24;

	private final Screen parent;
	private final List<CreativeModeTab> tabs = new ArrayList<>();
	private int tab;
	private EditBox search;
	private List<ItemStack> shown = List.of();
	private int page;
	private ItemStack picked;
	private Button next, prevPage, nextPage;
	// Grid area, worked out in init().
	private int gridX, gridY, cols, rows;

	public ItemPickerScreen(Screen parent) {
		super(Component.literal("获取物品"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();
		// The creative tabs fill themselves on demand; make sure they're filled for this world.
		if (minecraft.player != null && minecraft.level != null) {
			CreativeModeTabs.tryRebuildTabContents(minecraft.player.connection.enabledFeatures(), false,
					minecraft.level.registryAccess());
		}
		tabs.clear();
		for (CreativeModeTab t : CreativeModeTabs.tabs()) {
			if (t.getType() == CreativeModeTab.Type.CATEGORY && t.shouldDisplay() && !items(t).isEmpty()) {
				tabs.add(t);
			}
		}
		tab = Math.min(tab, Math.max(0, tabs.size() - 1));

		int w = panelWidth(420), x = panelLeft(w);
		int rowH = rowHeight(height / 6, 1), h = rowH - GAP;
		int y = 6;
		// Category: ◀ name ▶
		big("◀", x, y, 40, h, () -> switchTab(-1));
		big("▶", x + w - 40, y, 40, h, () -> switchTab(1));
		y += rowH;
		String old = search == null ? "" : search.getValue();
		search = new EditBox(font, x, y, w, h - 4, Component.literal("搜索"));
		search.setHint(Component.literal("搜索物品（名字或 ID），不填就看上面的分类"));
		search.setValue(old);
		search.setResponder(text -> refresh());
		addRenderableWidget(search);
		y += rowH;

		// Two rows of buttons at the bottom, the selected item's name just above them.
		int bottom = height - 6 - 2 * rowH;
		gridY = y;
		cols = Math.max(1, w / CELL);
		rows = Math.max(1, (bottom - 14 - gridY) / CELL);
		gridX = x + (w - cols * CELL) / 2;
		Button[] pages = row(x, bottom, w, h, new String[]{"上一页", "下一页"},
				new Runnable[]{() -> turn(-1), () -> turn(1)});
		prevPage = pages[0];
		nextPage = pages[1];
		Button[] last = row(x, bottom + rowH, w, h, new String[]{"§a下一步：选数量", "返回"},
				new Runnable[]{this::goOn, () -> minecraft.setScreen(parent)});
		next = last[0];
		refresh();
	}

	private void switchTab(int d) {
		if (tabs.isEmpty()) {
			return;
		}
		tab = (tab + d + tabs.size()) % tabs.size();
		search.setValue("");
		refresh();
	}

	private void turn(int d) {
		page = Math.max(0, Math.min(maxPage(), page + d));
		updateButtons();
	}

	private void goOn() {
		if (picked != null) {
			minecraft.setScreen(new AmountScreen(this, picked.copy()));
		}
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
		page = 0;
		String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) {
			shown = tabs.isEmpty() ? List.of() : items(tabs.get(tab));
		} else {
			// Search through every category; the same item can be listed in several.
			Set<String> seen = new LinkedHashSet<>();
			List<ItemStack> found = new ArrayList<>();
			for (CreativeModeTab t : tabs) {
				for (ItemStack stack : items(t)) {
					String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
					String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
					if ((name.contains(q) || id.contains(q)) && seen.add(id + stack.getComponentsPatch())) {
						found.add(stack);
					}
				}
			}
			shown = found;
		}
		updateButtons();
	}

	private void updateButtons() {
		if (next != null) {
			next.active = picked != null;
			prevPage.active = page > 0;
			nextPage.active = page < maxPage();
		}
	}

	private int perPage() {
		return rows * cols;
	}

	private int maxPage() {
		return Math.max(0, (shown.size() - 1) / perPage());
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		int rowH = rowHeight(height / 6, 1);
		String heading = search.getValue().isBlank() && !tabs.isEmpty() ? tabs.get(tab).getDisplayName().getString() : "搜索结果";
		g.drawCenteredString(font, heading + "（" + (page + 1) + "/" + (maxPage() + 1) + " 页）", width / 2, 6 + (rowH - GAP) / 2 - 4, 0xFFFFFFFF);
		for (int i = 0; i < perPage(); i++) {
			int index = page * perPage() + i;
			if (index >= shown.size()) {
				break;
			}
			int x = gridX + (i % cols) * CELL, y = gridY + (i / cols) * CELL;
			ItemStack stack = shown.get(index);
			boolean chosen = picked != null && ItemStack.isSameItemSameComponents(stack, picked);
			g.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, chosen ? 0xC0E0C040 : 0x50000000);
			g.renderItem(stack, x + (CELL - 16) / 2, y + (CELL - 16) / 2);
		}
		if (shown.isEmpty()) {
			g.drawCenteredString(font, "没有找到", width / 2, gridY + 8, 0xFFAAAAAA);
		}
		int bottom = height - 6 - 2 * rowH;
		g.drawCenteredString(font, picked == null ? "点一个物品选中它，再按\"下一步\"" : "已选：" + picked.getHoverName().getString(),
				width / 2, bottom - 11, picked == null ? 0xFFAAAAAA : 0xFFFFFF80);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (guarded()) {
			return true;
		}
		double mx = event.x(), my = event.y();
		if (mx >= gridX && mx < gridX + cols * CELL && my >= gridY && my < gridY + rows * CELL) {
			int i = ((int) (my - gridY) / CELL) * cols + (int) (mx - gridX) / CELL;
			int index = page * perPage() + i;
			if (index < shown.size()) {
				picked = shown.get(index).copy(); // select only: going on is the button's job
				updateButtons();
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		turn(scrollY > 0 ? -1 : 1);
		return true;
	}
}
