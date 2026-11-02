package com.vale.helperbot.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * The protected chunks around the player, as the server last told us (the reply to
 * "/bot protect query"), with their numbers. Only for drawing the chunk map.
 */
public final class ClientZones {
	/** Start of the server's reply line (see the server's /bot protect query). */
	public static final String MARKER = "[helperbot-zones]";
	private static final Long2IntOpenHashMap zones = new Long2IntOpenHashMap();
	private static boolean answered;

	private ClientZones() {
	}

	/** Reads a reply line: "[helperbot-zones]id,x,z;id,x,z;...". */
	static void read(String line) {
		zones.clear();
		for (String part : line.substring(MARKER.length()).split(";")) {
			String[] v = part.split(",");
			if (v.length == 3) {
				try {
					zones.put(ChunkPos.asLong(Integer.parseInt(v[1]), Integer.parseInt(v[2])), Integer.parseInt(v[0]));
				} catch (NumberFormatException ignored) {
					// not ours after all
				}
			}
		}
		answered = true;
	}

	/** The chunk's number, or 0 if it isn't protected (as far as we know). */
	static int idOf(int x, int z) {
		return zones.get(ChunkPos.asLong(x, z));
	}

	/** Has the server answered at all (no answer: it doesn't have the Helper Bot mod, or an old one)? */
	static boolean answered() {
		return answered;
	}

	static void forget() {
		zones.clear();
		answered = false;
	}
}
