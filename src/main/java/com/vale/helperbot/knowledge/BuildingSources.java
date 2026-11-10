package com.vale.helperbot.knowledge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What bridges and pillars are built of: the ground itself, by the game's own data. The ground is
 * what the block tags call the stone and dirt of the Overworld and the Nether (other mods' stone in
 * those tags counts too), and the block each dimension's terrain is made of (its generator's default
 * block: stone, netherrack, end stone). What may be laid is what digging those gives with a plain
 * tool or the bare hand (stone gives cobblestone, grass dirt; ores give ore, not a block, and so
 * aren't in it), and the ground blocks themselves; only whole blocks that don't fall and hold
 * nothing (no sand, no chests). A diamond block a player gave isn't ground, so it's never laid.
 *
 * <p>It used to be a hand-written list of nineteen blocks.
 */
public final class BuildingSources {
	private static Set<Item> items;
	/** For each item that may be laid, the ground blocks that give it when dug. */
	private static Map<Item, Set<Block>> sourcesOf;

	private BuildingSources() {
	}

	public static void clearCache() {
		items = null;
		sourcesOf = null;
	}

	/** Items that may be laid. */
	public static Set<Item> items(MinecraftServer server) {
		build(server);
		return items;
	}

	/** What may be laid, and the ground blocks that give each when dug. */
	public static Map<Item, Set<Block>> sources(MinecraftServer server) {
		build(server);
		return sourcesOf;
	}

	private static void build(MinecraftServer server) {
		if (items != null) {
			return;
		}
		Set<Block> g = new HashSet<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			BlockState state = block.defaultBlockState();
			if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.DIRT)) {
				g.add(block);
			}
		}
		for (ServerLevel level : server.getAllLevels()) {
			if (level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noise) {
				g.add(noise.generatorSettings().value().defaultBlock().getBlock());
			}
		}
		ServerLevel overworld = server.overworld();
		Entity breaker = net.fabricmc.fabric.api.entity.FakePlayer.get(overworld);
		Set<Item> laid = new HashSet<>();
		Map<Item, Set<Block>> from = new HashMap<>();
		for (Block block : g) {
			if (layable(block.asItem())) {
				laid.add(block.asItem());
			}
			for (Item item : DropAnalysis.dropsOf(overworld, block)) {
				if (layable(item) && (DropAnalysis.handDrops(overworld, breaker, block, item)
						|| !DropAnalysis.toolItemsFor(overworld, breaker, block, item).isEmpty())) {
					laid.add(item);
					from.computeIfAbsent(item, i -> new HashSet<>()).add(block);
				}
			}
		}
		items = Set.copyOf(laid);
		Map<Item, Set<Block>> frozen = new HashMap<>();
		from.forEach((item, blocks) -> frozen.put(item, Set.copyOf(blocks)));
		sourcesOf = Map.copyOf(frozen);
	}

	/** A whole block that doesn't fall and holds nothing. */
	private static boolean layable(Item item) {
		if (!(item instanceof BlockItem blockItem)) {
			return false;
		}
		BlockState state = blockItem.getBlock().defaultBlockState();
		return !(blockItem.getBlock() instanceof FallingBlock) && !state.hasBlockEntity()
				&& state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
	}
}
