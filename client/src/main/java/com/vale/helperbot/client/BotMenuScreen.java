package com.vale.helperbot.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The first menu: what to tell the bot. "获取物品" opens the item picker, the rest send right away. */
public class BotMenuScreen extends Screen {
	private static final int W = 150, H = 20, GAP = 4;

	public BotMenuScreen() {
		super(Component.literal("机器人助手"));
	}

	@Override
	protected void init() {
		int left = width / 2 - W - GAP / 2, right = width / 2 + GAP / 2;
		int y = height / 2 - 70;
		addRenderableWidget(Button.builder(Component.literal("§l获取物品…"), b -> minecraft.setScreen(new ItemPickerScreen(this)))
				.bounds(width / 2 - W, y, W * 2 + GAP - 4, H).build());
		y += H + GAP * 2;
		button(left, y, "过来（走到我身边）", "bot come");
		button(right, y, "取消当前任务", "bot cancel");
		y += H + GAP;
		button(left, y, "状态", "bot status");
		button(right, y, "最近日志", "bot log");
		y += H + GAP;
		button(left, y, "召唤机器人", "bot spawn");
		button(right, y, "移除机器人", "bot remove");
		y += H + GAP;
		button(left, y, "路线显示 开/关", "bot debug path");
		button(right, y, "查看规则", "bot rules");
		y += H + GAP * 2;
		addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
				.bounds(width / 2 - 50, y, 100, H).build());
	}

	private void button(int x, int y, String label, String command) {
		addRenderableWidget(Button.builder(Component.literal(label), b -> HelperBotClient.send(command)).bounds(x, y, W, H).build());
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawCenteredString(font, title, width / 2, height / 2 - 92, 0xFFFFFFFF);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
