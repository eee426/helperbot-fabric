package com.vale.helperbot.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Protecting chunks from above: a map of the ground around the player (from the chunks the game
 * has loaded), the protected chunks in green, the ones picked in yellow. Touch a chunk to pick it
 * (or unpick), drag to move the map, then "应用" protects all picked at once. Touching a protected
 * chunk shows its number: taking protection off is only done on the server console, by number.
 * Made for a finger as much as a mouse: a touch that moves is a drag, one that doesn't is a pick.
 */
public class ChunkMapScreen extends TouchScreen {
	/** How far around to ask the server about, in chunks. */
	private static final int QUERY_RADIUS = 32;
	/** Movement (screen pixels) under which a touch counts as a tap, not a drag. */
	private static final double TAP_SLOP = 6;
	private static final int[] ZOOMS = {6, 10, 16, 24, 36};
	private static final int UNLOADED = 0xFF2A2A2A;
	/** Chunks sent per /bot protect chunks command. */
	private static final int PER_COMMAND = 20;

	private final Screen parent;
	private final LongLinkedOpenHashSet picked = new LongLinkedOpenHashSet();
	/** Ground colours per chunk, 4x4 samples, worked out once per opening. */
	private final Long2ObjectOpenHashMap<int[]> colours = new Long2ObjectOpenHashMap<>();
	private double viewX, viewZ;
	private int zoom = 2;
	private int mapX0, mapY0, mapX1, mapY1;
	private Button apply;
	private String info = "点区块选中（黄色），再点取消；拖动移动地图。绿色是已保护的。";
	private boolean pressedInMap, dragging;
	private double pressX, pressY, moved;
	private int queriedX, queriedZ;
	private long queriedAt;

	public ChunkMapScreen(Screen parent) {
		super(Component.literal("保护区块"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();
		if (minecraft.player != null && viewX == 0 && viewZ == 0) {
			viewX = minecraft.player.getX() / 16.0;
			viewZ = minecraft.player.getZ() / 16.0;
		}
		int w = panelWidth(460), x = panelLeft(w);
		int rowH = rowHeight(height / 7, 1), h = rowH - GAP;
		mapX0 = x;
		mapX1 = x + w;
		mapY0 = 22;
		mapY1 = height - 6 - 2 * rowH - 4;
		row(x, mapY1 + 4, w, h, new String[]{"缩小", "放大", "回到我这"},
				new Runnable[]{() -> zoom = Math.max(0, zoom - 1), () -> zoom = Math.min(ZOOMS.length - 1, zoom + 1), this::recentre});
		Button[] last = row(x, mapY1 + 4 + rowH, w, h, new String[]{"应用", "清除选择", "返回"},
				new Runnable[]{this::applyPicked, picked::clear, () -> minecraft.setScreen(parent)});
		apply = last[0];
		colours.clear();
		query(true);
	}

	private void recentre() {
		if (minecraft.player != null) {
			viewX = minecraft.player.getX() / 16.0;
			viewZ = minecraft.player.getZ() / 16.0;
		}
	}

	/** Ask the server which chunks around the view are protected (again if the view moved far). */
	private void query(boolean now) {
		int cx = (int) Math.floor(viewX), cz = (int) Math.floor(viewZ);
		boolean far = Math.abs(cx - queriedX) > QUERY_RADIUS / 2 || Math.abs(cz - queriedZ) > QUERY_RADIUS / 2;
		if (now || (far && Util.getMillis() - queriedAt > 1000)) {
			queriedX = cx;
			queriedZ = cz;
			queriedAt = Util.getMillis();
			HelperBotClient.sendQuietly("bot protect query " + cx + " " + cz + " " + QUERY_RADIUS);
		}
	}

	private void applyPicked() {
		if (picked.isEmpty()) {
			return;
		}
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (long c : picked) {
			sb.append(ChunkPos.getX(c)).append(',').append(ChunkPos.getZ(c)).append(' ');
			if (++n % PER_COMMAND == 0) {
				HelperBotClient.sendQuietly("bot protect chunks " + sb.toString().trim());
				sb.setLength(0);
			}
		}
		if (!sb.isEmpty()) {
			HelperBotClient.sendQuietly("bot protect chunks " + sb.toString().trim());
		}
		info = "已发送：保护 " + picked.size() + " 个区块。";
		picked.clear();
		query(true); // the server answers in order: this comes after the protecting
	}

	private int cell() {
		return ZOOMS[zoom];
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		query(false);
		apply.setMessage(Component.literal(picked.isEmpty() ? "应用" : "§a应用（保护 " + picked.size() + " 个）"));
		apply.active = !picked.isEmpty();
		g.drawCenteredString(font, info, width / 2, 8, 0xFFFFFFFF);
		g.fill(mapX0 - 1, mapY0 - 1, mapX1 + 1, mapY1 + 1, 0xFF000000);
		g.enableScissor(mapX0, mapY0, mapX1, mapY1);
		int size = cell();
		double midX = (mapX0 + mapX1) / 2.0, midY = (mapY0 + mapY1) / 2.0;
		int cx0 = (int) Math.floor(viewX - (midX - mapX0) / size) - 1, cx1 = (int) Math.ceil(viewX + (mapX1 - midX) / size) + 1;
		int cz0 = (int) Math.floor(viewZ - (midY - mapY0) / size) - 1, cz1 = (int) Math.ceil(viewZ + (mapY1 - midY) / size) + 1;
		int sub = size >= 24 ? 4 : size >= 10 ? 2 : 1;
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				int sx = (int) Math.round(midX + (cx - viewX) * size), sy = (int) Math.round(midY + (cz - viewZ) * size);
				int[] col = colours(cx, cz);
				int step = size / sub;
				for (int i = 0; i < sub; i++) {
					for (int j = 0; j < sub; j++) {
						int c = col[(i * 4 / sub) * 4 + (j * 4 / sub)];
						g.fill(sx + i * step, sy + j * step, sx + (i + 1) * step, sy + (j + 1) * step, c);
					}
				}
				long key = ChunkPos.asLong(cx, cz);
				if (ClientZones.idOf(cx, cz) != 0) {
					g.fill(sx, sy, sx + size, sy + size, 0x6000FF40);
				}
				if (picked.contains(key)) {
					g.fill(sx, sy, sx + size, sy + size, 0x90FFE020);
				}
				// Chunk grid.
				g.fill(sx, sy, sx + size, sy + 1, 0x30000000);
				g.fill(sx, sy, sx + 1, sy + size, 0x30000000);
			}
		}
		if (minecraft.player != null) {
			int px = (int) Math.round(midX + (minecraft.player.getX() / 16.0 - viewX) * size);
			int py = (int) Math.round(midY + (minecraft.player.getZ() / 16.0 - viewZ) * size);
			g.fill(px - 2, py - 2, px + 3, py + 3, 0xFFFFFFFF);
			g.fill(px - 1, py - 1, px + 2, py + 2, 0xFFFF3030);
		}
		g.disableScissor();
		if (!ClientZones.answered()) {
			g.drawCenteredString(font, "等服务器回复……（服务器要装 Helper Bot 才行）", width / 2, mapY0 + 4, 0xFFFFC060);
		}
	}

	/** 4x4 ground colours of a chunk, from what the game has loaded (dark grey if not loaded). */
	private int[] colours(int cx, int cz) {
		long key = ChunkPos.asLong(cx, cz);
		int[] cached = colours.get(key);
		if (cached != null) {
			return cached;
		}
		int[] out = new int[16];
		ClientLevel level = minecraft.level;
		boolean loaded = level != null && level.getChunkSource().hasChunk(cx, cz);
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				if (!loaded) {
					out[i * 4 + j] = UNLOADED;
					continue;
				}
				int x = cx * 16 + i * 4 + 2, z = cz * 16 + j * 4 + 2;
				int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				BlockPos pos = new BlockPos(x, y, z);
				BlockState state = level.getBlockState(pos);
				MapColor map = state.getMapColor(level, pos);
				out[i * 4 + j] = map == MapColor.NONE ? UNLOADED : 0xFF000000 | map.col;
			}
		}
		if (loaded) {
			colours.put(key, out); // unloaded ones are looked at again (they may load meanwhile)
		}
		return out;
	}

	private boolean inMap(double x, double y) {
		return x >= mapX0 && x < mapX1 && y >= mapY0 && y < mapY1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (guarded()) {
			return true;
		}
		if (inMap(event.x(), event.y())) {
			pressedInMap = true;
			dragging = false;
			moved = 0;
			pressX = event.x();
			pressY = event.y();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (pressedInMap) {
			moved += Math.abs(dx) + Math.abs(dy);
			if (moved > TAP_SLOP) {
				dragging = true;
			}
			if (dragging) {
				viewX -= dx / cell();
				viewZ -= dy / cell();
			}
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (pressedInMap) {
			pressedInMap = false;
			if (!dragging && inMap(pressX, pressY)) {
				tap(pressX, pressY);
			}
			return true;
		}
		return super.mouseReleased(event);
	}

	private void tap(double x, double y) {
		double midX = (mapX0 + mapX1) / 2.0, midY = (mapY0 + mapY1) / 2.0;
		int cx = (int) Math.floor(viewX + (x - midX) / cell());
		int cz = (int) Math.floor(viewZ + (y - midY) / cell());
		int id = ClientZones.idOf(cx, cz);
		if (id != 0) {
			info = "区块 " + cx + ", " + cz + " 已保护，ID " + id + "（取消保护：服务器后台 /bot protect remove " + id + "）";
			return;
		}
		long key = ChunkPos.asLong(cx, cz);
		if (!picked.remove(key)) {
			picked.add(key);
		}
		info = "区块 " + cx + ", " + cz + (picked.contains(key) ? " 已选中" : " 取消选中") + "（共选了 " + picked.size() + " 个）";
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		zoom = Math.max(0, Math.min(ZOOMS.length - 1, zoom + (scrollY > 0 ? 1 : -1)));
		return true;
	}
}
