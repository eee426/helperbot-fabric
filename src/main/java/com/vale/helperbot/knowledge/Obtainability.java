package com.vale.helperbot.knowledge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

import java.util.Set;

/**
 * Items that must be refused before any searching starts, because no survival player can get
 * them by any means. Everything else is left to {@link DropAnalysis}, which works it out from the
 * loot tables; this list only covers what that can't tell (e.g. a block that drops itself but is
 * never generated in a survival world).
 */
public final class Obtainability {
	/** Creative / operator-only items, and blocks that only exist via commands or world-gen-free sources. */
	private static final Set<Item> CREATIVE_ONLY = Set.of(
			Items.COMMAND_BLOCK, Items.CHAIN_COMMAND_BLOCK, Items.REPEATING_COMMAND_BLOCK, Items.COMMAND_BLOCK_MINECART,
			Items.STRUCTURE_BLOCK, Items.STRUCTURE_VOID, Items.JIGSAW, Items.BARRIER, Items.LIGHT,
			Items.DEBUG_STICK, Items.KNOWLEDGE_BOOK, Items.TEST_BLOCK, Items.TEST_INSTANCE_BLOCK,
			Items.PETRIFIED_OAK_SLAB, Items.PLAYER_HEAD,
			Items.BEDROCK, Items.END_PORTAL_FRAME, Items.REINFORCED_DEEPSLATE, Items.BUDDING_AMETHYST,
			Items.FROGSPAWN, Items.SPAWNER, Items.TRIAL_SPAWNER, Items.VAULT);

	private Obtainability() {
	}

	/** Why this item can't be gathered at all, or null if it might be. */
	public static String refusal(ServerLevel level, Item item) {
		if (item == Items.AIR || new ItemStack(item).isEmpty()) {
			return "\"空气\"不是能拿到的东西。";
		}
		if (!item.isEnabled(level.enabledFeatures())) {
			return "这个物品在当前世界里没有启用（实验性内容）。";
		}
		if (item instanceof SpawnEggItem) {
			return "刷怪蛋生存模式里拿不到。";
		}
		if (CREATIVE_ONLY.contains(item)) {
			return "这个东西生存模式里拿不到（只能用创造模式或指令获得）。";
		}
		return null;
	}
}
