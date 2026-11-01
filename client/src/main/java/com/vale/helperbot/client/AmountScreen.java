package com.vale.helperbot.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;

/**
 * Third level: how many, and how boldly (the bot's movement mode), then send. Builds
 * "/bot get &lt;item&gt; &lt;count&gt; &lt;mode&gt;"; for potions and tipped arrows the potion is
 * included, written the way /give takes it.
 */
public class AmountScreen extends Screen {
	/** What the server accepts (see the bot's command). */
	private static final int MAX = 64 * 36;
	private static final String[] MODES = {"aggressive", "normal", "safe"};
	private static final String[] MODE_NAMES = {"激进（默认，挖得多、走得快）", "普通", "安全（少挖、不冒险）"};

	private final Screen parent;
	private final ItemStack stack;
	private EditBox amount;
	private Button modeButton;
	private int mode;

	public AmountScreen(Screen parent, ItemStack stack) {
		super(Component.literal("要多少？"));
		this.parent = parent;
		this.stack = stack;
	}

	@Override
	protected void init() {
		int cx = width / 2, y = height / 2 - 30;
		amount = new EditBox(font, cx - 40, y, 80, 20, Component.literal("数量"));
		amount.setFilter(s -> s.isEmpty() || s.matches("\\d{1,4}"));
		amount.setValue(amount.getValue().isEmpty() ? String.valueOf(Math.min(stack.getMaxStackSize(), 16)) : amount.getValue());
		addRenderableWidget(amount);
		setInitialFocus(amount);

		int[] quick = {1, 8, 16, 32, 64};
		int bx = cx - (quick.length * 34) / 2;
		for (int q : quick) {
			addRenderableWidget(Button.builder(Component.literal(String.valueOf(q)), b -> amount.setValue(String.valueOf(q)))
					.bounds(bx, y + 26, 32, 20).build());
			bx += 34;
		}
		modeButton = addRenderableWidget(Button.builder(modeLabel(), b -> {
			mode = (mode + 1) % MODES.length;
			b.setMessage(modeLabel());
		}).bounds(cx - 110, y + 52, 220, 20).build());
		addRenderableWidget(Button.builder(Component.literal("§a发送给机器人"), b -> send())
				.bounds(cx - 110, y + 80, 108, 20).build());
		addRenderableWidget(Button.builder(Component.literal("返回"), b -> minecraft.setScreen(parent))
				.bounds(cx + 2, y + 80, 108, 20).build());
	}

	private Component modeLabel() {
		return Component.literal("模式：" + MODE_NAMES[mode]);
	}

	private void send() {
		int count;
		try {
			count = Integer.parseInt(amount.getValue());
		} catch (NumberFormatException e) {
			return;
		}
		count = Math.max(1, Math.min(MAX, count));
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
		int cx = width / 2, y = height / 2 - 30;
		g.renderItem(stack, cx - 8, y - 46);
		g.drawCenteredString(font, stack.getHoverName(), cx, y - 26, 0xFFFFFFFF);
		g.drawCenteredString(font, "数量（1-" + MAX + "）", cx, y - 12, 0xFFAAAAAA);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
