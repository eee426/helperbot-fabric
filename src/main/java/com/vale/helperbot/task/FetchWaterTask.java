package com.vale.helperbot.task;

import com.mojang.datafixers.util.Pair;
import com.vale.helperbot.HelperBot;
import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.bot.MovementController;
import com.vale.helperbot.guard.ProtectionZones;
import com.vale.helperbot.path.Mode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Fill one of the empty buckets the player gave the bot with water and bring it back.
 * Searches nearby first; if there is no water around, finds the nearest water biome (rivers,
 * oceans, swamps) up to {@link #MAX_DISTANCE} blocks away and walks there.
 */
public class FetchWaterTask implements BotTask {
	public static final int MAX_DISTANCE = 10_000;
	private static final int LOCAL_RADIUS = 48;
	private static final int TRAVEL_SCAN_RADIUS = 32;
	private static final int TRAVEL_SCAN_INTERVAL = 100;
	private static final int MAX_TARGET_TRIES = 8;
	private static final int MAX_TRAVEL_FAILURES = 4;

	private enum Phase {
		FIND, LOCATE, TRAVEL, GOTO, FILL, RETURN
	}

	private final ServerPlayer requester;
	private final Mode mode;
	private final Set<BlockPos> rejected = new HashSet<>();
	private final ComeTask returnLeg;
	private Phase phase = Phase.FIND;
	private BlockPos water;
	private BlockPos biomeTarget;
	private CompletableFuture<Pair<BlockPos, Holder<Biome>>> locating;
	private int travelTicks;
	private int travelFailures;

	/** Part of a bigger plan (see AcquireTask): done once filled, no walking back, no messages of its own. */
	private final boolean asStep;

	public FetchWaterTask(ServerPlayer requester, Mode mode) {
		this(requester, mode, false);
	}

	FetchWaterTask(ServerPlayer requester, Mode mode, boolean asStep) {
		this.requester = requester;
		this.mode = mode;
		this.asStep = asStep;
		this.returnLeg = new ComeTask(requester, mode);
	}

	@Override
	public Status tick(BotPlayer bot) {
		return switch (phase) {
			case FIND -> find(bot);
			case LOCATE -> locate(bot);
			case TRAVEL -> travel(bot);
			case GOTO -> approach(bot);
			case FILL -> fill(bot);
			case RETURN -> {
				if (requester.hasDisconnected()) {
					yield Status.FAILED;
				}
				yield ComeTask.followTick(bot, requester, mode, returnLeg);
			}
		};
	}

	private Status find(BotPlayer bot) {
		if (bot.getInventory().findSlotMatchingItem(Items.BUCKET.getDefaultInstance()) < 0) {
			bot.say(requester, "我背包里没有空桶。请右键我，放一个空桶进来。");
			return Status.FAILED;
		}
		if (rejected.size() >= MAX_TARGET_TRIES) {
			bot.say(requester, "试了 " + MAX_TARGET_TRIES + " 处水源都够不着，放弃。");
			return Status.FAILED;
		}
		water = nearestWater(bot, LOCAL_RADIUS, 16);
		if (water != null) {
			startApproach(bot);
			return Status.RUNNING;
		}
		bot.say(requester, "附近 " + LOCAL_RADIUS + " 格没有水，去找 " + MAX_DISTANCE + " 格内最近的河流、海洋或沼泽……");
		ServerLevel level = bot.level();
		BlockPos origin = bot.blockPosition();
		// The biome lookup samples world-gen noise only (no chunk loading), so it can run off-thread.
		locating = CompletableFuture.supplyAsync(() -> level.findClosestBiome3d(FetchWaterTask::isWaterBiome,
				origin, MAX_DISTANCE, 64, 64));
		phase = Phase.LOCATE;
		return Status.RUNNING;
	}

	private static boolean isWaterBiome(Holder<Biome> biome) {
		return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)
				|| biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP);
	}

	private Status locate(BotPlayer bot) {
		if (!locating.isDone()) {
			return Status.RUNNING;
		}
		Pair<BlockPos, Holder<Biome>> found;
		try {
			found = locating.join();
		} catch (RuntimeException e) {
			HelperBot.LOGGER.error("Biome search failed", e);
			bot.say(requester, "搜索水域群系时出错了，看看服务器日志。");
			return Status.FAILED;
		}
		if (found == null) {
			bot.say(requester, MAX_DISTANCE + " 格内没有任何水域群系，放弃。");
			return Status.FAILED;
		}
		biomeTarget = found.getFirst();
		int distance = (int) Math.sqrt(bot.blockPosition().distSqr(biomeTarget.atY(bot.getBlockY())));
		String name = found.getSecond().unwrapKey().map(k -> k.identifier().getPath()).orElse("水域");
		bot.say(requester, "最近的水域是 " + biomeTarget.getX() + ", " + biomeTarget.getZ() + " 的 " + name
				+ "，约 " + distance + " 格，出发。");
		startTravel(bot);
		return Status.RUNNING;
	}

	private void startTravel(BotPlayer bot) {
		BlockPos t = biomeTarget;
		// Rivers and oceans are at the surface: head for its height there (worked out from world
		// generation if the chunk isn't loaded), and be up under the sky on arrival, not under it.
		ServerLevel level = bot.level();
		var surface = com.vale.helperbot.knowledge.Habitat.Layer.SURFACE;
		bot.movement().goTo(com.vale.helperbot.knowledge.Habitat.arrived(t, 16, surface),
				com.vale.helperbot.knowledge.Habitat.target(level, t, surface, bot.blockPosition()), mode, false);
		travelTicks = 0;
		phase = Phase.TRAVEL;
	}

	private Status travel(BotPlayer bot) {
		if (++travelTicks % TRAVEL_SCAN_INTERVAL == 0) {
			water = nearestWater(bot, TRAVEL_SCAN_RADIUS, 12);
			if (water != null) {
				bot.say(requester, "路上发现水源 " + water.toShortString() + "。");
				startApproach(bot);
				return Status.RUNNING;
			}
		}
		MovementController.Status status = bot.movement().status();
		if (status == MovementController.Status.ARRIVED) {
			water = nearestWater(bot, LOCAL_RADIUS, 24);
			if (water == null) {
				bot.say(requester, "到了水域群系，但附近没找到能用桶装的水面。");
				return Status.FAILED;
			}
			startApproach(bot);
		} else if (status == MovementController.Status.FAILED || status == MovementController.Status.IDLE) {
			if (++travelFailures > MAX_TRAVEL_FAILURES) {
				bot.say(requester, "去水域的路上多次受阻（" + bot.movement().failReason() + "），放弃。");
				return Status.FAILED;
			}
			bot.say(requester, "路上受阻：" + bot.movement().failReason() + "。重新出发。");
			startTravel(bot);
		}
		return Status.RUNNING;
	}

	private void startApproach(BotPlayer bot) {
		BlockPos w = water;
		// Stand within reach of the source, at or slightly above its level.
		bot.movement().goTo((world, p) -> Math.abs(p.getX() - w.getX()) <= 2 && Math.abs(p.getZ() - w.getZ()) <= 2
				&& p.getY() >= w.getY() && p.getY() <= w.getY() + 2 && !p.equals(w), w, mode);
		phase = Phase.GOTO;
	}

	private Status approach(BotPlayer bot) {
		MovementController.Status status = bot.movement().status();
		if (status == MovementController.Status.ARRIVED) {
			phase = Phase.FILL;
		} else if (status == MovementController.Status.FAILED || status == MovementController.Status.IDLE) {
			bot.say(requester, "去 " + water.toShortString() + " 的路走不通：" + bot.movement().failReason() + "。换一处水源。");
			rejected.add(water);
			phase = Phase.FIND;
		}
		return Status.RUNNING;
	}

	private Status fill(BotPlayer bot) {
		ServerLevel level = bot.level();
		Inventory inv = bot.getInventory();
		Vec3 aim = new Vec3(water.getX() + 0.5, water.getY() + 0.5, water.getZ() + 0.5);
		bot.movement().lookAt(aim);

		Vec3 eye = bot.getEyePosition();
		Vec3 end = eye.add(aim.subtract(eye).normalize().scale(bot.blockInteractionRange()));
		BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, bot));
		// Any water source the bucket would hit is fine (e.g. the one the bot is floating in).
		boolean hitsWater = hit.getType() == HitResult.Type.BLOCK
				&& level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER)
				&& level.getFluidState(hit.getBlockPos()).isSource();
		if (!hitsWater) {
			bot.say(requester, "看不到 " + water.toShortString() + " 的水面（被挡住了），换一处。");
			rejected.add(water);
			phase = Phase.FIND;
			return Status.RUNNING;
		}

		int bucketSlot = inv.findSlotMatchingItem(Items.BUCKET.getDefaultInstance());
		if (bucketSlot < 0) {
			bot.say(requester, "空桶不见了。");
			return Status.FAILED;
		}
		bot.movement().selectSlot(bucketSlot);
		int before = countWaterBuckets(inv);
		bot.gameMode.useItem(bot, level, inv.getSelectedItem(), InteractionHand.MAIN_HAND);
		if (countWaterBuckets(inv) <= before) {
			rejected.add(water);
			phase = Phase.FIND;
			return Status.RUNNING;
		}
		if (asStep) {
			return Status.DONE;
		}
		bot.say(requester, "装好水了，正在回来。");
		phase = Phase.RETURN;
		return Status.RUNNING;
	}

	private static int countWaterBuckets(Inventory inv) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).is(Items.WATER_BUCKET)) {
				n += inv.getItem(i).getCount();
			}
		}
		return n;
	}

	private static long horizontalDistSq(BlockPos a, BlockPos b) {
		long dx = a.getX() - b.getX();
		long dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	/**
	 * Nearest water source with open air above it, outside protected zones, in loaded chunks.
	 * Checks the exposed surface of every column (however far below or above the bot that is),
	 * plus everything within {@code height} blocks of the bot for water in caves and overhangs.
	 */
	private BlockPos nearestWater(BotPlayer bot, int radius, int height) {
		ServerLevel level = bot.level();
		BlockPos origin = bot.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int x = origin.getX() + dx, z = origin.getZ() + dz;
				pos.set(x, origin.getY(), z);
				if (!level.hasChunkAt(pos)) {
					continue;
				}
				int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				pos.setY(surface);
				if (isFillable(level, pos) && pos.distSqr(origin) < bestDist) {
					best = pos.immutable();
					bestDist = pos.distSqr(origin);
				}
				for (int dy = -height; dy <= height; dy++) {
					pos.setY(origin.getY() + dy);
					if (pos.distSqr(origin) < bestDist && isFillable(level, pos)) {
						best = pos.immutable();
						bestDist = pos.distSqr(origin);
					}
				}
			}
		}
		return best;
	}

	private boolean isFillable(ServerLevel level, BlockPos pos) {
		FluidState fluid = level.getFluidState(pos);
		return fluid.isSource() && fluid.is(FluidTags.WATER)
				&& !rejected.contains(pos)
				&& level.getBlockState(pos.above()).isAir()
				&& !ProtectionZones.isProtected(level, pos);
	}

	@Override
	public void onEnd(BotPlayer bot, Status status) {
		if (locating != null) {
			locating.cancel(false);
		}
		if (asStep) {
			return;
		}
		switch (status) {
			case DONE -> bot.say(requester, "水取回来了，右键我把水桶拿走吧。");
			case CANCELLED -> bot.say(requester, "取水任务已取消。");
			default -> {
			}
		}
	}

	@Override
	public String describe() {
		return switch (phase) {
			case FIND -> "取水：在附近找水源";
			case LOCATE -> "取水：搜索远处的水域群系";
			case TRAVEL -> "取水：前往水域 " + biomeTarget.getX() + ", " + biomeTarget.getZ();
			case GOTO -> "取水：前往水源 " + water.toShortString();
			case FILL -> "取水：装水";
			case RETURN -> "取水：返回";
		};
	}
}
