package com.vale.helperbot.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** The first menu: what to tell the bot. "获取物品" and "保护区块" open their own screens, the rest send right away. */
public class BotMenuScreen extends TouchScreen {
	private static final int ROWS = 7;

	public BotMenuScreen() {
		super(Component.literal("机器人助手"));
	}

	@Override
	protected void init() {
		super.init();
		int w = panelWidth(320), x = panelLeft(w);
		int top = 22, rowH = rowHeight(height - top - 8, ROWS), h = rowH - GAP;
		int y = top;
		big("§l获取物品…", x, y, w, h, () -> minecraft.setScreen(new ItemPickerScreen(this)));
		y += rowH;
		big("§l保护区块（地图）…", x, y, w, h, () -> minecraft.setScreen(new ChunkMapScreen(this)));
		y += rowH;
		row(x, y, w, h, new String[]{"过来（到我身边）", "停止当前任务"},
				new Runnable[]{() -> HelperBotClient.send("bot come"), () -> HelperBotClient.send("bot cancel")});
		y += rowH;
		row(x, y, w, h, new String[]{"状态", "最近日志"},
				new Runnable[]{() -> HelperBotClient.send("bot status"), () -> HelperBotClient.send("bot log")});
		y += rowH;
		row(x, y, w, h, new String[]{"召唤机器人", "移除机器人"},
				new Runnable[]{() -> HelperBotClient.send("bot spawn"), () -> HelperBotClient.send("bot remove")});
		y += rowH;
		row(x, y, w, h, new String[]{"路线显示 开/关", "查看规则"},
				new Runnable[]{() -> HelperBotClient.send("bot debug path"), () -> HelperBotClient.send("bot rules")});
		y += rowH;
		big("关闭", x + w / 4, y, w / 2, h, this::onClose);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
	}
}
