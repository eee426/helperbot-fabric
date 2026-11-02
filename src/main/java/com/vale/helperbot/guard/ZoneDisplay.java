package com.vale.helperbot.guard;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Shows a player where the protected chunks around them end, with particles along the outer
 * edges, for a while: no client mod needed. Only the player who asked sees them.
 */
public final class ZoneDisplay {
	private static final int SHOW_TICKS = 20 * 15;
	private static final int REFRESH_TICKS = 10;
	/** Chunks around the player looked at. */
	private static final int RADIUS = 6;
	private static final Map<UUID, Integer> showing = new HashMap<>();

	private ZoneDisplay() {
	}

	public static void show(ServerPlayer player) {
		showing.put(player.getUUID(), player.level().getServer().getTickCount() + SHOW_TICKS);
	}

	/** Every server tick. */
	public static void tick(MinecraftServer server) {
		if (showing.isEmpty() || server.getTickCount() % REFRESH_TICKS != 0) {
			return;
		}
		for (Iterator<Map.Entry<UUID, Integer>> it = showing.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Integer> e = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player == null || server.getTickCount() > e.getValue()) {
				it.remove();
				continue;
			}
			draw(player);
		}
	}

	private static void draw(ServerPlayer player) {
		ServerLevel level = player.level();
		ChunkPos here = player.chunkPosition();
		double y0 = player.getY() + 0.2, y1 = player.getY() + 1.6;
		for (int cx = here.x - RADIUS; cx <= here.x + RADIUS; cx++) {
			for (int cz = here.z - RADIUS; cz <= here.z + RADIUS; cz++) {
				ChunkPos c = new ChunkPos(cx, cz);
				if (!ProtectionZones.isProtected(level, c)) {
					continue;
				}
				int x0 = c.getMinBlockX(), z0 = c.getMinBlockZ();
				// Only the outer edges: between two protected chunks there's no border to show.
				if (!ProtectionZones.isProtected(level, new ChunkPos(cx, cz - 1))) {
					line(player, x0, z0, 1, 0, y0, y1);
				}
				if (!ProtectionZones.isProtected(level, new ChunkPos(cx, cz + 1))) {
					line(player, x0, z0 + 16, 1, 0, y0, y1);
				}
				if (!ProtectionZones.isProtected(level, new ChunkPos(cx - 1, cz))) {
					line(player, x0, z0, 0, 1, y0, y1);
				}
				if (!ProtectionZones.isProtected(level, new ChunkPos(cx + 1, cz))) {
					line(player, x0 + 16, z0, 0, 1, y0, y1);
				}
			}
		}
	}

	private static void line(ServerPlayer player, int x, int z, int dx, int dz, double y0, double y1) {
		for (int i = 0; i <= 16; i += 2) {
			for (double y : new double[]{y0, y1}) {
				player.level().sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, true,
						x + dx * i, y, z + dz * i, 1, 0, 0, 0, 0);
			}
		}
	}
}
