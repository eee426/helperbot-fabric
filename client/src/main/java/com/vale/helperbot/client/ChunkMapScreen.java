package com.vale.helperbot.client;

import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
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
	/** Screen pixels per chunk: from a wide view down to 16 pixels a block. */
	private static final int[] ZOOMS = {8, 16, 32, 48, 64, 96, 128, 192, 256};
	/** From this many screen pixels a block, each block is drawn with its own texture. */
	private static final int TEXTURED_FROM = 8;
	private static final int UNLOADED = 0xFF2A2A2A;
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("helperbotclient", "chunk_map");
	/** Chunks sent per /bot protect chunks command. */
	private static final int PER_COMMAND = 20;

	private final Screen parent;
	private final LongLinkedOpenHashSet picked = new LongLinkedOpenHashSet();
	/**
	 * The ground, one pixel a block, drawn once when the map opens into a texture: chunks
	 * {@code imageX..imageX+imageChunks-1} (and z likewise). Scaled up on screen, so every block
	 * shows (it used to be 4x4 samples a chunk, a few blurry dots).
	 */
	private DynamicTexture image;
	private int imageX, imageZ, imageChunks;
	/**
	 * Close up, what each column looks like from above: the top block's own texture (a chest
	 * reads as a chest, not as "brown" like on the vanilla map). Worked out when first drawn.
	 */
	private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Column> columns = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
	private final RandomSource random = RandomSource.create();

	/**
	 * One column seen from above. {@code under}: drawn first when the top block doesn't fill its
	 * cell (a torch, a carpet, a pressure plate on the floor). {@code item}: for blocks drawn by a
	 * special renderer (chests, signs, beds...), whose model has nothing to show. {@code shade}:
	 * lighter or darker by the height step, like the vanilla map.
	 */
	private record Column(TextureAtlasSprite top, int topTint, TextureAtlasSprite under, int underTint, ItemStack item, int shade) {
	}
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
		if (image == null) {
			drawGround();
		}
		query(true);
	}

	/**
	 * The loaded ground around the player into {@link #image}: each block's map colour (what the
	 * vanilla map would show), lighter or darker by the height step to the block north of it, deeper
	 * water darker, like the vanilla map. Unloaded chunks dark grey.
	 */
	private void drawGround() {
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null) {
			return;
		}
		int radius = Math.max(4, Math.min(16, minecraft.options.getEffectiveRenderDistance()));
		ChunkPos centre = minecraft.player.chunkPosition();
		imageChunks = radius * 2 + 1;
		imageX = centre.x - radius;
		imageZ = centre.z - radius;
		int size = imageChunks * 16;
		NativeImage pixels = new NativeImage(size, size, false);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int cx = 0; cx < imageChunks; cx++) {
			for (int cz = 0; cz < imageChunks; cz++) {
				boolean loaded = level.getChunkSource().hasChunk(imageX + cx, imageZ + cz);
				for (int bx = 0; bx < 16; bx++) {
					for (int bz = 0; bz < 16; bz++) {
						int px = cx * 16 + bx, pz = cz * 16 + bz;
						pixels.setPixel(px, pz, loaded ? groundColour(level, pos, (imageX + cx) * 16 + bx, (imageZ + cz) * 16 + bz) : UNLOADED);
					}
				}
			}
		}
		image = new DynamicTexture(() -> "helperbot chunk map", pixels);
		minecraft.getTextureManager().register(TEXTURE, image);
	}

	private static int groundColour(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int z) {
		int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		pos.set(x, y, z);
		BlockState state = level.getBlockState(pos);
		MapColor colour = state.getMapColor(level, pos);
		if (colour == MapColor.NONE) {
			return UNLOADED;
		}
		MapColor.Brightness shade;
		if (colour == MapColor.WATER) {
			// Deeper water darker.
			int depth = 0;
			while (depth < 10 && level.getFluidState(pos.set(x, y - depth - 1, z)).isSource()) {
				depth++;
			}
			shade = depth < 3 ? MapColor.Brightness.HIGH : depth < 6 ? MapColor.Brightness.NORMAL : MapColor.Brightness.LOW;
		} else {
			int north = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - 1) - 1;
			shade = y > north ? MapColor.Brightness.HIGH : y < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
		}
		return colour.calculateARGBColor(shade);
	}

	@Override
	public void removed() {
		if (image != null) {
			minecraft.getTextureManager().release(TEXTURE);
			image = null;
		}
		super.removed();
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
		g.fill(mapX0, mapY0, mapX1, mapY1, UNLOADED);
		int blockPx = size / 16;
		if (blockPx >= TEXTURED_FROM) {
			drawBlocks(g, size, midX, midY);
		} else if (image != null) {
			// The whole ground image, scaled; the scissor keeps it inside the map.
			int ix0 = (int) Math.round(midX + (imageX - viewX) * size), iz0 = (int) Math.round(midY + (imageZ - viewZ) * size);
			g.blit(TEXTURE, ix0, iz0, ix0 + imageChunks * size, iz0 + imageChunks * size, 0f, 1f, 0f, 1f);
		}
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				int sx = (int) Math.round(midX + (cx - viewX) * size), sy = (int) Math.round(midY + (cz - viewZ) * size);
				// Light tints and a border, so what's in the chunk still shows through.
				if (ClientZones.idOf(cx, cz) != 0) {
					g.fill(sx, sy, sx + size, sy + size, 0x3000FF40);
					border(g, sx, sy, size, 1, 0xFF20E040);
				}
				if (picked.contains(ChunkPos.asLong(cx, cz))) {
					g.fill(sx, sy, sx + size, sy + size, 0x40FFE020);
					border(g, sx, sy, size, 2, 0xFFFFE020);
				}
				// Chunk grid.
				g.fill(sx, sy, sx + size, sy + 1, 0x50000000);
				g.fill(sx, sy, sx + 1, sy + size, 0x50000000);
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

	/** Close up: every block in view with its own texture, seen from above. */
	private void drawBlocks(GuiGraphics g, int chunkPx, double midX, double midY) {
		int px = chunkPx / 16;
		double bx = viewX * 16, bz = viewZ * 16;
		int x0 = (int) Math.floor(bx - (midX - mapX0) / px) - 1, x1 = (int) Math.ceil(bx + (mapX1 - midX) / px) + 1;
		int z0 = (int) Math.floor(bz - (midY - mapY0) / px) - 1, z1 = (int) Math.ceil(bz + (mapY1 - midY) / px) + 1;
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				Column c = column(x, z);
				if (c == null) {
					continue;
				}
				int sx = (int) Math.round(midX + (x - bx) * px), sy = (int) Math.round(midY + (z - bz) * px);
				if (c.under() != null) {
					g.blitSprite(RenderPipelines.GUI_TEXTURED, c.under(), sx, sy, px, px, c.underTint());
				}
				if (c.top() != null) {
					g.blitSprite(RenderPipelines.GUI_TEXTURED, c.top(), sx, sy, px, px, c.topTint());
				}
				if (!c.item().isEmpty()) {
					g.pose().pushMatrix();
					g.pose().translate(sx, sy);
					g.pose().scale(px / 16f, px / 16f);
					g.renderItem(c.item(), 0, 0);
					g.pose().popMatrix();
				}
				if (c.shade() != 0) {
					g.fill(sx, sy, sx + px, sy + px, c.shade());
				}
			}
		}
	}

	/** What column (x, z) looks like from above, or null if its chunk isn't loaded. */
	private Column column(int x, int z) {
		long key = BlockPos.asLong(x, 0, z);
		Column cached = columns.get(key);
		if (cached != null) {
			return cached;
		}
		ClientLevel level = minecraft.level;
		if (level == null || !level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
			return null;
		}
		int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		BlockPos pos = new BlockPos(x, y, z);
		BlockState state = level.getBlockState(pos);
		TextureAtlasSprite top = null, under = null;
		int topTint = -1, underTint = -1;
		ItemStack item = ItemStack.EMPTY;
		TintedSprite face = topFace(level, state, pos);
		if (face == null) {
			// Drawn by a special renderer (chest, sign, bed...): show it as its item.
			item = new ItemStack(state.getBlock().asItem());
		} else {
			top = face.sprite();
			topTint = face.tint();
		}
		if (face == null || !state.isCollisionShapeFullBlock(level, pos)) {
			// Doesn't fill its cell (torch, carpet, plate, a chest's corners): the floor shows round it.
			BlockPos below = pos.below();
			TintedSprite floor = topFace(level, level.getBlockState(below), below);
			if (floor != null) {
				under = floor.sprite();
				underTint = floor.tint();
			}
		}
		int north = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - 1) - 1;
		int shade = y > north ? 0x28FFFFFF : y < north ? 0x40000000 : 0;
		Column c = new Column(top, topTint, under, underTint, item, shade);
		columns.put(key, c);
		return c;
	}

	private record TintedSprite(TextureAtlasSprite sprite, int tint) {
	}

	/**
	 * The texture on top of this block, coloured the way it's drawn in the world (grass and leaves
	 * by biome). Its upward face; for plants and the like, which have no such face, whatever the
	 * model has. Null if the model is empty (drawn by a special renderer, or air).
	 */
	private TintedSprite topFace(ClientLevel level, BlockState state, BlockPos pos) {
		if (state.isAir()) {
			return null;
		}
		random.setSeed(state.getSeed(pos));
		BakedQuad quad = null;
		for (BlockModelPart part : minecraft.getBlockRenderer().getBlockModel(state).collectParts(random)) {
			for (Direction d : new Direction[]{Direction.UP, null}) {
				var quads = part.getQuads(d);
				if (!quads.isEmpty()) {
					quad = quads.getFirst();
					break;
				}
			}
			if (quad != null) {
				break;
			}
		}
		if (quad == null) {
			return state.getFluidState().isEmpty() ? null
					: new TintedSprite(minecraft.getBlockRenderer().getBlockModel(state).particleIcon(), tint(level, state, pos, 0));
		}
		return new TintedSprite(quad.sprite(), quad.isTinted() ? tint(level, state, pos, quad.tintIndex()) : -1);
	}

	private int tint(ClientLevel level, BlockState state, BlockPos pos, int index) {
		return 0xFF000000 | minecraft.getBlockColors().getColor(state, level, pos, index);
	}

	private static void border(GuiGraphics g, int x, int y, int size, int t, int colour) {
		g.fill(x, y, x + size, y + t, colour);
		g.fill(x, y + size - t, x + size, y + size, colour);
		g.fill(x, y, x + t, y + size, colour);
		g.fill(x + size - t, y, x + size, y + size, colour);
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
