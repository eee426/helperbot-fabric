package com.vale.helperbot.test;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.task.BotTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * One test scene: build it, give the bot a job, then check every tick whether it passed, failed
 * or is still going. The ids match docs/REGRESSION_TESTS.md where a row exists.
 *
 * @param setup   builds the scene and returns the task to start (null: check only, no task)
 * @param check   PASS / FAIL with a reason, or null while still undecided
 * @param timeout game ticks before it counts as a failure
 */
public record Scenario(String id, String title, int timeout, Setup setup, Check check) {

	public interface Setup {
		/** Build the scene around {@code arena}, prepare the bot; the requester stands next to it. */
		BotTask start(Arena arena, BotPlayer bot, ServerPlayer requester);
	}

	public interface Check {
		Outcome check(Context c);
	}

	/** What a check can look at. */
	public record Context(Arena arena, BotPlayer bot, int ticks, String log) {
		public int count(Item item) {
			int n = 0;
			for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
				ItemStack stack = bot.getInventory().getItem(i);
				if (stack.is(item)) {
					n += stack.getCount();
				}
			}
			return n;
		}

		/** The task ended on its own (DONE or FAILED), as opposed to still running. */
		public boolean ended() {
			return bot.getTask() == null;
		}

		public boolean logHas(String text) {
			return log.contains(text);
		}

		public int logCount(String text) {
			int n = 0;
			for (int i = log.indexOf(text); i >= 0; i = log.indexOf(text, i + 1)) {
				n++;
			}
			return n;
		}
	}

	public record Outcome(boolean pass, String reason) {
		public static Outcome pass(String reason) {
			return new Outcome(true, reason);
		}

		public static Outcome fail(String reason) {
			return new Outcome(false, reason);
		}
	}

	/** Pass once the bot holds {@code n} of the item; fail if the task gives up first. Plus extra rules. */
	public static Check has(Item item, int n, Predicate<Context> also, String alsoReason) {
		return c -> {
			if (also != null && !also.test(c)) {
				return Outcome.fail(alsoReason);
			}
			if (c.count(item) >= n) {
				return Outcome.pass("拿到 " + c.count(item) + " 个 " + item);
			}
			if (c.ended()) {
				return Outcome.fail("任务结束了（" + c.bot().lastTaskStatus() + "），只有 " + c.count(item) + " 个");
			}
			return null;
		};
	}

	public static Check has(Item item, int n) {
		return has(item, n, null, null);
	}
}
