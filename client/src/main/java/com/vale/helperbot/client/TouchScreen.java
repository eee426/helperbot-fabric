package com.vale.helperbot.client;

import net.minecraft.util.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Screens that work with a finger (FCL and other phone launchers) as well as a mouse:
 * <ul>
 * <li>everything in a panel in the middle, away from the edges, where the launcher's on-screen
 * keys sit;</li>
 * <li>big buttons, and nothing happens just by touching a list: pick, then press a button;</li>
 * <li>taps right after a screen opens are ignored, so the tap that opened it can't also press
 * whatever is now under the finger (it used to jump straight on to "send to the bot?").</li>
 * </ul>
 */
public abstract class TouchScreen extends Screen {
	/** Taps this soon after the screen opened are ignored. */
	private static final long GUARD_MS = 350;
	protected static final int GAP = 6;
	private long openedAt;

	protected TouchScreen(Component title) {
		super(title);
	}

	@Override
	protected void init() {
		openedAt = Util.getMillis();
	}

	/** Side margin: clear of the launcher's on-screen keys. */
	protected int margin() {
		return Math.max(24, width / 10);
	}

	/** Panel width, centred. */
	protected int panelWidth(int max) {
		return Math.min(max, width - 2 * margin());
	}

	protected int panelLeft(int panelWidth) {
		return (width - panelWidth) / 2;
	}

	/** Row height for {@code rows} rows of buttons in {@code space} pixels: big, but it must fit. */
	protected static int rowHeight(int space, int rows) {
		return Math.max(22, Math.min(32, space / Math.max(1, rows)));
	}

	protected Button big(String label, int x, int y, int w, int h, Runnable action) {
		return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, h).build());
	}

	/** {@code labels.length} buttons side by side across {@code w} pixels from {@code x}. */
	protected Button[] row(int x, int y, int w, int h, String[] labels, Runnable[] actions) {
		Button[] out = new Button[labels.length];
		int each = (w - GAP * (labels.length - 1)) / labels.length;
		for (int i = 0; i < labels.length; i++) {
			out[i] = big(labels[i], x + i * (each + GAP), y, each, h, actions[i]);
		}
		return out;
	}

	protected boolean guarded() {
		return Util.getMillis() - openedAt < GUARD_MS;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (guarded()) {
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
