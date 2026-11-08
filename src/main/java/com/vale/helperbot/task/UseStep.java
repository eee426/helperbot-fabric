package com.vale.helperbot.task;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.bot.BotTask;
import com.vale.helperbot.guard.Placement;
import com.vale.helperbot.knowledge.CraftPlanner;
import com.vale.helperbot.knowledge.Interactions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Makes {@code count} by using something on a block of its own: puts the block down next to
 * itself (as it does a crafting table, see Station), strips it with an axe or pours water on the
 * powder and takes the water back up, then breaks it and picks it up. An empty map is just used.
 * It leaves nothing behind: the water goes back into the bucket the same tick it's poured, before
 * it can flow.
 */
final class UseStep {
	private enum Stage {
		PLACE, CONVERT, RETRIEVE
	}

	private final ServerPlayer requester;
	private final Interactions.Way way;
	private final int count;
	private final OutOfReach unreachable;
	private Station station;
	private Stage stage = Stage.PLACE;
	private int made;
	private int before = -1;
	private String failure;

	UseStep(ServerPlayer requester, Interactions.Way way, int count, OutOfReach unreachable) {
		this.requester = requester;
		this.way = way;
		this.count = count;
		this.unreachable = unreachable;
	}

	String failure() {
		return failure;
	}

	Component describe() {
		return Component.literal(verb(way)).append(CraftPlanner.name(way.result())).append(" " + made + "/" + count);
	}

	/** How a way reads in a plan. */
	static String verb(Interactions.Way way) {
		return switch (way.kind()) {
			case STRIP -> "去皮 ";
			case SOLIDIFY -> "浇水做 ";
			case SCOOP -> "装 ";
			case MOB -> "从动物身上取 ";
			case USE -> "使用得到 ";
		};
	}

	BotTask.Status tick(BotPlayer bot) {
		Inventory inv = bot.getInventory();
		if (before < 0) {
			before = inv.countItem(way.result());
		}
		made = inv.countItem(way.result()) - before;
		if (made >= count) {
			return BotTask.Status.DONE;
		}
		if (way.kind() == Interactions.Kind.USE) {
			return useInHand(bot);
		}
		return switch (stage) {
			case PLACE -> place(bot);
			case CONVERT -> convert(bot);
			case RETRIEVE -> retrieve(bot);
		};
	}

	private BotTask.Status useInHand(BotPlayer bot) {
		int slot = bot.getInventory().findSlotMatchingItem(new ItemStack(way.input()));
		if (slot < 0) {
			return fail("背包里没有" + CraftPlanner.name(way.input()).getString());
		}
		int had = bot.getInventory().countItem(way.result());
		bot.movement().hands().selectSlot(slot);
		bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
		return bot.getInventory().countItem(way.result()) > had ? BotTask.Status.RUNNING
				: fail("用了" + CraftPlanner.name(way.input()).getString() + "也没得到" + CraftPlanner.name(way.result()).getString());
	}

	/** How many it had when putting this one down: one more once it's back. */
	private int cycleStart;

	private BotTask.Status place(BotPlayer bot) {
		if (station == null) {
			station = new Station(way.input(), way.placed(), unreachable);
			cycleStart = bot.getInventory().countItem(way.result());
		}
		BotTask.Status s = station.placeTick(bot);
		if (s == BotTask.Status.FAILED) {
			return fail(station.failure());
		}
		if (s == BotTask.Status.DONE) {
			stage = Stage.CONVERT;
		}
		return BotTask.Status.RUNNING;
	}

	private BotTask.Status convert(BotPlayer bot) {
		ServerLevel level = bot.level();
		BlockPos pos = station.pos();
		if (!level.getBlockState(pos).is(way.becomes())) {
			String why = way.kind() == Interactions.Kind.STRIP ? strip(bot, pos) : pourWater(bot, pos);
			if (why != null) {
				retrieveAfterFailure = why;
				stage = Stage.RETRIEVE; // pick back up what was put down, then say why
				return BotTask.Status.RUNNING;
			}
		}
		station.became(way.result(), way.becomes());
		stage = Stage.RETRIEVE;
		return BotTask.Status.RUNNING;
	}

	/** Why it couldn't be turned: picked back up first, then the step fails with this. */
	private String retrieveAfterFailure;

	/** Null if it's stripped now. */
	private String strip(BotPlayer bot, BlockPos pos) {
		Inventory inv = bot.getInventory();
		int slot = -1;
		for (int i = 0; i < inv.getContainerSize() && slot < 0; i++) {
			if (inv.getItem(i).is(ItemTags.AXES)) {
				slot = i;
			}
		}
		if (slot < 0) {
			return "没有斧子";
		}
		bot.movement().hands().selectSlot(slot);
		Direction face = faceToward(bot, pos);
		Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		bot.movement().hands().lookAt(hit);
		bot.gameMode.useItemOn(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
		bot.swing(InteractionHand.MAIN_HAND);
		return bot.level().getBlockState(pos).is(way.becomes()) ? null : "斧子没能给它去皮";
	}

	/**
	 * Water poured on top of the powder (the bucket aimed at its top), and scooped back up at once:
	 * the powder turns as the water touches it. Null if it's concrete now.
	 */
	private String pourWater(BotPlayer bot, BlockPos pos) {
		ServerLevel level = bot.level();
		BlockPos above = pos.above();
		if (!level.getBlockState(above).canBeReplaced(Fluids.WATER) || !level.getFluidState(above).isEmpty()
				|| !Placement.allowed(level, above, Placement.Kind.STATION)) {
			return "上面没地方倒水";
		}
		Inventory inv = bot.getInventory();
		int slot = inv.findSlotMatchingItem(new ItemStack(Items.WATER_BUCKET));
		if (slot < 0) {
			return "没有水桶";
		}
		bot.movement().hands().selectSlot(slot);
		bot.movement().hands().lookAt(new Vec3(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5));
		bot.gameMode.useItem(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND);
		boolean turned = level.getBlockState(pos).is(way.becomes());
		if (level.getFluidState(above).isSource()) {
			// Back into the bucket before it flows (the bucket in hand is the empty one now).
			bot.movement().hands().lookAt(Vec3.atCenterOf(above));
			bot.gameMode.useItem(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND);
			if (!level.getFluidState(above).isEmpty()) {
				bot.log("倒在 " + above.toShortString() + " 的水没收回来");
			}
		}
		return turned ? null : "倒了水也没变成" + CraftPlanner.name(way.result()).getString();
	}

	/** Ticks spent going after what it broke, once the station gave up waiting for it. */
	private int fetching;
	private static final int MAX_FETCH_TICKS = 100;

	private BotTask.Status retrieve(BotPlayer bot) {
		if (station != null) {
			BotTask.Status s = station.retrieve(bot, requester);
			if (s == BotTask.Status.RUNNING) {
				return s;
			}
			station = null;
			fetching = 0;
		}
		if (retrieveAfterFailure != null) {
			stage = Stage.PLACE;
			String why = retrieveAfterFailure;
			retrieveAfterFailure = null;
			return fail(why);
		}
		if (bot.getInventory().countItem(way.result()) <= cycleStart) {
			// Still lying there (it rolled off, the bot stood on the wrong side): go and get it.
			var drop = bot.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, bot.getBoundingBox().inflate(6),
					e -> e.isAlive() && e.getItem().is(way.result())).stream().findFirst();
			if (drop.isPresent() && ++fetching <= MAX_FETCH_TICKS) {
				bot.movement().nudgeToward(drop.get().position());
				return BotTask.Status.RUNNING;
			}
			stage = Stage.PLACE;
			return fail("没能收回" + CraftPlanner.name(way.result()).getString());
		}
		stage = Stage.PLACE;
		return BotTask.Status.RUNNING;
	}

	private static Direction faceToward(BotPlayer bot, BlockPos pos) {
		Vec3 d = bot.getEyePosition().subtract(Vec3.atCenterOf(pos));
		double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
		if (ay >= ax && ay >= az) {
			return d.y > 0 ? Direction.UP : Direction.DOWN;
		}
		if (ax >= az) {
			return d.x > 0 ? Direction.EAST : Direction.WEST;
		}
		return d.z > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	private BotTask.Status fail(String why) {
		failure = why;
		return BotTask.Status.FAILED;
	}
}
