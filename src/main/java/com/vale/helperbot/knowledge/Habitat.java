package com.vale.helperbot.knowledge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.function.Predicate;

/**
 * Which layer of the world something lives in, and where "over there" is in 3D. A biome is a
 * column: plains on the surface can be solid rock 40 blocks down, where no sheep will ever be.
 * Everything that sends the bot somewhere far goes through here, so it heads for the right
 * height, not just the right x/z (it used to keep its own height: from a cave it walked, and
 * waited for sheep, underneath the plains).
 *
 * <p>All from game data: mob categories and spawn placement rules, and the generation step of
 * the world features that place a block (see {@link BiomeHints#layerFor}).
 */
public final class Habitat {
	private Habitat() {
	}

	public enum Layer {
		/** Under the open sky: animals (they need grass and daylight), trees, flowers, lakes. */
		SURFACE,
		/** In the rock: ores, cave growth. */
		UNDERGROUND,
		/** Anywhere (monsters spawn wherever it's dark, caves included), or not known. */
		ANY
	}

	/** Animals and anything spawning on the ground in daylight: the surface. Monsters: anywhere dark. */
	public static Layer of(EntityType<?> type) {
		MobCategory category = type.getCategory();
		if (category == MobCategory.MONSTER || category == MobCategory.UNDERGROUND_WATER_CREATURE
				|| category == MobCategory.AXOLOTLS || category == MobCategory.AMBIENT) {
			return Layer.ANY;
		}
		if (SpawnPlacements.getPlacementType(type) == SpawnPlacementTypes.ON_GROUND
				|| category == MobCategory.WATER_CREATURE || category == MobCategory.WATER_AMBIENT) {
			return Layer.SURFACE; // ground animals; fish and squid live in surface waters too
		}
		return Layer.ANY;
	}

	/** The surface layer if any of these mobs lives there and none lives anywhere else. */
	public static Layer ofAll(Iterable<EntityType<?>> types) {
		Layer result = null;
		for (EntityType<?> type : types) {
			Layer l = of(type);
			if (result == null) {
				result = l;
			} else if (result != l) {
				return Layer.ANY;
			}
		}
		return result == null ? Layer.ANY : result;
	}

	/** Under a roof (see Worlds#hasCeiling) there's no surface: what lives on top of things there lives anywhere. */
	public static Layer in(ServerLevel level, Layer layer) {
		return layer == Layer.SURFACE && com.vale.helperbot.path.Worlds.hasCeiling(level) ? Layer.ANY : layer;
	}

	/** Where to head for {@code xz}: on the surface there for surface things, else at the bot's own height. */
	public static BlockPos target(ServerLevel level, BlockPos xz, Layer layer, BlockPos bot) {
		return in(level, layer) == Layer.SURFACE ? new BlockPos(xz.getX(), com.vale.helperbot.path.Worlds.surfaceY(level, xz.getX(), xz.getZ()), xz.getZ())
				: xz.atY(bot.getY());
	}

	/** The height of the target is known (surface): plan in 3D, not just towards the x/z. */
	public static boolean heightKnown(Layer layer) {
		return layer == Layer.SURFACE;
	}

	/** Arrived: within {@code radius} of the x/z, and for surface things, out under the sky. */
	public static com.vale.helperbot.path.PathGoal arrived(BlockPos xz, int radius, Layer layer) {
		long r2 = (long) radius * radius;
		return (w, p) -> {
			long dx = p.getX() - xz.getX(), dz = p.getZ() - xz.getZ();
			return dx * dx + dz * dz <= r2
					&& (layer != Layer.SURFACE || com.vale.helperbot.path.Worlds.hasCeiling(w) || p.getY() >= com.vale.helperbot.path.Worlds.surfaceY(w, p.getX(), p.getZ()) - 1);
		};
	}

	/** Out in the open: nothing but sky over the head (a tree's leaves don't count as a roof). */
	public static boolean onSurface(ServerLevel level, BlockPos feet) {
		return com.vale.helperbot.path.Worlds.hasCeiling(level) || feet.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()) - 1;
	}
}
