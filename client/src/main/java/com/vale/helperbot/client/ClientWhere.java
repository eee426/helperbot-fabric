package com.vale.helperbot.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;

/**
 * Where each item comes from, as the server last told us (the reply to "/bot where"): only the
 * server knows (loot tables, world generation). Items it doesn't name come from the Overworld.
 */
public final class ClientWhere {
	/** Start of the server's reply line (see the server's /bot where). */
	public static final String MARKER = "[helperbot-where]";

	public enum Place {
		OVERWORLD("主世界"), NETHER("地狱"), END("末地"), NONE("拿不到");

		final String label;

		Place(String label) {
			this.label = label;
		}
	}

	private static final Map<Item, Place> places = new HashMap<>();
	/** The ones that need a silk touch tool given to the bot. */
	private static final java.util.Set<Item> silk = new java.util.HashSet<>();
	private static boolean answered;
	/** Goes up with every answer: a screen that's open redraws its lists when it changes. */
	private static int version;

	private ClientWhere() {
	}

	/** Reads a reply line: "[helperbot-where]n=id,id,;e=id,;x=id,;s=id,". */
	static void read(String line) {
		places.clear();
		silk.clear();
		for (String part : line.substring(MARKER.length()).split(";")) {
			int eq = part.indexOf('=');
			if (eq != 1) {
				continue;
			}
			if (part.charAt(0) == 's') {
				for (String id : part.substring(2).split(",")) {
					Identifier key = Identifier.tryParse(id);
					if (key != null) {
						BuiltInRegistries.ITEM.getOptional(key).ifPresent(silk::add);
					}
				}
				continue;
			}
			Place place = switch (part.charAt(0)) {
				case 'n' -> Place.NETHER;
				case 'e' -> Place.END;
				case 'x' -> Place.NONE;
				default -> null;
			};
			if (place == null) {
				continue;
			}
			for (String id : part.substring(2).split(",")) {
				Identifier key = Identifier.tryParse(id);
				if (key != null) {
					BuiltInRegistries.ITEM.getOptional(key).ifPresent(item -> places.put(item, place));
				}
			}
		}
		answered = true;
		version++;
	}

	/** Can be had only with a silk touch tool, which the player has to give the bot. */
	static boolean needsSilk(Item item) {
		return silk.contains(item);
	}

	static Place of(Item item) {
		return places.getOrDefault(item, Place.OVERWORLD);
	}

	/** Has the server answered at all (no answer: it doesn't have the Helper Bot mod, or an old one)? */
	static boolean answered() {
		return answered;
	}

	static int version() {
		return version;
	}

	/** A new world or server: what the last one said doesn't hold. */
	static void forget() {
		places.clear();
		silk.clear();
		answered = false;
		version++;
	}
}
