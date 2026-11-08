package com.vale.helperbot.task;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.bot.BotTask;
import com.vale.helperbot.bot.MovementController;
import com.vale.helperbot.guard.ProtectionZones;
import com.vale.helperbot.knowledge.BiomeHints;
import com.vale.helperbot.knowledge.BlockScanner;
import com.vale.helperbot.knowledge.CraftPlanner;
import com.vale.helperbot.knowledge.Habitat;
import com.vale.helperbot.knowledge.Interactions;
import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.MineSpot;
import com.vale.helperbot.path.Mode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fills {@code count} containers at what they're used on (a lava source, powder snow, water for a
 * bottle): the nearest one in what's loaded, or, with none around, the places the biomes say have
 * it (see Explorer), as gathering does. It stands beside it, never in it or on it (lava, powder
 * snow), with it in sight, and uses the container on it the way a player does.
 */
final class ScoopStep {
	private static final int SCAN_CHUNKS = 4;
	private static final int TRAVEL_SCAN_INTERVAL = 100;
	private static final int MAX_TRIES = 8;

	private enum Stage {
		FIND, MAP, TRAVEL, GOTO
	}

	private final ServerPlayer requester;
	private final Interactions.Way way;
	private final int count;
	private final Mode mode;
	private final Map<Item, Explorer> explorers;
	private final Set<BlockPos> rejected = new HashSet<>();
	private Stage stage = Stage.FIND;
	private BlockPos target;
	private Explorer exploring;
	private int travelTicks;
	private int before = -1;
	private String failure;

	ScoopStep(ServerPlayer requester, Interactions.Way way, int count, Mode mode, Map<Item, Explorer> explorers) {
		this.requester = requester;
		this.way = way;
		this.count = count;
		this.mode = mode;
		this.explorers = explorers;
	}

	String failure() {
		return failure;
	}

	Component describe() {
		return Component.literal("装 ").append(CraftPlanner.name(way.result())).append(target != null ? "（去 " + target.toShortString() + "）" : "");
	}

	BotTask.Status tick(BotPlayer bot) {
		int have = bot.getInventory().countItem(way.result());
		if (before < 0) {
			before = have;
		}
		if (have - before >= count) {
			bot.movement().stop();
			return BotTask.Status.DONE;
		}
		if (bot.getInventory().findSlotMatchingItem(new ItemStack(way.input())) < 0) {
			return fail("背包里没有" + CraftPlanner.name(way.input()).getString());
		}
		if (rejected.size() >= MAX_TRIES) {
			return fail("试了 " + MAX_TRIES + " 处都装不到" + CraftPlanner.name(way.result()).getString());
		}
		return switch (stage) {
			case FIND -> find(bot);
			case MAP -> map(bot);
			case TRAVEL -> travel(bot);
			case GOTO -> approach(bot);
		};
	}

	private boolean usable(ServerLevel level, BlockPos p, BlockState state) {
		return way.at().contains(state.getBlock()) && (state.getFluidState().isEmpty() || state.getFluidState().isSource())
				&& !rejected.contains(p) && !ProtectionZones.isProtected(level, p) && MineSpot.exposed(level, p);
	}

	private BlockPos scan(BotPlayer bot, int chunks) {
		ServerLevel level = bot.level();
		List<BlockPos> found = BlockScanner.find(level, bot.blockPosition(), chunks,
				state -> way.at().contains(state.getBlock()), p -> usable(level, p, level.getBlockState(p)), 1);
		return found.isEmpty() ? null : found.get(0);
	}

	private BotTask.Status find(BotPlayer bot) {
		target = scan(bot, SCAN_CHUNKS);
		if (target != null) {
			startApproach(bot);
			return BotTask.Status.RUNNING;
		}
		stage = Stage.MAP;
		return map(bot);
	}

	/** None around: the next place the biomes say has it (see Explorer). */
	private BotTask.Status map(BotPlayer bot) {
		ServerLevel level = bot.level();
		Explorer explorer = explorers.computeIfAbsent(way.result(), i -> {
			Map<Holder<Biome>, Double> worth = new HashMap<>(BiomeHints.perChunk(level, way.at()));
			boolean water = way.at().stream().allMatch(b -> b.defaultBlockState().getFluidState().is(net.minecraft.tags.FluidTags.WATER));
			return new Explorer(level, bot.blockPosition(), worth, BiomeHints.layerFor(level, way.at()) != Habitat.Layer.SURFACE,
					!water, SCAN_CHUNKS * 16, mode.speed());
		});
		if (!explorer.ready()) {
			return BotTask.Status.RUNNING;
		}
		explorer.look(bot.blockPosition());
		BlockPos next = explorer.timeUp() ? null : explorer.next(level, bot.blockPosition(), null, count);
		if (next == null) {
			return fail("方圆 " + Explorer.RADIUS + " 格内找不到能装" + CraftPlanner.name(way.result()).getString() + "的地方");
		}
		bot.log("附近没有能装" + CraftPlanner.name(way.result()).getString() + "的，去 " + next.getX() + ", " + next.getZ() + " 找");
		exploring = explorer;
		Habitat.Layer layer = BiomeHints.layerFor(level, way.at());
		bot.movement().goTo(Habitat.arrived(next, 16, layer), Habitat.target(level, next, layer, bot.blockPosition()), mode,
				!Habitat.heightKnown(layer));
		travelTicks = 0;
		stage = Stage.TRAVEL;
		return BotTask.Status.RUNNING;
	}

	private BotTask.Status travel(BotPlayer bot) {
		if (exploring != null) {
			exploring.tick();
		}
		if (++travelTicks % TRAVEL_SCAN_INTERVAL == 0) {
			BlockPos found = scan(bot, SCAN_CHUNKS);
			if (found != null) {
				target = found;
				bot.movement().stop();
				startApproach(bot);
				return BotTask.Status.RUNNING;
			}
		}
		MovementController.Status status = bot.movement().status();
		if (status == MovementController.Status.ARRIVED || status == MovementController.Status.FAILED
				|| status == MovementController.Status.IDLE || (exploring != null && exploring.timeUp())) {
			if (exploring != null) {
				exploring.leavePlace();
			}
			stage = Stage.FIND;
		}
		return BotTask.Status.RUNNING;
	}

	/**
	 * Beside it, in reach and in sight, head out of the water; never standing in or on what it's
	 * after (lava, powder snow) or in any lava.
	 */
	private void startApproach(BotPlayer bot) {
		BlockPos t = target;
		Set<Block> at = way.at();
		var reach = MineSpot.reachGoal(t, MineSpot.reach(bot) - 0.5, bot.getEyeHeight(), true, true);
		bot.movement().goTo((w, p) -> reach.test(w, p)
				&& !at.contains(w.getBlockState(p).getBlock()) && !at.contains(w.getBlockState(p.below()).getBlock())
				&& !w.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA) && !w.getFluidState(p.below()).is(net.minecraft.tags.FluidTags.LAVA)
				&& !LocalPathfinder.isWater(w, p.above()), t, mode);
		stage = Stage.GOTO;
	}

	private BotTask.Status approach(BotPlayer bot) {
		MovementController.Status status = bot.movement().status();
		if (status == MovementController.Status.ARRIVED) {
			return fill(bot);
		}
		if (status == MovementController.Status.FAILED || status == MovementController.Status.IDLE) {
			bot.log("去 " + target.toShortString() + " 的路走不通（" + bot.movement().failReason() + "），换一处");
			rejected.add(target);
			stage = Stage.FIND;
		}
		return BotTask.Status.RUNNING;
	}

	private BotTask.Status fill(BotPlayer bot) {
		ServerLevel level = bot.level();
		bot.movement().stop();
		Vec3 aim = Vec3.atCenterOf(target);
		bot.movement().hands().lookAt(aim);
		Vec3 eye = bot.getEyePosition();
		Vec3 end = eye.add(aim.subtract(eye).normalize().scale(bot.blockInteractionRange()));
		BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, bot));
		// Any one of them the container would hit will do (the one next to it, say).
		if (hit.getType() != HitResult.Type.BLOCK || !usable(level, hit.getBlockPos(), level.getBlockState(hit.getBlockPos()))) {
			bot.log("对不准 " + target.toShortString() + "（被挡住了），换一处");
			rejected.add(target);
			stage = Stage.FIND;
			return BotTask.Status.RUNNING;
		}
		int slot = bot.getInventory().findSlotMatchingItem(new ItemStack(way.input()));
		int had = bot.getInventory().countItem(way.result());
		bot.movement().hands().selectSlot(slot);
		bot.gameMode.useItem(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND);
		if (bot.getInventory().countItem(way.result()) <= had) {
			bot.log("在 " + target.toShortString() + " 没装到，换一处");
			rejected.add(target);
		}
		stage = Stage.FIND;
		return BotTask.Status.RUNNING;
	}

	private BotTask.Status fail(String why) {
		failure = why;
		return BotTask.Status.FAILED;
	}
}
