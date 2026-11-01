package com.vale.helperbot.task;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.knowledge.CraftPlanner;
import com.vale.helperbot.path.Mode;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Get N of an item by whatever route is cheapest: gather it, craft it, smelt it, recursively.
 * Plans with {@link CraftPlanner}, runs only the first action, then plans again from the real
 * inventory, so a short gather or a failed craft just leads to a new plan.
 */
public class AcquireTask implements BotTask {
	private static final int MAX_STEPS = 80;
	/** The same action failing to change anything this many times in a row means we're stuck. */
	private static final int MAX_REPEATS = 3;

	private enum Phase {
		PLAN, GATHER, HUNT, CRAFT, SMELT, RETURN
	}

	private final ServerPlayer requester;
	private final Item item;
	private final int wanted;
	private final Mode mode;
	private final ComeTask returnLeg;
	private final Set<Item> noGather = new HashSet<>();
	private final Set<Item> planItems = new HashSet<>();
	private Phase phase = Phase.PLAN;
	private int startCount = -1;
	private boolean announced;
	private int steps;
	private String lastAction;
	private int repeats;

	private GatherTask gather;
	private HuntTask hunt;
	private Item huntItem;
	private CraftStep craft;
	private SmeltStep smelt;

	public AcquireTask(ServerPlayer requester, Item item, int wanted, Mode mode) {
		this.requester = requester;
		this.item = item;
		this.wanted = wanted;
		this.mode = mode;
		this.returnLeg = new ComeTask(requester, mode);
	}

	@Override
	public boolean wantsPickup(ItemStack stack) {
		return stack.is(item) || planItems.contains(stack.getItem()) || (gather != null && gather.wantsPickup(stack))
				|| (hunt != null && hunt.wantsPickup(stack));
	}

	@Override
	public Status tick(BotPlayer bot) {
		if (startCount < 0) {
			startCount = count(bot.getInventory());
		}
		return switch (phase) {
			case PLAN -> plan(bot);
			case GATHER -> {
				Status s = gather.tick(bot);
				if (s != Status.RUNNING) {
					gather.onEnd(bot, s);
					if (s == Status.FAILED) {
						stepFailed(bot, gatherItem, "采集");
					} else {
						failures.remove(gatherItem);
					}
					gather = null;
					phase = Phase.PLAN;
				}
				yield Status.RUNNING;
			}
			case HUNT -> {
				Status s = hunt.tick(bot);
				if (s != Status.RUNNING) {
					hunt.onEnd(bot);
					if (hunt.failure() != null) {
						bot.say(requester, hunt.failure() + "。");
						bot.log("打猎没完成：" + hunt.failure());
					}
					if (s == Status.FAILED) {
						stepFailed(bot, huntItem, "打猎");
					} else {
						failures.remove(huntItem);
					}
					hunt = null;
					phase = Phase.PLAN;
				}
				yield Status.RUNNING;
			}
			case CRAFT -> stepDone(bot, craft.tick(bot), craft.failure());
			case SMELT -> stepDone(bot, smelt.tick(bot), smelt.failure());
			case RETURN -> requester.hasDisconnected() ? Status.FAILED : ComeTask.followTick(bot, requester, mode, returnLeg);
		};
	}

	private Item gatherItem;
	/** Failed gathering/hunting steps in a row, per item. */
	private final java.util.Map<Item, Integer> failures = new java.util.HashMap<>();
	/** A source of an item is given up on for this task only after this many failures in a row. */
	private static final int MAX_STEP_FAILURES = 3;
	/** Why the last source was given up on: the last thing said if the whole order fails. */
	private String lastFailure;

	/**
	 * One failed try at gathering or hunting something isn't the end of it (unlucky spot, a drop
	 * that floated off): plan again, maybe the same way. Only after a few in a row is that source
	 * ruled out for this order.
	 */
	private void stepFailed(BotPlayer bot, Item what, String how) {
		int n = failures.merge(what, 1, Integer::sum);
		if (n >= MAX_STEP_FAILURES) {
			noGather.add(what);
			lastFailure = how + CraftPlanner.name(what).getString() + "连续失败 " + n + " 次，这次不再" + how + "它";
			bot.log(lastFailure);
		} else {
			bot.log(how + CraftPlanner.name(what).getString() + "失败（第 " + n + " 次），重新规划再试");
		}
	}

	private Status stepDone(BotPlayer bot, Status s, String failure) {
		if (s == Status.RUNNING) {
			return Status.RUNNING;
		}
		if (s == Status.FAILED && failure != null) {
			bot.say(requester, failure);
			bot.log("这一步失败：" + failure);
		}
		craft = null;
		smelt = null;
		phase = Phase.PLAN;
		return Status.RUNNING;
	}

	private Status plan(BotPlayer bot) {
		int got = count(bot.getInventory()) - startCount;
		if (got >= wanted) {
			bot.say(requester, Component.literal("准备好了 " + got + " 个").append(name(item)).append("，正在回来。"));
			phase = Phase.RETURN;
			return Status.RUNNING;
		}
		if (++steps > MAX_STEPS) {
			return giveUp(bot, "步骤太多了，可能在原地打转");
		}
		Map<Item, Integer> inventory = CraftPlanner.counts(bot.getInventory());
		// What was already in the bag before the task doesn't count towards the order.
		inventory.merge(item, -startCount, Integer::sum);
		CraftPlanner.Plan plan = new CraftPlanner(bot.level(), bot, bot.getInventory(), noGather)
				.plan(item, wanted - got, inventory);
		if (!plan.possible()) {
			MutableComponent why = Component.literal("想不出怎么弄到").append(name(item));
			if (lastFailure != null) {
				why.append("（" + lastFailure + "）");
			}
			for (Component problem : plan.problems()) {
				why.append("；").append(problem);
			}
			return giveUp(bot, why.getString());
		}
		List<CraftPlanner.Action> actions = plan.actions();
		if (actions.isEmpty()) {
			phase = Phase.RETURN;
			return Status.RUNNING;
		}
		rememberPlanItems(actions);
		if (!announced) {
			announced = true;
			bot.say(requester, describePlan(actions));
		}

		CraftPlanner.Action next = actions.get(0);
		bot.log("计划共 " + actions.size() + " 步，下一步：" + describePlan(List.of(next)).getString().replace("计划：", ""));
		String key = next.toString();
		repeats = key.equals(lastAction) ? repeats + 1 : 0;
		lastAction = key;
		if (repeats >= MAX_REPEATS) {
			return giveUp(bot, "同一步反复失败");
		}
		switch (next) {
			case CraftPlanner.Gather g -> {
				gatherItem = g.item();
				gather = new GatherTask(requester, g.item(), g.count(), mode, true);
				phase = Phase.GATHER;
			}
			case CraftPlanner.Hunt h -> {
				huntItem = h.item();
				hunt = new HuntTask(requester, h.item(), h.count(), mode, h.sources());
				phase = Phase.HUNT;
			}
			case CraftPlanner.Craft c -> {
				bot.movement().stop();
				craft = new CraftStep(requester, c);
				phase = Phase.CRAFT;
			}
			case CraftPlanner.Smelt s -> {
				bot.movement().stop();
				smelt = new SmeltStep(requester, s);
				phase = Phase.SMELT;
			}
		}
		return Status.RUNNING;
	}

	/** Items the plan makes or uses: under strict rules only these get picked up. */
	private void rememberPlanItems(List<CraftPlanner.Action> actions) {
		planItems.add(Items.CRAFTING_TABLE);
		planItems.add(Items.FURNACE);
		for (CraftPlanner.Action action : actions) {
			switch (action) {
				case CraftPlanner.Gather g -> planItems.add(g.item());
				case CraftPlanner.Hunt h -> planItems.add(h.item());
				case CraftPlanner.Craft c -> {
					planItems.add(c.recipe().result());
					for (Ingredient ingredient : c.recipe().ingredients()) {
						ingredient.items().forEach(h -> planItems.add(h.value()));
					}
				}
				case CraftPlanner.Smelt s -> {
					planItems.add(s.recipe().result());
					s.recipe().input().items().forEach(h -> planItems.add(h.value()));
				}
			}
		}
	}

	private Component describePlan(List<CraftPlanner.Action> actions) {
		MutableComponent text = Component.literal("计划：");
		for (int i = 0; i < actions.size(); i++) {
			if (i > 0) {
				text.append(" → ");
			}
			switch (actions.get(i)) {
				case CraftPlanner.Gather g -> text.append("采集 ").append(name(g.item())).append(" ×" + g.count());
				case CraftPlanner.Hunt h -> text.append("打猎 ").append(name(h.item())).append(" ×" + h.count());
				case CraftPlanner.Craft c -> text.append("合成 ").append(name(c.recipe().result()))
						.append(" ×" + c.times() * c.recipe().resultCount());
				case CraftPlanner.Smelt s -> text.append("熔炼 ").append(name(s.recipe().result()))
						.append(" ×" + s.count() * s.recipe().resultCount());
			}
		}
		return text;
	}

	/** Stops. If anything was made, still brings it back. */
	private Status giveUp(BotPlayer bot, String reason) {
		int got = count(bot.getInventory()) - startCount;
		bot.log("放弃：" + reason);
		if (got > 0) {
			// One message, reason included, so it isn't lost among earlier ones.
			bot.say(requester, Component.literal("没能凑够 " + wanted + " 个").append(name(item)).append("：" + reason
					+ "。先把做好的 " + got + " 个带回来。"));
			phase = Phase.RETURN;
			return Status.RUNNING;
		}
		bot.say(requester, reason + "。");
		return Status.FAILED;
	}

	private int count(Inventory inv) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).is(item)) {
				n += inv.getItem(i).getCount();
			}
		}
		return n;
	}

	private static Component name(Item item) {
		return CraftPlanner.name(item);
	}

	@Override
	public void onEnd(BotPlayer bot, Status status) {
		if (gather != null) {
			gather.onEnd(bot, status);
		}
		if (hunt != null) {
			hunt.onEnd(bot);
		}
		switch (status) {
			case DONE -> bot.say(requester, Component.literal("带回来了 " + Math.max(0, count(bot.getInventory()) - startCount)
					+ " 个").append(name(item)).append("，右键我拿走吧。"));
			case CANCELLED -> bot.say(requester, Component.literal("获取").append(name(item)).append("的任务已取消。"));
			default -> {
			}
		}
	}

	@Override
	public String describe() {
		String what = new ItemStack(item).getHoverName().getString() + " ×" + wanted;
		return switch (phase) {
			case PLAN -> "获取 " + what + "：规划中";
			case GATHER -> "获取 " + what + " → " + gather.describe();
			case HUNT -> "获取 " + what + " → " + hunt.describe();
			case CRAFT -> "获取 " + what + " → " + craft.describe().getString();
			case SMELT -> "获取 " + what + " → " + smelt.describe().getString();
			case RETURN -> "获取 " + what + "：返回";
		};
	}
}
