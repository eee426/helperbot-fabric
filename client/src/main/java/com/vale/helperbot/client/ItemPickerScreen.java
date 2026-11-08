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
 * Second level: pick an item. First where it comes from (the Overworld, the Nether, the End, or
 * nowhere the bot can get it: those are shown greyed, to look at only), as the server says (see
 * ClientWhere), so it's clear where the bot has to be; under that the game's own creative-inventory
 * tabs (building blocks, natural blocks, tools, food...), so other mods' items show up too. A search
 * box looks through all of them. Touching an item only selects it; "下一步" goes on to how many. (Going on
 * at the first touch made a finger jump straight through to "send to the bot?".)
 *
 * <p>Scrolling works by finger: drag the list up and down (a touch that moves is a drag, one that
 * doesn't selects), or the thick bar on the right (drag it, or touch where to go). The mouse wheel
 * still works too.
 */
public class ItemPickerScreen extends TouchScreen {
	private static final int CELL = 24;
	/** Width of the scroll bar: thick enough for a thumb. */
	private static final int BAR = 16;
	/** Movement (screen pixels) under which a touch on the list counts as a tap, not a drag. */
	private static final double TAP_SLOP = 6;

	private final Screen parent;
	/** Every tab that has something to ask for; of those, the ones with items from {@link #place}. */
	private final List<CreativeModeTab> allTabs = new ArrayList<>();
	private final List<CreativeModeTab> tabs = new ArrayList<>();
	private int tab;
	private ClientWhere.Place place = ClientWhere.Place.OVERWORLD;
	private Button[] placeButtons;
	/** The answer about where things come from that the lists were made with. */
	private int seenVersion = -1;
	/** Said for a moment after touching something it can't get. */
	private String note;
	private EditBox search;
	private List<ItemStack> shown = List.of();
	/** First row shown. */
	private int scroll;
	private ItemStack picked;
	private Button next, up, down;
	// Grid and bar areas, worked out in init().
	private int gridX, gridY, cols, rows, barX;
	// A touch in progress: on the list (tap or drag), or on the bar.
	private boolean onList, onBar, dragging;
	private double pressX, pressY, moved;
	private int scrollAtPress;

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
		allTabs.clear();
		for (CreativeModeTab t : CreativeModeTabs.tabs()) {
			if (t.getType() == CreativeModeTab.Type.CATEGORY && t.shouldDisplay() && !items(t).isEmpty()) {
				allTabs.add(t);
			}
		}
		if (!ClientWhere.answered()) {
			HelperBotClient.sendQuietly("bot where");
		}

		int w = panelWidth(420), x = panelLeft(w);
		int rowH = rowHeight(height / 7, 1), h = rowH - GAP;
		int y = 6;
		// Where from: four buttons, the chosen one marked.
		ClientWhere.Place[] all = ClientWhere.Place.values();
		String[] labels = new String[all.length];
		Runnable[] actions = new Runnable[all.length];
		for (int i = 0; i < all.length; i++) {
			ClientWhere.Place p = all[i];
			labels[i] = p.label;
			actions[i] = () -> switchPlace(p);
		}
		placeButtons = row(x, y, w, h, labels, actions);
		y += rowH;
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
		cols = Math.max(1, (w - BAR - GAP) / CELL);
		rows = Math.max(1, (bottom - 14 - gridY) / CELL);
		gridX = x;
		barX = x + w - BAR;
		Button[] pages = row(x, bottom, w, h, new String[]{"▲ 往上", "▼ 往下"},
				new Runnable[]{() -> scrollTo(scroll - rows), () -> scrollTo(scroll + rows)});
		up = pages[0];
		down = pages[1];
		Button[] last = row(x, bottom + rowH, w, h, new String[]{"§a下一步：选数量", "返回"},
				new Runnable[]{this::goOn, () -> minecraft.setScreen(parent)});
		next = last[0];
		remakeTabs();
	}

	/** The tabs with something from the chosen place, and the place buttons to match. */
	private void remakeTabs() {
		seenVersion = ClientWhere.version();
		tabs.clear();
		for (CreativeModeTab t : allTabs) {
			if (!items(t, place).isEmpty()) {
				tabs.add(t);
			}
		}
		tab = Math.min(tab, Math.max(0, tabs.size() - 1));
		ClientWhere.Place[] all = ClientWhere.Place.values();
		for (int i = 0; i < placeButtons.length; i++) {
			placeButtons[i].setMessage(Component.literal(all[i] == place ? "§e[" + all[i].label + "]" : all[i].label));
			placeButtons[i].active = ClientWhere.answered() || all[i] == ClientWhere.Place.OVERWORLD;
		}
		refresh();
	}

	private void switchPlace(ClientWhere.Place p) {
		if (p != place) {
			place = p;
			tab = 0;
			search.setValue("");
			remakeTabs();
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (seenVersion != ClientWhere.version()) {
			remakeTabs(); // the server's answer came in
		}
	}

	private void switchTab(int d) {
		if (tabs.isEmpty()) {
			return;
		}
		tab = (tab + d + tabs.size()) % tabs.size();
		search.setValue("");
		refresh();
	}

	private void scrollTo(int row) {
		scroll = Math.max(0, Math.min(maxScroll(), row));
		updateButtons();
	}

	private void goOn() {
		if (picked != null) {
			minecraft.setScreen(new AmountScreen(this, picked.copy()));
		}
	}

	/** What a tab has from this place. */
	private static List<ItemStack> items(CreativeModeTab tab, ClientWhere.Place place) {
		List<ItemStack> out = new ArrayList<>();
		for (ItemStack stack : items(tab)) {
			if (ClientWhere.of(stack.getItem()) == place) {
				out.add(stack);
			}
		}
		return out;
	}

	/** What a tab offers: no spawn eggs, nothing creative-only. */
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
			shown = tabs.isEmpty() ? List.of() : items(tabs.get(tab), place);
		} else {
			// Search through every category; the same item can be listed in several.
			Set<String> seen = new LinkedHashSet<>();
			List<ItemStack> found = new ArrayList<>();
			for (CreativeModeTab t : allTabs) {
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
			up.active = scroll > 0;
			down.active = scroll < maxScroll();
		}
	}

	private int totalRows() {
		return (shown.size() + cols - 1) / cols;
	}

	private int maxScroll() {
		return Math.max(0, totalRows() - rows);
	}

	// ---- the scroll bar ---------------------------------------------------------------------

	private int trackHeight() {
		return rows * CELL;
	}

	private int thumbHeight() {
		int total = Math.max(1, totalRows());
		return Math.max(24, Math.min(trackHeight(), trackHeight() * rows / total));
	}

	private int thumbY() {
		int room = trackHeight() - thumbHeight();
		return gridY + (maxScroll() == 0 ? 0 : room * scroll / maxScroll());
	}

	/** Scrolls so the thumb is centred on screen height {@code y}. */
	private void scrollToBar(double y) {
		int room = trackHeight() - thumbHeight();
		if (room <= 0) {
			return;
		}
		double f = (y - gridY - thumbHeight() / 2.0) / room;
		scrollTo((int) Math.round(f * maxScroll()));
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		int rowH = rowHeight(height / 7, 1);
		String heading = !search.getValue().isBlank() ? "搜索结果（所有地方）"
				: tabs.isEmpty() ? place.label + "：没有" : place.label + " · " + tabs.get(tab).getDisplayName().getString();
		g.drawCenteredString(font, heading + "（" + shown.size() + " 个）", width / 2, 6 + rowH + (rowH - GAP) / 2 - 4, 0xFFFFFFFF);
		for (int i = 0; i < rows * cols; i++) {
			int index = scroll * cols + i;
			if (index >= shown.size()) {
				break;
			}
			int x = gridX + (i % cols) * CELL, y = gridY + (i / cols) * CELL;
			ItemStack stack = shown.get(index);
			boolean chosen = picked != null && ItemStack.isSameItemSameComponents(stack, picked);
			g.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, chosen ? 0xC0E0C040 : 0x50000000);
			g.renderItem(stack, x + (CELL - 16) / 2, y + (CELL - 16) / 2);
			if (ClientWhere.of(stack.getItem()) == ClientWhere.Place.NONE) {
				// Greyed: to look at only.
				g.nextStratum();
				g.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0xB0303030);
			}
		}
		if (shown.isEmpty()) {
			g.drawCenteredString(font, "没有找到", width / 2, gridY + 8, 0xFFAAAAAA);
		}
		// The bar: a dark track, a light thumb.
		g.fill(barX, gridY, barX + BAR, gridY + trackHeight(), 0x60000000);
		if (maxScroll() > 0) {
			int ty = thumbY();
			g.fill(barX + 2, ty, barX + BAR - 2, ty + thumbHeight(), onBar ? 0xFFFFFFFF : 0xFFB0B0B0);
		}
		int bottom = height - 6 - 2 * rowH;
		String line;
		if (note != null) {
			line = note;
		} else if (!ClientWhere.answered()) {
			line = "服务器还没说各物品在哪拿（可能没装新版 Helper Bot），先按原版分类显示";
		} else if (picked == null) {
			line = "点一个物品选中它，再按\"下一步\"；上下拖动列表或右边的滑条翻看";
		} else {
			ClientWhere.Place p = ClientWhere.of(picked.getItem());
			line = "已选：" + picked.getHoverName().getString() + "（" + p.label
					+ (p == ClientWhere.Place.OVERWORLD ? "" : "，要先把机器人叫到" + p.label) + "）";
		}
		g.drawCenteredString(font, line, width / 2, bottom - 11, note != null ? 0xFFFF8080 : picked == null ? 0xFFAAAAAA : 0xFFFFFF80);
	}

	private boolean inList(double x, double y) {
		return x >= gridX && x < gridX + cols * CELL && y >= gridY && y < gridY + rows * CELL;
	}

	private boolean inBar(double x, double y) {
		return x >= barX - 4 && x < barX + BAR + 4 && y >= gridY && y < gridY + trackHeight();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (guarded()) {
			return true;
		}
		double mx = event.x(), my = event.y();
		if (inBar(mx, my)) {
			onBar = true;
			scrollToBar(my);
			return true;
		}
		if (inList(mx, my)) {
			onList = true;
			dragging = false;
			moved = 0;
			pressX = mx;
			pressY = my;
			scrollAtPress = scroll;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (onBar) {
			scrollToBar(event.y());
			return true;
		}
		if (onList) {
			moved += Math.abs(dx) + Math.abs(dy);
			if (moved > TAP_SLOP) {
				dragging = true;
			}
			if (dragging) {
				// The list follows the finger: drag up to see further down.
				scrollTo(scrollAtPress - (int) Math.round((event.y() - pressY) / CELL));
			}
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (onBar) {
			onBar = false;
			return true;
		}
		if (onList) {
			onList = false;
			if (!dragging) {
				select(pressX, pressY);
			}
			return true;
		}
		return super.mouseReleased(event);
	}

	private void select(double mx, double my) {
		int i = ((int) (my - gridY) / CELL) * cols + (int) (mx - gridX) / CELL;
		int index = scroll * cols + i;
		if (index < shown.size()) {
			ItemStack stack = shown.get(index);
			if (ClientWhere.of(stack.getItem()) == ClientWhere.Place.NONE) {
				note = stack.getHoverName().getString() + "：机器人拿不到（只能看）";
				return;
			}
			note = null;
			picked = stack.copy(); // select only: going on is the button's job
			updateButtons();
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scrollTo(scroll + (scrollY > 0 ? -1 : 1));
		return true;
	}
}
