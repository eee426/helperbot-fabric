package com.vale.helperbot.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;

/**
 * Third level: how many, and how boldly (the bot's movement mode), then send. Buttons only, no
 * typing (a text box pops up the phone keyboard). Builds "/bot get &lt;item&gt; &lt;count&gt; &lt;mode&gt;";
 * for potions and tipped arrows the potion is included, written the way /give takes it.
 */
public class AmountScreen extends TouchScreen {
	/** What the server accepts (see the bot's command). */
	private static final int MAX = 64 * 36;
	private static final String[] MODES = {"aggressive", "normal", "safe"};
	private static final String[] MODE_NAMES = {"激进（默认，挖得多、走得快）", "普通", "安全（少挖、不冒险）"};

	private final Screen parent;
	private final ItemStack stack;
	private int count;
	private int mode;

	public AmountScreen(Screen parent, ItemStack stack) {
		super(Component.literal("要多少？"));
		this.parent = parent;
		this.stack = stack;
		this.count = Math.min(stack.getMaxStackSize(), 16);
	}

	@Override
	protected void init() {
		super.init();
		int w = panelWidth(340), x = panelLeft(w);
		int top = 56, rowH = rowHeight(height - top - 6, 5), h = rowH - GAP;
		int y = top;
		row(x, y, w, h, new String[]{"-10", "-1", "+1", "+10"},
				new Runnable[]{() -> add(-10), () -> add(-1), () -> add(1), () -> add(10)});
		y += rowH;
		int[] quick = {1, 8, 16, 32, 64};
		String[] labels = new String[quick.length];
		Runnable[] set = new Runnable[quick.length];
		for (int i = 0; i < quick.length; i++) {
			int q = quick[i];
			labels[i] = String.valueOf(q);
			set[i] = () -> count = q;
		}
		row(x, y, w, h, labels, set);
		y += rowH;
		addRenderableWidget(Button.builder(Component.literal(modeLabel()), b -> {
			mode = (mode + 1) % MODES.length;
			b.setMessage(Component.literal(modeLabel()));
		}).bounds(x, y, w, h).build());
		y += rowH + GAP;
		row(x, y, w, h, new String[]{"§a发送给机器人", "返回"}, new Runnable[]{this::send, () -> minecraft.setScreen(parent)});
	}

	private void add(int d) {
		count = Math.max(1, Math.min(MAX, count + d));
	}

	private String modeLabel() {
		return "模式：" + MODE_NAMES[mode];
	}

	private void send() {
		HelperBotClient.send("bot get " + itemArgument() + " " + count + " " + MODES[mode]);
	}

	/** The item as the command takes it: its ID, plus the potion for potions and tipped arrows. */
	private String itemArgument() {
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
		if (contents != null && contents.potion().isPresent()) {
			String potion = contents.potion().get().unwrapKey().map(k -> k.identifier().toString()).orElse(null);
			if (potion != null) {
				return id + "[potion_contents={potion:\"" + potion + "\"}]";
			}
		}
		return id;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		int cx = width / 2;
		g.renderItem(stack, cx - 8, 6);
		g.drawCenteredString(font, stack.getHoverName(), cx, 25, 0xFFFFFFFF);
		g.drawCenteredString(font, "要 §e§l" + count + "§r 个（1-" + MAX + "）", cx, 39, 0xFFFFFFFF);
	}
}
