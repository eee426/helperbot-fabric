package com.vale.helperbot.knowledge;

import com.vale.helperbot.HelperBot;
import com.vale.helperbot.mixin.AxeItemAccessor;
import com.vale.helperbot.mixin.ConcretePowderBlockAccessor;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What using something on a block or a mob makes, from the game itself: the axe's table of logs
 * it strips, what each concrete powder turns into in water, the bucket of each fluid and of each
 * block a bucket picks up, and what a mob gives when a bucket, a water bucket or a bowl is used
 * on it (asked of the game: a mob made for the asking, never put in the world, and a fake player
 * using the item on it). Other mods' logs, powders, fluids and mobs count the same.
 *
 * <p>Two are written in the game's code and nowhere else, so they're named here: a glass bottle
 * filled at water, and an empty map used.
 */
public final class Interactions {
	public enum Kind {
		/** Put the block down, use an axe on it, break it back up. */
		STRIP,
		/** Put the powder down, pour water on it, take the water back, break it up. */
		SOLIDIFY,
		/** Use the container on one of {@code at} (a lava source, powder snow, water). */
		SCOOP,
		/** Use the held item on one of {@code mobs}. */
		MOB,
		/** Use it in the hand. */
		USE
	}

	/**
	 * One way to {@code result}: one {@code input} used up for each. {@code placed}/{@code becomes}:
	 * the block put down and what it turns into (STRIP, SOLIDIFY); {@code at}: the blocks it's used
	 * on (SCOOP); {@code mobs}: the mobs it's used on (MOB).
	 */
	public record Way(Kind kind, Item result, Item input, Block placed, Block becomes, Set<Block> at, Set<EntityType<?>> mobs) {
		@Override
		public String toString() {
			return kind + " " + BuiltInRegistries.ITEM.getKey(input).getPath() + "->" + BuiltInRegistries.ITEM.getKey(result).getPath();
		}
	}

	/** What's tried on every mob. */
	private static final List<Item> HELD = List.of(Items.BUCKET, Items.WATER_BUCKET, Items.BOWL, Items.GLASS_BOTTLE);

	private static Map<Item, List<Way>> ways;

	private Interactions() {
	}

	public static void clearCache() {
		ways = null;
	}

	public static void warmUp(ServerLevel level) {
		build(level);
	}

	public static List<Way> waysTo(ServerLevel level, Item item) {
		build(level);
		return ways.getOrDefault(item, List.of());
	}

	private static void build(ServerLevel level) {
		if (ways != null) {
			return;
		}
		long start = System.nanoTime();
		Map<Item, List<Way>> built = new HashMap<>();
		// Stripping: the axe's own table (mods add to it through Fabric, which writes into it).
		for (Map.Entry<Block, Block> e : AxeItemAccessor.helperbot$strippables().entrySet()) {
			add(built, new Way(Kind.STRIP, e.getValue().asItem(), e.getKey().asItem(), e.getKey(), e.getValue(), Set.of(), Set.of()));
		}
		for (Block block : BuiltInRegistries.BLOCK) {
			if (block instanceof ConcretePowderBlock powder) {
				Block concrete = ((ConcretePowderBlockAccessor) powder).helperbot$concrete();
				add(built, new Way(Kind.SOLIDIFY, concrete.asItem(), block.asItem(), block, concrete, Set.of(), Set.of()));
			}
		}
		// Buckets: of every fluid's source (water has its own step, see CraftPlanner.Fill), and of
		// every block a bucket picks up as a block (powder snow).
		for (Fluid fluid : BuiltInRegistries.FLUID) {
			Item bucket = fluid.getBucket();
			if (bucket == Items.AIR || bucket == Items.WATER_BUCKET || !fluid.isSource(fluid.defaultFluidState())) {
				continue;
			}
			Block source = fluid.defaultFluidState().createLegacyBlock().getBlock();
			if (source != Blocks.AIR) {
				add(built, new Way(Kind.SCOOP, bucket, Items.BUCKET, null, null, Set.of(source), Set.of()));
			}
		}
		for (Item item : BuiltInRegistries.ITEM) {
			if (item instanceof SolidBucketItem solid) {
				add(built, new Way(Kind.SCOOP, item, Items.BUCKET, null, null, Set.of(solid.getBlock()), Set.of()));
			}
		}
		// Only in the game's code (GlassBottleItem, EmptyMapItem).
		add(built, new Way(Kind.SCOOP, Items.POTION, Items.GLASS_BOTTLE, null, null, Set.of(Blocks.WATER), Set.of()));
		add(built, new Way(Kind.USE, Items.FILLED_MAP, Items.MAP, null, null, Set.of(), Set.of()));
		askMobs(level, built);
		ways = built;
		HelperBot.LOGGER.info("HelperBot interaction index: {} items, {} ms", built.size(), (System.nanoTime() - start) / 1_000_000);
	}

	/** Every mob that lives in the world, asked what it gives for each of {@link #HELD}. */
	private static void askMobs(ServerLevel level, Map<Item, List<Way>> built) {
		FakePlayer player = FakePlayer.get(level);
		Map<Item, Map<Item, Set<EntityType<?>>>> given = new HashMap<>();
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			MobCategory category = type.getCategory();
			if (category == MobCategory.MISC || category == MobCategory.MONSTER) {
				continue;
			}
			for (Item held : HELD) {
				Entity entity;
				try {
					entity = type.create(level, EntitySpawnReason.LOAD);
				} catch (RuntimeException e) {
					break;
				}
				if (!(entity instanceof Mob mob)) {
					break;
				}
				// Far under the world, where nothing hears the sound it makes.
				mob.setPos(0, level.getMinY() - 64, 0);
				player.setPos(0, level.getMinY() - 64, 0);
				player.getInventory().clearContent();
				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(held));
				try {
					mob.interact(player, InteractionHand.MAIN_HAND);
				} catch (RuntimeException e) {
					continue; // a mob that can't be asked this way isn't worth failing the rest over
				}
				Set<Item> got = new HashSet<>();
				ItemStack hand = player.getMainHandItem();
				if (!hand.isEmpty() && !hand.is(held)) {
					got.add(hand.getItem());
				}
				for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
					if (!stack.isEmpty() && !stack.is(held)) {
						got.add(stack.getItem());
					}
				}
				for (Item item : got) {
					given.computeIfAbsent(item, i -> new HashMap<>()).computeIfAbsent(held, h -> new HashSet<>()).add(type);
				}
				mob.discard();
			}
		}
		player.getInventory().clearContent();
		for (Map.Entry<Item, Map<Item, Set<EntityType<?>>>> e : given.entrySet()) {
			for (Map.Entry<Item, Set<EntityType<?>>> h : e.getValue().entrySet()) {
				add(built, new Way(Kind.MOB, e.getKey(), h.getKey(), null, null, Set.of(), Set.copyOf(h.getValue())));
			}
		}
	}

	private static void add(Map<Item, List<Way>> built, Way way) {
		if (way.result() == Items.AIR || way.input() == Items.AIR) {
			return;
		}
		built.computeIfAbsent(way.result(), i -> new ArrayList<>()).add(way);
	}
}
