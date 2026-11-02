package com.vale.helperbot.test;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.knowledge.CraftPlanner;
import com.vale.helperbot.path.Mode;
import com.vale.helperbot.task.AcquireTask;
import com.vale.helperbot.task.ComeTask;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The test scenes. Each builds its own little world on the superflat plains (see {@link Arena}:
 * surface at y=41, solid stone below y=37) and checks one thing the bot got wrong before, or one
 * basic thing it must keep doing right. Ids follow docs/REGRESSION_TESTS.md; "H" ones are new
 * (height). Add a scene here whenever a bug is fixed, so it stays fixed.
 */
public final class Scenarios {
	private Scenarios() {
	}

	private static final int MINUTE = 20 * 60;

	public static List<Scenario> all() {
		return List.of(
				new Scenario("S1", "跨过沟走到你身边", 2 * MINUTE, (a, bot, me) -> {
					a.fill(10, 20, -6, 13, 40, 6, Blocks.AIR); // a ravine 4 wide, 20 deep
					me.teleportTo(a.level, a.origin.getX() + 25.5, Arena.SURFACE, a.origin.getZ() + 0.5, Set.of(), 0, 0, true);
					give(bot, Items.BREAD, 16);
					return new ComeTask(me, Mode.NORMAL);
				}, c -> c.bot().position().distanceTo(c.arena().at(25, Arena.SURFACE, 0).getBottomCenter()) <= 3
						? Scenario.Outcome.pass("到了") : c.ended() ? Scenario.Outcome.fail("任务结束了但没到") : null),

				new Scenario("C35", "空背包做石镐（木棍要用木板做）", 10 * MINUTE, (a, bot, me) -> {
					a.tree(6, 6, 5);
					a.tree(-7, 5, 5);
					a.tree(5, -8, 6);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.STONE_PICKAXE, 1, Mode.NORMAL);
				}, Scenario.has(Items.STONE_PICKAXE, 1, c -> !c.logHas("采集 Stick") && !c.logHas("采集 木棍"),
						"计划里出现了\"采集木棍\"（去挖树叶）")),

				new Scenario("C36", "空背包规划钻石镐（只规划，看快不快、对不对）", 20, (a, bot, me) -> {
					a.tree(6, 6, 5);
					a.tree(-7, 5, 5);
					a.fill(3, 30, -3, 4, 31, -2, Blocks.IRON_ORE);
					a.fill(-3, 32, 3, -2, 33, 4, Blocks.COAL_ORE);
					a.fill(0, -40, 0, 1, -40, 1, Blocks.DIAMOND_ORE);
					give(bot, Items.BREAD, 16);
					CraftPlanner planner = new CraftPlanner(a.level, bot, bot.getInventory(), new HashSet<>());
					long start = System.nanoTime();
					CraftPlanner.Plan plan = planner.plan(Items.DIAMOND_PICKAXE, 1, CraftPlanner.counts(bot.getInventory()));
					long ms = (System.nanoTime() - start) / 1_000_000;
					PLAN_RESULT[0] = !plan.possible() ? "规划失败：" + plan.problems().stream().limit(4).map(x -> x.getString()).toList()
							: ms > 500 ? "太慢：" + ms + " 毫秒，" + planner.stats()
							: plan.actions().stream().noneMatch(x -> x instanceof CraftPlanner.Gather g && g.item() == Items.DIAMOND)
							? "计划里没有挖钻石"
							: plan.actions().stream().anyMatch(x -> x instanceof CraftPlanner.Hunt)
							? "计划里要打怪（例如打僵尸掉铁锭，一个要打约 30 只）：" + plan.actions().stream()
									.filter(x -> x instanceof CraftPlanner.Hunt).toList() : null;
					PLAN_RESULT[1] = ms + " 毫秒，" + (plan.possible() ? plan.actions().size() : 0) + " 步";
					return null;
				}, c -> PLAN_RESULT[0] == null ? Scenario.Outcome.pass(PLAN_RESULT[1]) : Scenario.Outcome.fail(PLAN_RESULT[0])),

				new Scenario("C37", "镐都快坏了（耐久 1）还要钻石镐：规划里要先做新铁镐（只规划）", 20, (a, bot, me) -> {
					a.tree(6, 6, 5);
					a.tree(-7, 5, 5);
					a.fill(3, 30, -3, 4, 31, -2, Blocks.IRON_ORE);
					a.fill(-3, 32, 3, -2, 33, 4, Blocks.COAL_ORE);
					a.fill(0, -40, 0, 1, -40, 1, Blocks.DIAMOND_ORE);
					for (Item pick : List.of(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE)) {
						ItemStack worn = new ItemStack(pick);
						worn.setDamageValue(worn.getMaxDamage() - 1);
						bot.getInventory().add(worn);
					}
					give(bot, Items.BREAD, 16);
					CraftPlanner planner = new CraftPlanner(a.level, bot, bot.getInventory(), new HashSet<>());
					CraftPlanner.Plan plan = planner.plan(Items.DIAMOND_PICKAXE, 1, CraftPlanner.counts(bot.getInventory()));
					PLAN_RESULT[0] = !plan.possible() ? "规划失败：" + plan.problems().stream().limit(4).map(x -> x.getString()).toList()
							: plan.actions().stream().noneMatch(x -> x instanceof CraftPlanner.Craft c && c.recipe().result() == Items.IRON_PICKAXE)
							? "计划里没有做新铁镐（把快坏的那把当成能用的了）"
							: plan.actions().stream().anyMatch(x -> x instanceof CraftPlanner.Hunt) ? "计划里要打怪拿铁锭" : null;
					PLAN_RESULT[1] = plan.possible() ? plan.actions().size() + " 步，含做新铁镐" : "";
					return null;
				}, c -> PLAN_RESULT[0] == null ? Scenario.Outcome.pass(PLAN_RESULT[1]) : Scenario.Outcome.fail(PLAN_RESULT[0])),

				new Scenario("C33", "白色地毯：做好的不拿去染色", 5 * MINUTE, (a, bot, me) -> {
					for (int i = 0; i < 4; i++) {
						a.spawn(EntityType.SHEEP, 6 + i, Arena.SURFACE, 4);
					}
					give(bot, Items.IRON_SWORD, 1);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.WHITE_CARPET, 3, Mode.NORMAL);
				}, Scenario.has(Items.WHITE_CARPET, 3, c -> !c.logHas("Blue Carpet") && !c.logHas("蓝色地毯")
						&& !c.logHas("Blue Dye") && !c.logHas("蓝色染料"), "计划里出现了染色")),

				new Scenario("W19", "水下沙砾拿燧石，工具不乱换", 10 * MINUTE, (a, bot, me) -> {
					a.fill(8, 34, -4, 16, 35, 4, Blocks.GRAVEL);
					a.fill(8, 36, -4, 16, 40, 4, Blocks.WATER);
					give(bot, Items.IRON_SHOVEL, 1);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.FLINT, 3, Mode.NORMAL);
				}, Scenario.has(Items.FLINT, 3, c -> !c.logHas("重新选工具"), "挖的时候换了工具（手里的东西挖不出）")),

				new Scenario("W20", "深水底挖黏土，不挖一半上去换气", 10 * MINUTE, (a, bot, me) -> {
					a.fill(8, 31, -4, 16, 31, 4, Blocks.CLAY);
					a.fill(8, 32, -4, 16, 40, 4, Blocks.WATER);
					give(bot, Items.IRON_SHOVEL, 1);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.CLAY_BALL, 16, Mode.NORMAL);
				}, Scenario.has(Items.CLAY_BALL, 16, c -> c.logCount("该换气了") <= 8, "换气超过 8 次（挖一半上去又回来）")),

				new Scenario("W21", "钻石在瀑布底下（瀑布有岩石檐）", 10 * MINUTE, (a, bot, me) -> {
					a.fill(18, 10, -2, 22, 40, 2, Blocks.AIR); // a shaft 5x5, 30 deep
					a.fill(18, 25, -2, 19, 26, 2, Blocks.STONE); // an overhang half way down
					a.set(22, 40, 0, Blocks.WATER); // the waterfall pours down one side
					a.set(20, 9, 0, Blocks.DIAMOND_ORE);
					give(bot, Items.IRON_PICKAXE, 1);
					give(bot, Items.COBBLESTONE, 64);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.DIAMOND, 1, Mode.NORMAL);
				}, Scenario.has(Items.DIAMOND, 1)),

				new Scenario("H1", "在深矿洞里（y=-40）要生羊肉（羊在地面上）", 6 * MINUTE, (a, bot, me) -> {
					// A cave room 81 blocks down: deeper than it can see mobs (64), like a real deep cave.
					a.fill(-2, -40, -2, 2, -38, 2, Blocks.AIR);
					teleport(bot, a, 0, -40, 0);
					// A few groups 90 blocks out in every direction: out of sight from the cave (and
					// from right above it), so it has to go up and look around on the surface.
					for (int[] d : new int[][]{{90, 0}, {-90, 0}, {0, 90}, {0, -90}}) {
						for (int i = 0; i < 3; i++) {
							a.spawn(EntityType.SHEEP, d[0] + i, Arena.SURFACE, d[1] + i);
						}
					}
					give(bot, Items.IRON_SWORD, 1);
					give(bot, Items.IRON_PICKAXE, 1);
					give(bot, Items.COBBLESTONE, 64);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.MUTTON, 1, Mode.NORMAL);
				}, Scenario.has(Items.MUTTON, 1)),

				new Scenario("H2", "在矿洞里要原木（树在地面上，40 格外）", 6 * MINUTE, (a, bot, me) -> {
					a.fill(-2, 0, -2, 2, 2, 2, Blocks.AIR);
					teleport(bot, a, 0, 0, 0);
					a.tree(40, 0, 5);
					a.tree(43, 4, 5);
					give(bot, Items.IRON_PICKAXE, 1);
					give(bot, Items.COBBLESTONE, 64);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.OAK_LOG, 3, Mode.NORMAL);
				}, Scenario.has(Items.OAK_LOG, 3))
				,

				new Scenario("H3", "从矿洞（y=0）走到地面上你身边", 4 * MINUTE, (a, bot, me) -> {
					a.fill(-2, 0, -2, 2, 2, 2, Blocks.AIR);
					teleport(bot, a, 0, 0, 0);
					me.teleportTo(a.level, a.origin.getX() + 20.5, Arena.SURFACE, a.origin.getZ() + 0.5, Set.of(), 0, 0, true);
					give(bot, Items.IRON_PICKAXE, 1);
					give(bot, Items.COBBLESTONE, 64);
					give(bot, Items.BREAD, 16);
					return new ComeTask(me, Mode.NORMAL);
				}, c -> c.bot().position().distanceTo(c.arena().at(20, Arena.SURFACE, 0).getBottomCenter()) <= 3
						? Scenario.Outcome.pass("到了") : c.ended() ? Scenario.Outcome.fail("任务结束了但没到") : null),

				new Scenario("H4", "在地面上要铁（铁矿在地下 25 格）", 6 * MINUTE, (a, bot, me) -> {
					a.fill(6, 14, 6, 7, 15, 7, Blocks.IRON_ORE);
					give(bot, Items.STONE_PICKAXE, 1);
					give(bot, Items.COBBLESTONE, 64);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.RAW_IRON, 3, Mode.NORMAL);
				}, Scenario.has(Items.RAW_IRON, 3)),

				new Scenario("W23", "从悬崖顶下到底（悬崖面上有一道单层瀑布）", 4 * MINUTE, (a, bot, me) -> {
					cliffWithWaterfall(a);
					teleport(bot, a, 11, 61, 0); // on the cliff top, right above the spring
					me.teleportTo(a.level, a.origin.getX() - 6.5, Arena.SURFACE, a.origin.getZ() + 0.5, Set.of(), 0, 0, true);
					give(bot, Items.IRON_PICKAXE, 1);
					give(bot, Items.COBBLESTONE, 64);
					give(bot, Items.BREAD, 16);
					return new ComeTask(me, Mode.NORMAL);
				}, c -> c.bot().position().distanceTo(c.arena().at(-7, Arena.SURFACE, 0).getBottomCenter()) <= 3
						? Scenario.Outcome.pass("到了") : c.ended() ? Scenario.Outcome.fail("任务结束了但没到") : null),

				new Scenario("W24", "从悬崖底上到顶（悬崖面上有一道单层瀑布）", 4 * MINUTE, (a, bot, me) -> {
					cliffWithWaterfall(a);
					teleport(bot, a, -2, Arena.SURFACE, 0);
					me.teleportTo(a.level, a.origin.getX() + 14.5, 61, a.origin.getZ() + 0.5, Set.of(), 0, 0, true);
					give(bot, Items.IRON_PICKAXE, 1);
					give(bot, Items.COBBLESTONE, 64);
					give(bot, Items.BREAD, 16);
					return new ComeTask(me, Mode.NORMAL);
				}, c -> c.bot().position().distanceTo(c.arena().at(14, 61, 0).getBottomCenter()) <= 3
						? Scenario.Outcome.pass("到了") : c.ended() ? Scenario.Outcome.fail("任务结束了但没到") : null),

				new Scenario("W25", "钻石在瀑布后面的崖壁里（半山腰）", 6 * MINUTE, (a, bot, me) -> {
					cliffWithWaterfall(a);
					a.set(10, 50, 0, Blocks.DIAMOND_ORE); // right behind the falling water
					teleport(bot, a, -2, Arena.SURFACE, 0);
					give(bot, Items.IRON_PICKAXE, 1);
					give(bot, Items.COBBLESTONE, 64);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.DIAMOND, 1, Mode.NORMAL);
				}, Scenario.has(Items.DIAMOND, 1)),

				new Scenario("W26", "结冰的池塘里抓鳕鱼（鱼在冰下，离洞口远）", 5 * MINUTE, (a, bot, me) -> {
					a.fill(4, 36, -6, 18, 39, 6, Blocks.WATER); // a pond 4 deep
					a.fill(4, 40, -6, 18, 40, 6, Blocks.ICE); // frozen over
					teleport(bot, a, 5, Arena.SURFACE, 0);
					for (int i = 0; i < 3; i++) {
						a.spawn(EntityType.COD, 15 + i % 2, 37, 3 + i);
					}
					give(bot, Items.IRON_SWORD, 1);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.COD, 1, Mode.NORMAL);
				}, Scenario.has(Items.COD, 1)),

				// What this checks is air under ice (it used to drown, or say there's no path at all);
				// catching fish under water is a matter of its own.
				new Scenario("W27", "刚凿开冰洞下到水里，抓冰下的鳕鱼", 3 * MINUTE, (a, bot, me) -> {
					a.fill(4, 36, -6, 18, 39, 6, Blocks.WATER);
					a.fill(4, 40, -6, 18, 40, 6, Blocks.ICE);
					a.set(5, 40, 0, Blocks.WATER); // the hole it just broke
					teleport(bot, a, 5, 39, 0);
					for (int i = 0; i < 3; i++) {
						a.spawn(EntityType.COD, 15 + i % 2, 37, 3 + i);
					}
					give(bot, Items.IRON_SWORD, 1);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.COD, 1, Mode.NORMAL);
				}, c -> c.logHas("找不到路") || c.logHas("找不到能走的路") ? Scenario.Outcome.fail("在冰下说找不到路")
						: c.bot().getInventory().countItem(Items.COD) >= 1 ? Scenario.Outcome.pass("抓到了鱼")
						: c.ended() ? Scenario.Outcome.pass("没淹死、没说找不到路（鱼没抓到）") : null),

				new Scenario("W28", "冰下没气了，站得偏离洞口中心也能浮上去", MINUTE, (a, bot, me) -> {
					a.fill(4, 36, -6, 18, 39, 6, Blocks.WATER);
					a.fill(4, 40, -6, 18, 40, 6, Blocks.ICE);
					a.set(5, 40, 0, Blocks.WATER); // the one hole
					// In the hole's column, but 0.35 off its centre: the body pokes under the ice beside it.
					bot.teleportTo(a.level, a.origin.getX() + 5.85, 38, a.origin.getZ() + 0.5, Set.of(), 0, 0, true);
					// Water breathing: its own breathing layer stays out of it (otherwise, running low,
					// it plans a way up and that's what gets tested).
					bot.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WATER_BREATHING, 20 * 600));
					give(bot, Items.BREAD, 16);
					return null;
				}, c -> {
					// Drives the last-resort "straight up" ascent itself, every tick, as the breathing
					// layer does when the planned way up stalls (with low air it would plan a route
					// instead, and this part wouldn't be tested).
					return c.bot().movement().ascendStep() ? Scenario.Outcome.pass("浮上来了") : null;
				})
		);
	}

	/**
	 * A stone cliff 20 high (x 10..20, top at y=60) with a spring in its face: one source block in a
	 * notch at y=58 pours out and down the face as a single sheet, one block thick, into a puddle.
	 */
	private static void cliffWithWaterfall(Arena a) {
		a.fill(10, 41, -6, 20, 60, 6, Blocks.STONE);
		a.set(10, 58, 0, Blocks.WATER);
	}

	/** Result of the plan-only scene: [0] failure reason or null, [1] description. */
	private static final String[] PLAN_RESULT = new String[2];

	private static void give(BotPlayer bot, Item item, int count) {
		bot.getInventory().add(new ItemStack(item, count));
	}

	private static void teleport(BotPlayer bot, Arena a, int x, int y, int z) {
		bot.teleportTo(a.level, a.origin.getX() + x + 0.5, y, a.origin.getZ() + z + 0.5, Set.of(), 0, 0, true);
	}
}
