package com.vale.helperbot.test;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.knowledge.CraftPlanner;
import com.vale.helperbot.path.Mode;
import com.vale.helperbot.task.AcquireTask;
import com.vale.helperbot.task.ComeTask;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
				// First in the list: after other scenes, in a sped-up run, a new chunk's entity data may never
				// turn up, and a furnace won't run without it. Not seen in the game: furnaces work there.
				new Scenario("W48", "在 30 格宽的水底要烧东西（玩家报告）：游到岸上再放熔炉", 5 * MINUTE, (a, bot, me) -> {
					a.fill(-30, 30, -30, 30, 40, 30, Blocks.WATER);
					teleport(bot, a, 0, 30, 0);
					give(bot, Items.FURNACE, 1);
					give(bot, Items.RAW_IRON, 1);
					give(bot, Items.COAL, 1);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.IRON_INGOT, 1, Mode.AGGRESSIVE);
				}, Scenario.has(Items.IRON_INGOT, 1, c -> !c.logHas("干地方"), "说找不到干地方了")),

				new Scenario("W49", "加载范围内全是水（超过视距）：先往有陆地的地方走，再放熔炉", 6 * MINUTE, (a, bot, me) -> {
					a.fill(-130, 40, -130, 130, 40, 130, Blocks.WATER);
					teleport(bot, a, 0, 40, 0);
					give(bot, Items.FURNACE, 1);
					give(bot, Items.RAW_IRON, 1);
					give(bot, Items.COAL, 1);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.IRON_INGOT, 1, Mode.AGGRESSIVE);
				}, Scenario.has(Items.IRON_INGOT, 1)),

				new Scenario("S1", "跨过沟走到你身边", 2 * MINUTE, (a, bot, me) -> {
					a.fill(10, 20, -6, 13, 40, 6, Blocks.AIR); // a ravine 4 wide, 20 deep
					me.teleportTo(a.level, a.origin.getX() + 25.5, Arena.SURFACE, a.origin.getZ() + 0.5, Set.of(), 0, 0, true);
					give(bot, Items.BREAD, 16);
					return new ComeTask(me, Mode.NORMAL);
				}, c -> c.bot().position().distanceTo(c.arena().at(25, Arena.SURFACE, 0).getBottomCenter()) <= 3
						? Scenario.Outcome.pass("到了") : c.ended() ? Scenario.Outcome.fail("任务结束了但没到") : null),

				// The same ravine in the other two modes (S1 is normal): safe must not jump down, it
				// bridges or goes round; aggressive may do what it likes. Both must get there.
				ravine("S1s", "安全模式：跨过沟走到你身边", Mode.SAFE),
				ravine("S1a", "激进模式：跨过沟走到你身边", Mode.AGGRESSIVE),

				new Scenario("C35s", "安全模式：空背包做石镐", 10 * MINUTE, (a, bot, me) -> {
					a.tree(6, 6, 5);
					a.tree(-7, 5, 5);
					a.tree(5, -8, 6);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.STONE_PICKAXE, 1, Mode.SAFE);
				}, Scenario.has(Items.STONE_PICKAXE, 1)),

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

				new Scenario("X1", "挖圆石时被两只尸壳打（打完接着挖，不来回抢控制权）", 6 * MINUTE, (a, bot, me) -> {
					for (int i = 0; i < 2; i++) {
						net.minecraft.world.entity.monster.zombie.Husk husk = a.spawn(EntityType.HUSK, 6 + i, Arena.SURFACE, 3);
						if (husk != null) {
							husk.setTarget(bot);
						}
					}
					give(bot, Items.STONE_PICKAXE, 1);
					give(bot, Items.IRON_SWORD, 1);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.COBBLESTONE, 16, Mode.NORMAL);
				}, Scenario.has(Items.COBBLESTONE, 16, c -> c.logCount("控制权") <= 100, "控制权换手超过 100 次（各层在来回抢）")),

				new Scenario("X2", "饿着肚子挖圆石（边干边吃，不来回抢控制权）", 4 * MINUTE, (a, bot, me) -> {
					bot.getFoodData().setFoodLevel(4);
					give(bot, Items.STONE_PICKAXE, 1);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.COBBLESTONE, 8, Mode.NORMAL);
				}, Scenario.has(Items.COBBLESTONE, 8, c -> c.logHas("吃 ") && c.logCount("控制权") <= 60,
						"没吃东西，或者控制权换手超过 60 次")),

				new Scenario("T22", "挖 10 个钻石：一处 4 个的矿堆要挖干净再去下一处", 8 * MINUTE, (a, bot, me) -> {
					a.fill(5, -50, 5, 6, -50, 6, Blocks.DEEPSLATE_DIAMOND_ORE); // 4, the nearer vein
					a.fill(25, -50, 25, 28, -49, 25, Blocks.DEEPSLATE_DIAMOND_ORE); // 8, further off
					give(bot, Items.IRON_PICKAXE, 1);
					give(bot, Items.COBBLESTONE, 64);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.DIAMOND, 10, Mode.NORMAL);
				}, c -> {
					int leftNear = 0;
					for (BlockPos p : BlockPos.betweenClosed(c.arena().at(5, -50, 5), c.arena().at(6, -50, 6))) {
						if (c.arena().level.getBlockState(p).is(Blocks.DEEPSLATE_DIAMOND_ORE)) {
							leftNear++;
						}
					}
					if (c.bot().getInventory().countItem(Items.DIAMOND) >= 10) {
						return leftNear == 0 ? Scenario.Outcome.pass("拿到 10 个，近处矿堆挖干净了")
								: Scenario.Outcome.fail("拿到 10 个，但近处矿堆还剩 " + leftNear + " 个");
					}
					return c.ended() ? Scenario.Outcome.fail("任务结束了，只有 " + c.bot().getInventory().countItem(Items.DIAMOND)
							+ " 个；近处矿堆剩 " + leftNear + " 个") : null;
				}),

				new Scenario("V1", "矿洞里 5 格外的钻石：直接挖过去，不先上地面", 3 * MINUTE, (a, bot, me) -> {
					a.fill(-1, 0, -1, 1, 2, 1, Blocks.AIR);
					teleport(bot, a, 0, 0, 0);
					a.set(5, 0, 0, Blocks.DIAMOND_ORE);
					give(bot, Items.IRON_PICKAXE, 1);
					give(bot, Items.COBBLESTONE, 64);
					give(bot, Items.BREAD, 16);
					MAX_Y[0] = Integer.MIN_VALUE;
					return new AcquireTask(me, Items.DIAMOND, 1, Mode.NORMAL);
				}, c -> {
					MAX_Y[0] = Math.max(MAX_Y[0], c.bot().getBlockY());
					if (MAX_Y[0] > 10) {
						return Scenario.Outcome.fail("往上爬到了 y=" + MAX_Y[0] + "（钻石就在 5 格外）");
					}
					return c.bot().getInventory().countItem(Items.DIAMOND) >= 1 ? Scenario.Outcome.pass("直接挖过去拿到了，最高 y=" + MAX_Y[0])
							: c.ended() ? Scenario.Outcome.fail("任务结束了没拿到") : null;
				}),

				new Scenario("V2", "矿洞里（y=0）回到 300 格外的你身边：先上地面跑，不一路挖隧道", 6 * MINUTE, (a, bot, me) -> {
					a.fill(-1, 0, -1, 1, 2, 1, Blocks.AIR);
					teleport(bot, a, 0, 0, 0);
					me.teleportTo(a.level, a.origin.getX() + 300.5, Arena.SURFACE, a.origin.getZ() + 0.5, Set.of(), 0, 0, true);
					give(bot, Items.IRON_PICKAXE, 1);
					give(bot, Items.COBBLESTONE, 64);
					give(bot, Items.BREAD, 16);
					MAX_Y[0] = Integer.MIN_VALUE;
					return new ComeTask(me, Mode.NORMAL);
				}, c -> {
					MAX_Y[0] = Math.max(MAX_Y[0], c.bot().getBlockY());
					int used = 999;
					for (int i = 0; i < c.bot().getInventory().getContainerSize(); i++) {
						if (c.bot().getInventory().getItem(i).is(Items.IRON_PICKAXE)) {
							used = c.bot().getInventory().getItem(i).getDamageValue();
						}
					}
					if (c.bot().position().distanceTo(c.arena().at(300, Arena.SURFACE, 0).getBottomCenter()) <= 3) {
						return MAX_Y[0] >= Arena.SURFACE - 1 && used < 200
								? Scenario.Outcome.pass("到了，上过地面，镐用了 " + used + " 点耐久")
								: Scenario.Outcome.fail("到了，但最高只到 y=" + MAX_Y[0] + "，镐用了 " + used + " 点耐久（一路挖过去的）");
					}
					return c.ended() ? Scenario.Outcome.fail("任务结束了但没到（最高 y=" + MAX_Y[0] + "，镐用了 " + used + "）") : null;
				}),

				new Scenario("Z1", "穿过保护区块（里面有尸壳）到你身边：灵魂状态，不被盯上", 3 * MINUTE, (a, bot, me) -> {
					for (int x = 16; x <= 48; x += 16) {
						for (int z = -16; z <= 16; z += 16) {
							protect(a, x, z);
						}
					}
					ZONE_HUSK[0] = a.spawn(EntityType.HUSK, 30, Arena.SURFACE, 4);
					// 1.21.11 husks may come armed (a spear) and with company (a camel, a parched): this
					// scene is about the protected chunk, so just a plain husk, nothing else around.
					if (ZONE_HUSK[0] instanceof net.minecraft.world.entity.Mob husk) {
						husk.stopRiding();
						husk.ejectPassengers();
						husk.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
						for (net.minecraft.world.entity.Mob other : a.level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
								new net.minecraft.world.phys.AABB(a.at(-20, 0, -40).getCenter(), a.at(100, 80, 40).getCenter()), m -> m != husk)) {
							other.discard();
						}
					}
					ZONE_TARGETED[0] = 0;
					me.teleportTo(a.level, a.origin.getX() + 70.5, Arena.SURFACE, a.origin.getZ() + 0.5, Set.of(), 0, 0, true);
					// Outside the protected chunks it fights back as usual (a husk may come with company).
					give(bot, Items.IRON_SWORD, 1);
					give(bot, Items.BREAD, 16);
					return new ComeTask(me, Mode.NORMAL);
				}, c -> {
					boolean inZone = com.vale.helperbot.guard.ProtectionZones.isProtected(c.arena().level, c.bot().blockPosition());
					if (inZone && ZONE_HUSK[0] instanceof net.minecraft.world.entity.Mob m && m.isAlive() && m.getTarget() == c.bot()) {
						ZONE_TARGETED[0]++;
					}
					if (ZONE_TARGETED[0] > 20) {
						return Scenario.Outcome.fail("在保护区块里还被尸壳盯着");
					}
					if (c.bot().position().distanceTo(c.arena().at(70, Arena.SURFACE, 0).getBottomCenter()) <= 3) {
						return c.logHas("进入保护区块") && c.logHas("离开保护区块") ? Scenario.Outcome.pass("穿过去了，进出都切换了灵魂状态")
								: Scenario.Outcome.fail("到了，但日志里没看到进出灵魂状态");
					}
					return c.ended() ? Scenario.Outcome.fail("任务结束了但没到") : null;
				}),

				new Scenario("Z2", "要原木：近处的树在保护区块里，不能砍；去砍远处的", 4 * MINUTE, (a, bot, me) -> {
					protect(a, 8, 0);
					a.tree(8, 0, 5); // the near tree, protected
					a.tree(40, 8, 5);
					ZONE_COUNT[0] = countLogs(a, 8, 0);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.OAK_LOG, 3, Mode.NORMAL);
				}, c -> {
					if (countLogs(c.arena(), 8, 0) != ZONE_COUNT[0]) {
						return Scenario.Outcome.fail("砍了保护区块里的树");
					}
					return c.bot().getInventory().countItem(Items.OAK_LOG) >= 3 ? Scenario.Outcome.pass("砍的是外面的树")
							: c.ended() ? Scenario.Outcome.fail("任务结束了没拿到") : null;
				}),

				new Scenario("Z3", "站在保护区块里要圆石：走出去挖，里面一块不动", 4 * MINUTE, (a, bot, me) -> {
					protect(a, 0, 0);
					give(bot, Items.STONE_PICKAXE, 1);
					give(bot, Items.BREAD, 16);
					ZONE_COUNT[0] = countSolid(a, 0, 0);
					return new AcquireTask(me, Items.COBBLESTONE, 4, Mode.NORMAL);
				}, c -> {
					if (countSolid(c.arena(), 0, 0) != ZONE_COUNT[0]) {
						return Scenario.Outcome.fail("动了保护区块里的方块");
					}
					return c.bot().getInventory().countItem(Items.COBBLESTONE) >= 4 ? Scenario.Outcome.pass("在外面挖的，里面没动")
							: c.ended() ? Scenario.Outcome.fail("任务结束了没拿到") : null;
				}),

				new Scenario("Z4", "保护区块里站上压力板、走进绊线：都不触发（区块外的压力板作对照，必须触发）", 30 * 20, (a, bot, me) -> {
					protect(a, 8, 0);
					a.set(8, Arena.SURFACE, 0, Blocks.STONE_PRESSURE_PLATE);
					a.set(8, Arena.SURFACE, 3, Blocks.TRIPWIRE);
					a.set(40, Arena.SURFACE, 0, Blocks.STONE_PRESSURE_PLATE); // outside: the control
					teleport(bot, a, 8, Arena.SURFACE, 0);
					give(bot, Items.BREAD, 16);
					ZONE_COUNT[0] = 0;
					return null;
				}, c -> {
					Arena a = c.arena();
					int t = ++ZONE_COUNT[0];
					boolean plate = a.level.getBlockState(a.at(8, Arena.SURFACE, 0)).getValue(net.minecraft.world.level.block.PressurePlateBlock.POWERED);
					boolean wire = a.level.getBlockState(a.at(8, Arena.SURFACE, 3)).getValue(net.minecraft.world.level.block.TripWireBlock.POWERED);
					if (plate || wire) {
						return Scenario.Outcome.fail("在保护区块里触发了" + (plate ? "压力板" : "绊线"));
					}
					if (t == 60) {
						teleport(c.bot(), a, 8, Arena.SURFACE, 3); // into the tripwire
					} else if (t == 120) {
						teleport(c.bot(), a, 40, Arena.SURFACE, 0); // onto the plate outside
					} else if (t > 140) {
						boolean control = a.level.getBlockState(a.at(40, Arena.SURFACE, 0)).getValue(net.minecraft.world.level.block.PressurePlateBlock.POWERED);
						return control ? Scenario.Outcome.pass("区块里压力板、绊线都没触发；区块外的压力板触发了（对照正常）")
								: Scenario.Outcome.fail("对照失败：区块外的压力板也没触发，这个测试测不出东西");
					}
					return null;
				}),

				new Scenario("R1", "临时限制黑曜石：被关在黑曜石盒子里也不挖（有钻石镐）", 2 * MINUTE, (a, bot, me) -> {
					restrictObsidian(true);
					a.fill(-2, Arena.SURFACE - 1, -2, 2, Arena.SURFACE + 3, 2, Blocks.OBSIDIAN);
					a.fill(-1, Arena.SURFACE, -1, 1, Arena.SURFACE + 2, 1, Blocks.AIR);
					teleport(bot, a, 0, Arena.SURFACE, 0);
					me.teleportTo(a.level, a.origin.getX() + 12.5, Arena.SURFACE, a.origin.getZ() + 0.5, Set.of(), 0, 0, true);
					give(bot, Items.DIAMOND_PICKAXE, 1);
					give(bot, Items.BREAD, 16);
					ZONE_COUNT[0] = countObsidian(a);
					return new ComeTask(me, Mode.NORMAL);
				}, c -> countObsidian(c.arena()) != ZONE_COUNT[0] ? Scenario.Outcome.fail("挖了黑曜石")
						: c.ended() ? Scenario.Outcome.pass("一块黑曜石没挖，说出不去") : null),

				new Scenario("R2", "临时限制黑曜石：要附魔台，规划里说明是被限制了（只规划）", 20, (a, bot, me) -> {
					restrictObsidian(true);
					give(bot, Items.DIAMOND, 2);
					give(bot, Items.BOOK, 1);
					CraftPlanner planner = new CraftPlanner(a.level, bot, bot.getInventory(), new HashSet<>());
					CraftPlanner.Plan plan = planner.plan(Items.ENCHANTING_TABLE, 1, CraftPlanner.counts(bot.getInventory()));
					List<String> problems = plan.problems().stream().map(x -> x.getString()).toList();
					PLAN_RESULT[0] = plan.possible() ? "居然规划出来了：" + plan.actions()
							: problems.stream().noneMatch(p -> p.contains("临时限制")) ? "失败原因里没提临时限制：" + problems : null;
					PLAN_RESULT[1] = "做不了，原因里有：" + problems.stream().filter(p -> p.contains("临时限制")).findFirst().orElse("");
					return null;
				}, c -> PLAN_RESULT[0] == null ? Scenario.Outcome.pass(PLAN_RESULT[1]) : Scenario.Outcome.fail(PLAN_RESULT[0])),

				new Scenario("R3", "临时限制传送门：直接站进末地传送门，也不会被传走", 10 * 20, (a, bot, me) -> {
					com.vale.helperbot.guard.Restrictions.setNoPortals(true);
					a.set(5, Arena.SURFACE - 1, 0, Blocks.END_PORTAL);
					bot.teleportTo(a.level, a.origin.getX() + 5.5, Arena.SURFACE - 0.6, a.origin.getZ() + 0.5, Set.of(), 0, 0, true);
					give(bot, Items.BREAD, 16);
					return null;
				}, c -> c.bot().level().dimension() != net.minecraft.world.level.Level.OVERWORLD
						? Scenario.Outcome.fail("被传送到了 " + c.bot().level().dimension().identifier())
						: c.ticks() >= 8 * 20 ? Scenario.Outcome.pass("在传送门里待了 8 秒，还在主世界") : null),

				new Scenario("P1", "遥控地图用的指令：批量保护、查询；普通玩家不能取消保护，服务器后台可以按 ID 取消", 20, (a, bot, me) -> {
					var server = a.level.getServer();
					net.minecraft.world.level.ChunkPos base = new net.minecraft.world.level.ChunkPos(a.at(0, 0, 0));
					int bx = base.x + 3, bz = base.z + 3;
					server.getCommands().performPrefixedCommand(me.createCommandSourceStack(),
							"bot protect chunks " + bx + "," + bz + " " + (bx + 1) + "," + bz + " " + bx + "," + (bz + 1));
					int id = com.vale.helperbot.guard.ProtectionZones.idOf(a.level, new net.minecraft.world.level.ChunkPos(bx, bz));
					boolean three = id != 0
							&& com.vale.helperbot.guard.ProtectionZones.isProtected(a.level, new net.minecraft.world.level.ChunkPos(bx + 1, bz))
							&& com.vale.helperbot.guard.ProtectionZones.isProtected(a.level, new net.minecraft.world.level.ChunkPos(bx, bz + 1));
					// The query answers with the marked line.
					StringBuilder reply = new StringBuilder();
					server.getCommands().performPrefixedCommand(me.createCommandSourceStack()
									.withSource(new net.minecraft.commands.CommandSource() {
										public void sendSystemMessage(net.minecraft.network.chat.Component message) {
											reply.append(message.getString());
										}

										public boolean acceptsSuccess() {
											return true;
										}

										public boolean acceptsFailure() {
											return true;
										}

										public boolean shouldInformAdmins() {
											return false;
										}
									}),
							"bot protect query " + bx + " " + bz + " 2");
					boolean answered = reply.toString().startsWith(com.vale.helperbot.command.BotCommands.ZONES_MARKER)
							&& reply.toString().contains(id + "," + bx + "," + bz + ";");
					// A player (no operator rights) may not take protection off; the console may.
					server.getCommands().performPrefixedCommand(me.createCommandSourceStack(), "bot protect remove " + id);
					boolean stillAfterPlayer = com.vale.helperbot.guard.ProtectionZones.idOf(a.level, new net.minecraft.world.level.ChunkPos(bx, bz)) == id;
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "bot protect remove " + id);
					boolean goneAfterConsole = !com.vale.helperbot.guard.ProtectionZones.isProtected(a.level, new net.minecraft.world.level.ChunkPos(bx, bz));
					PLAN_RESULT[0] = !three ? "批量保护没生效" : !answered ? "查询的回复不对：" + reply
							: !stillAfterPlayer ? "普通玩家取消了保护" : !goneAfterConsole ? "后台按 ID 取消没生效" : null;
					PLAN_RESULT[1] = "批量保护 3 个、查询回复正确、玩家取消被拒、后台按 ID " + id + " 取消成功";
					return null;
				}, c -> PLAN_RESULT[0] == null ? Scenario.Outcome.pass(PLAN_RESULT[1]) : Scenario.Outcome.fail(PLAN_RESULT[0])),

				new Scenario("Z5", "在保护区块里召唤（或药水效果变化）：不会变隐身", 6 * 20, (a, bot, me) -> {
					protect(a, 0, 0);
					// Vanilla works out "invisible?" on joining and whenever effects change: a short effect
					// coming and going inside the protected chunk sets that off, like being summoned there.
					bot.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SPEED, 40));
					give(bot, Items.BREAD, 16);
					return null;
				}, c -> c.bot().isInvisible() ? Scenario.Outcome.fail("在保护区块里变成隐身了（只看得见装备）")
						: c.ticks() >= 5 * 20 ? Scenario.Outcome.pass("效果加上、消失后都没隐身") : null),

				new Scenario("N1", "叫机器人名字的玩家/假人会被处死；同名的生物（命名牌）不受影响", 8 * 20, (a, bot, me) -> {
					var server = a.level.getServer();
					// An impostor in the player list (like a Carpet fake player): "helperbot", another case.
					var profile = new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "helperbot");
					var impostor = new net.minecraft.server.level.ServerPlayer(server, a.level, profile,
							net.minecraft.server.level.ClientInformation.createDefault());
					server.getPlayerList().placeNewPlayer(new com.vale.helperbot.bot.FakeConnection(), impostor,
							new net.minecraft.server.network.CommonListenerCookie(profile, 0, impostor.clientInformation(), false));
					impostor.teleportTo(a.level, a.origin.getX() + 5.5, Arena.SURFACE, a.origin.getZ() + 5.5, Set.of(), 0, 0, true);
					impostor.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
					impostor.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
					NAME_TEST[0] = impostor;
					// A mob with the same name tag: must be left alone.
					var golem = a.spawn(EntityType.IRON_GOLEM, -5, Arena.SURFACE, -5);
					golem.setCustomName(Component.literal("HelperBot"));
					NAME_TEST[1] = golem;
					return null;
				}, c -> {
					var impostor = (net.minecraft.server.level.ServerPlayer) NAME_TEST[0];
					var golem = (net.minecraft.world.entity.LivingEntity) NAME_TEST[1];
					if (golem.getHealth() < golem.getMaxHealth()) {
						return Scenario.Outcome.fail("同名的铁傀儡（命名牌）也受了伤");
					}
					if (!c.bot().isAlive()) {
						return Scenario.Outcome.fail("真机器人也被打了");
					}
					if (!impostor.isAlive() || impostor.isDeadOrDying()) {
						var chest = impostor.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
						// Out of the player list, or the next scenes can't summon the bot ("name taken").
						c.arena().level.getServer().getPlayerList().remove(impostor);
						return Scenario.Outcome.pass("冒名假人死了（下界合金胸甲耐久没掉：" + (chest.isEmpty() ? "已掉落" : chest.getDamageValue() == 0) + "）；同名铁傀儡和真机器人都没事");
					}
					return c.ticks() >= 6 * 20 ? Scenario.Outcome.fail("冒名假人 6 秒还活着") : null;
				}),

				new Scenario("P2", "回到主人身边，途中主人死了重生到别处：跟到新位置（玩家报告）", MINUTE, (a, bot, me) -> {
					var server = a.level.getServer();
					var profile = new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Owner");
					var owner = new net.minecraft.server.level.ServerPlayer(server, a.level, profile,
							net.minecraft.server.level.ClientInformation.createDefault());
					server.getPlayerList().placeNewPlayer(new com.vale.helperbot.bot.FakeConnection(), owner,
							new net.minecraft.server.network.CommonListenerCookie(profile, 0, owner.clientInformation(), false));
					owner.setPos(a.origin.getX() + 10.5, Arena.SURFACE, a.origin.getZ() + 0.5); // not a teleport: no client to answer it
					teleport(bot, a, 0, Arena.SURFACE, 0);
					NAME_TEST[0] = owner;
					NAME_TEST[1] = null;
					return new ComeTask(owner, Mode.NORMAL);
				}, c -> {
					var players = c.arena().level.getServer().getPlayerList();
					if (NAME_TEST[1] == null && c.ticks() >= 20) {
						// What dying does: a new player object, the old one left where it was.
						var fresh = players.respawn((net.minecraft.server.level.ServerPlayer) NAME_TEST[0], false,
								net.minecraft.world.entity.Entity.RemovalReason.KILLED);
						NAME_TEST[1] = fresh;
					}
					if (NAME_TEST[1] == null) {
						return null;
					}
					var fresh = (net.minecraft.server.level.ServerPlayer) NAME_TEST[1];
					// Held where it "respawned", away from the world spawn: the old object ends up at the
					// spawn, so the old code heads the other way (a fake player has no client for teleports).
					fresh.setPos(c.arena().origin.getX() + 25.5, Arena.SURFACE, c.arena().origin.getZ() + 0.5);
					boolean there = c.bot().distanceTo(fresh) <= 4;
					if (there || c.ended()) {
						players.remove(fresh); // out of the list, like N1
						return there ? Scenario.Outcome.pass("跟到了重生后的位置")
								: Scenario.Outcome.fail("任务结束了，没到新位置，最后 x=" + (c.bot().getBlockX() - c.arena().origin.getX()));
					}
					if (c.ticks() >= 45 * 20) {
						players.remove(fresh);
						return Scenario.Outcome.fail("45 秒没到新位置，最后 x=" + (c.bot().getBlockX() - c.arena().origin.getX()));
					}
					return null;
				}),

				new Scenario("ST1", "地窖：三排台阶下去（每级上方 3 格空，刚够人走），从地窖走上来到你身边", 3 * MINUTE, (a, bot, me) -> {
					cellar(a);
					teleport(bot, a, 9, 34, 0);
					me.teleportTo(a.level, a.origin.getX() - 6.5, Arena.SURFACE, a.origin.getZ() + 0.5, Set.of(), 0, 0, true);
					give(bot, Items.BREAD, 16);
					return new ComeTask(me, Mode.NORMAL);
				}, c -> c.bot().position().distanceTo(c.arena().at(-7, Arena.SURFACE, 0).getBottomCenter()) <= 3
						? Scenario.Outcome.pass("走上来了") : c.ended() ? Scenario.Outcome.fail("任务结束了但没上来，最后在 y=" + c.bot().getBlockY()) : null),

				new Scenario("ST1p", "地窖在保护区块里（不能挖）：顺着台阶走上来到你身边", 3 * MINUTE, (a, bot, me) -> {
					cellar(a);
					for (int x = -8; x <= 14; x += 4) {
						for (int z = -4; z <= 4; z += 4) {
							protect(a, x, z);
						}
					}
					teleport(bot, a, 9, 34, 0);
					me.teleportTo(a.level, a.origin.getX() - 6.5, Arena.SURFACE, a.origin.getZ() + 0.5, Set.of(), 0, 0, true);
					give(bot, Items.BREAD, 16);
					return new ComeTask(me, Mode.NORMAL);
				}, c -> c.bot().position().distanceTo(c.arena().at(-7, Arena.SURFACE, 0).getBottomCenter()) <= 3
						? Scenario.Outcome.pass("走上来了") : c.ended() ? Scenario.Outcome.fail("任务结束了但没上来，最后在 y=" + c.bot().getBlockY()) : null),

				new Scenario("ST2", "地窖：从地面顺着台阶走下去到你身边", 3 * MINUTE, (a, bot, me) -> {
					cellar(a);
					teleport(bot, a, -6, Arena.SURFACE, 0);
					me.teleportTo(a.level, a.origin.getX() + 9.5, 34, a.origin.getZ() + 0.5, Set.of(), 0, 0, true);
					give(bot, Items.BREAD, 16);
					return new ComeTask(me, Mode.NORMAL);
				}, c -> c.bot().position().distanceTo(c.arena().at(9, 34, 0).getBottomCenter()) <= 3
						? Scenario.Outcome.pass("走下去了") : c.ended() ? Scenario.Outcome.fail("任务结束了但没下去") : null),

				new Scenario("C33", "白色地毯：做好的不拿去染色", 5 * MINUTE, (a, bot, me) -> {
					for (int i = 0; i < 4; i++) {
						// White: spawned sheep get random colours, and only white ones drop white wool.
						a.spawn(EntityType.SHEEP, 6 + i, Arena.SURFACE, 4).setColor(net.minecraft.world.item.DyeColor.WHITE);
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
					// The move giving up for lack of a path, not one search coming up empty (that's routine
					// when short of air: it goes up for a breath next).
				}, c -> c.logHas("找不到能走的路") || c.logHas("换气的地方里找不到路") ? Scenario.Outcome.fail("在冰下说找不到路（移动放弃了）")
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
				}),

				new Scenario("D1", "采牡丹（两格高的花：先够到的是上半截）", 2 * MINUTE, (a, bot, me) -> {
					// A step one block up, so the flowers' top halves are level with its feet: the
					// nearest part of each is the top. The top's own loot is empty; breaking it takes
					// the bottom with it, which drops the flower.
					a.fill(2, 41, -1, 4, 41, 1, Blocks.STONE);
					for (int i = 0; i < 2; i++) {
						net.minecraft.world.level.block.state.BlockState lower = Blocks.PEONY.defaultBlockState();
						a.set(6, 41, i * 2 - 1, lower);
						a.set(6, 42, i * 2 - 1, lower.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
								net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
					}
					teleport(bot, a, 4, 42, 0);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.PEONY, 2, Mode.NORMAL);
				}, Scenario.has(Items.PEONY, 2, c -> !c.logHas("重新选工具"), "说手里的东西挖不出（拿上半截当没有掉落）")),

				new Scenario("Z6", "大保护区中间，要用工作台：走出去再放，不连败 3 次放弃", 5 * MINUTE, (a, bot, me) -> {
					zoneWithDeadEnds(a);
					teleport(bot, a, 3, 41, 0);
					give(bot, Items.CRAFTING_TABLE, 1);
					give(bot, Items.OAK_PLANKS, 3);
					give(bot, Items.STICK, 2);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.WOODEN_PICKAXE, 1, Mode.NORMAL);
				}, Scenario.has(Items.WOODEN_PICKAXE, 1)),

				new Scenario("Z7", "大保护区中间，树在区外，脚下的群系就长树：走出去才看得到", 5 * MINUTE, (a, bot, me) -> {
					zoneWithDeadEnds(a);
					a.tree(110, 0, 5);
					a.tree(-110, 0, 5);
					a.tree(0, 110, 5);
					a.tree(0, -110, 5);
					teleport(bot, a, 3, 41, 0);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.OAK_LOG, 1, Mode.NORMAL);
				}, Scenario.has(Items.OAK_LOG, 1)),

				new Scenario("Z8", "回字形保护区：旁边的洞不算出去，要走到真外面", 5 * MINUTE, (a, bot, me) -> {
					for (int cx = -5; cx <= 5; cx++) {
						for (int cz = -5; cz <= 5; cz++) {
							if (cx != 2 || cz != 0) {
								protect(a, cx * 16, cz * 16); // the chunk two along is a hole
							}
						}
					}
					a.tree(100, 0, 5);
					a.tree(-100, 0, 5);
					teleport(bot, a, 3, 41, 0);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.OAK_LOG, 1, Mode.NORMAL);
				}, Scenario.has(Items.OAK_LOG, 1)),

				new Scenario("Z9", "凹字形保护区，从缺口里面穿过到对面：不原路进出来回", 5 * MINUTE, (a, bot, me) -> {
					for (int cx = -4; cx <= 4; cx++) {
						for (int cz = -5; cz <= 5; cz++) {
							if (cx >= -1 || Math.abs(cz) > 1) {
								protect(a, cx * 16, cz * 16); // open to the west, closed to the east
							}
						}
					}
					teleport(bot, a, -30, 41, 0);
					me.setPos(a.origin.getX() + 110.5, Arena.SURFACE, a.origin.getZ() + 0.5); // not a teleport: no client to answer it
					return new ComeTask(me, Mode.NORMAL);
				}, c -> c.ended() ? (c.bot().getX() - c.arena().origin.getX() > 90 ? Scenario.Outcome.pass("穿过去了")
						: Scenario.Outcome.fail("没到")) : null),

				new Scenario("Z10", "保护区比视距大得多（中心到边 200 多格）：一段一段走出去", 6 * MINUTE, (a, bot, me) -> {
					// These tests see 112 blocks out: the first searches can't reach the edge at all.
					for (int cx = -13; cx <= 13; cx++) {
						for (int cz = -13; cz <= 13; cz++) {
							protect(a, cx * 16, cz * 16);
						}
					}
					a.fill(60, 41, -30, 60, 44, 30, Blocks.STONE); // in the way of the shortest way out, east
					teleport(bot, a, 3, 41, 0);
					give(bot, Items.CRAFTING_TABLE, 1);
					give(bot, Items.OAK_PLANKS, 3);
					give(bot, Items.STICK, 2);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.WOODEN_PICKAXE, 1, Mode.NORMAL);
				}, Scenario.has(Items.WOODEN_PICKAXE, 1)),

				new Scenario("Z11", "大保护区中间打猎，牛在区外：走出去打", 5 * MINUTE, (a, bot, me) -> {
					zoneWithDeadEnds(a);
					for (int i = 0; i < 3; i++) {
						a.spawn(EntityType.COW, -118, 41, -2 + i * 2);
						a.spawn(EntityType.COW, 118, 41, -2 + i * 2);
						a.spawn(EntityType.COW, -2 + i * 2, 41, 118);
						a.spawn(EntityType.COW, -2 + i * 2, 41, -118);
					}
					teleport(bot, a, 3, 41, 0);
					give(bot, Items.IRON_SWORD, 1);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.BEEF, 1, Mode.NORMAL);
				}, Scenario.has(Items.BEEF, 1)),

				new Scenario("Z12", "大保护区中间打水，水在区外：走出去打", 5 * MINUTE, (a, bot, me) -> {
					zoneWithDeadEnds(a);
					a.fill(-120, 40, -3, -115, 40, 3, Blocks.WATER);
					teleport(bot, a, 3, 41, 0);
					give(bot, Items.BUCKET, 1);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.WATER_BUCKET, 1, Mode.NORMAL);
				}, Scenario.has(Items.WATER_BUCKET, 1)),

				new Scenario("W45", "海带在岩缝里（胳膊够得着，但看不到）：确定到不了就换旁边的，不来回下潜", 4 * MINUTE, (a, bot, me) -> {
					kelpInACrack(a);
					a.set(18, 22, 2, Blocks.KELP);
					teleport(bot, a, 2, 41, 0);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.KELP, 1, Mode.AGGRESSIVE);
				}, Scenario.has(Items.KELP, 1, c -> c.logCount("该换气了") <= 1, "来回下潜了")),

				new Scenario("W46", "同上但只有岩缝里这一根：说明到不了，不无限循环", 4 * MINUTE, (a, bot, me) -> {
					kelpInACrack(a);
					teleport(bot, a, 2, 41, 0);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.KELP, 1, Mode.AGGRESSIVE);
				}, c -> c.logCount("该换气了") > 2 ? Scenario.Outcome.fail("来回下潜了")
						: c.ended() ? Scenario.Outcome.pass("结束了：" + c.bot().lastTaskStatus()) : null),

				new Scenario("W47", "决策测试（不走路）：把 2026-11-04 海带日志的气量和位置灌进去，反复决策，不选同一根", MINUTE, (a, bot, me) -> {
					kelpInACrack(a);
					a.set(18, 22, 2, Blocks.KELP);
					return null;
				}, Scenarios::decideAgainAndAgain),

				new Scenario("E1", "脚下的群系就长树，树在 250 格外：走出去找，不说“这里已经是这个群系”就放弃", 6 * MINUTE, (a, bot, me) -> {
					a.tree(250, 0, 5);
					a.tree(-250, 0, 5);
					a.tree(0, 250, 5);
					a.tree(0, -250, 5);
					teleport(bot, a, 3, 41, 0);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.OAK_LOG, 1, Mode.NORMAL);
				}, Scenario.has(Items.OAK_LOG, 1)),

				new Scenario("E2", "牛在 300 格外：探索找到，一圈圈往外扩", 12 * MINUTE, (a, bot, me) -> {
					for (int i = 0; i < 2; i++) {
						a.spawn(EntityType.COW, 300, 41, i);
						a.spawn(EntityType.COW, -300, 41, i);
						a.spawn(EntityType.COW, i, 41, 300);
						a.spawn(EntityType.COW, i, 41, -300);
					}
					teleport(bot, a, 3, 41, 0);
					give(bot, Items.IRON_SWORD, 1);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.BEEF, 1, Mode.NORMAL);
				}, Scenario.has(Items.BEEF, 1)),

				new Scenario("M9", "站在一排栅栏顶上（矿井的支撑架）：能走下来，不卡死", 2 * MINUTE, (a, bot, me) -> {
					// A line of fences with walls of stone beside them: no way off but along the top, or down the end.
					a.fill(3, 41, -3, 3, 41, 3, Blocks.OAK_FENCE);
					a.fill(2, 41, -3, 2, 43, 3, Blocks.STONE);
					a.fill(4, 41, -3, 4, 43, 3, Blocks.STONE);
					bot.teleportTo(a.level, a.origin.getX() + 3.5, 42.5, a.origin.getZ() + 0.5, Set.of(), 0, 0, true);
					me.setPos(a.origin.getX() + 3.5, Arena.SURFACE, a.origin.getZ() + 12.5); // not a teleport: no client to answer it
					return new ComeTask(me, Mode.NORMAL);
				}, c -> c.ended() ? (c.logHas("找不到路") ? Scenario.Outcome.fail("卡住了") : Scenario.Outcome.pass("走下来了")) : null),

				new Scenario("W43", "要 4 块黏土：3 格深的水底有（浮在水面够得着），25 格外的岸上也有：去岸上", 4 * MINUTE, (a, bot, me) -> {
					// Reachable from the surface, head out: no dives, but floating it mines 5x slower.
					a.fill(6, 38, -3, 12, 40, 3, Blocks.WATER);
					a.fill(6, 37, -3, 12, 37, 3, Blocks.CLAY);
					a.fill(28, 40, -2, 32, 40, 2, Blocks.CLAY);
					teleport(bot, a, 3, 41, 0);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.CLAY_BALL, 16, Mode.NORMAL);
				}, Scenario.has(Items.CLAY_BALL, 16, c -> !c.bot().isInWater(), "下水了")),

				new Scenario("W22", "要 4 块黏土：近处 5 格深的水底有，25 格外的岸上也有：去岸上，不下水", 4 * MINUTE, (a, bot, me) -> {
					// By hand under water each block takes a whole dive; the walk is only paid once.
					a.fill(6, 36, -3, 12, 40, 3, Blocks.WATER);
					a.fill(6, 35, -3, 12, 35, 3, Blocks.CLAY);
					a.fill(28, 40, -2, 32, 40, 2, Blocks.CLAY);
					teleport(bot, a, 3, 41, 0);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.CLAY_BALL, 16, Mode.NORMAL);
				}, Scenario.has(Items.CLAY_BALL, 16, c -> !c.bot().movement().headUnderwater(), "下水了")),

				new Scenario("W37", "只要 1 块黏土：2 格深的水里就在眼前，岸上的在 50 格外：下水拿", 3 * MINUTE, (a, bot, me) -> {
					// The other side of W22: not "never in water", a dive that costs less than the walk.
					a.fill(5, 39, -2, 9, 40, 2, Blocks.WATER);
					a.fill(5, 38, -2, 9, 38, 2, Blocks.CLAY);
					a.fill(52, 40, -2, 56, 40, 2, Blocks.CLAY);
					teleport(bot, a, 3, 41, 0);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.CLAY_BALL, 4, Mode.NORMAL);
				}, Scenario.has(Items.CLAY_BALL, 4, c -> c.bot().blockPosition().getX() < c.arena().origin.getX() + 30, "走去了远处")),

				new Scenario("W38", "去对岸，水塘绕一下就过去了：走陆地，不游泳", 2 * MINUTE, (a, bot, me) -> {
					// Swimming takes about twice as long a block as walking: real time alone keeps it on land.
					a.fill(8, 38, -3, 13, 40, 3, Blocks.WATER);
					teleport(bot, a, 3, 41, 0);
					me.setPos(a.origin.getX() + 18.5, Arena.SURFACE, a.origin.getZ() + 0.5); // not a teleport: no client to answer it
					return new ComeTask(me, Mode.NORMAL);
				}, c -> c.bot().isInWater() ? Scenario.Outcome.fail("下水了")
						: c.ended() ? Scenario.Outcome.pass("绕过去了") : null),

				new Scenario("W39", "吃饱的时候 11 格深的水底空手挖黏土：疾跑游上来够快，一口气够", 4 * MINUTE, (a, bot, me) -> {
					// At the plain swimming speed the way up alone would take too much of the breath.
					a.fill(6, 30, -2, 10, 40, 2, Blocks.WATER);
					a.set(8, 29, 0, Blocks.CLAY);
					teleport(bot, a, 3, 41, 0);
					return new AcquireTask(me, Items.CLAY_BALL, 4, Mode.NORMAL);
				}, Scenario.has(Items.CLAY_BALL, 4)),

				new Scenario("W40", "同上但饿着（不能疾跑）：一口气不够，不下去（去别处找）", 2 * MINUTE, (a, bot, me) -> {
					a.fill(6, 30, -2, 10, 40, 2, Blocks.WATER);
					a.set(8, 29, 0, Blocks.CLAY);
					teleport(bot, a, 3, 41, 0);
					bot.getFoodData().setFoodLevel(6);
					bot.getFoodData().setSaturation(0);
					return new AcquireTask(me, Items.CLAY_BALL, 4, Mode.NORMAL);
				}, c -> c.bot().getY() < 34 ? Scenario.Outcome.fail("下去了")
						: c.ended() || c.ticks() > MINUTE ? Scenario.Outcome.pass("没下去") : null),

				new Scenario("W41", "站在结冰的池塘上，黏土在 4 格深的水底：凿开脚下的冰下去挖，从洞口上来", 4 * MINUTE, (a, bot, me) -> {
					// Nothing tells it to come back by the hole: the hole is simply the cheapest way up.
					a.fill(4, 36, -6, 14, 39, 6, Blocks.WATER);
					a.fill(4, 40, -6, 14, 40, 6, Blocks.ICE);
					a.set(9, 35, 0, Blocks.CLAY);
					teleport(bot, a, 9, Arena.SURFACE, 0);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.CLAY_BALL, 4, Mode.NORMAL);
				}, Scenario.has(Items.CLAY_BALL, 4, c -> c.bot().getHealth() > 12, "淹着了")),

				new Scenario("W42", "同上但是浮冰（挖开不变水，留个洞）", 4 * MINUTE, (a, bot, me) -> {
					a.fill(4, 36, -6, 14, 39, 6, Blocks.WATER);
					a.fill(4, 40, -6, 14, 40, 6, Blocks.PACKED_ICE);
					a.set(9, 35, 0, Blocks.CLAY);
					teleport(bot, a, 9, Arena.SURFACE, 0);
					give(bot, Items.IRON_PICKAXE, 1);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.CLAY_BALL, 4, Mode.NORMAL);
				}, Scenario.has(Items.CLAY_BALL, 4, c -> c.bot().getHealth() > 12, "淹着了")),

				new Scenario("W30", "6 格深的池底挖一块沙子（空手）：一口气下去站着挖完、捡起来，不来回换气", 3 * MINUTE, (a, bot, me) -> {
					// Floating where it's nearest, mining by hand takes 25x as long: too long for one
					// breath. Standing on the bottom it's 5x, and there's air for that and the pickup.
					a.fill(6, 35, -2, 10, 40, 2, Blocks.WATER);
					a.set(8, 34, 0, Blocks.SAND);
					teleport(bot, a, 3, 41, 0);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.SAND, 1, Mode.NORMAL);
				}, Scenario.has(Items.SAND, 1, c -> c.logCount("该换气了") == 0 && !c.logHas("剩下的气不够"),
						"去换了气（说明规划没把挖的时间算进去，或者游的时间算少了）")),

				new Scenario("W31", "6 格深的池底挖沙砾拿燧石（时运 III 铲子，必掉燧石）：挖完把燧石捡起来", 4 * MINUTE, (a, bot, me) -> {
					// Fortune III makes gravel always drop flint: every break leaves a flint to collect
					// under water (a player saw it swim almost up to one, turn back, and never go for it again).
					a.fill(6, 35, -3, 12, 40, 3, Blocks.WATER);
					a.set(7, 34, -2, Blocks.GRAVEL);
					a.set(9, 34, 1, Blocks.GRAVEL);
					a.set(11, 34, -1, Blocks.GRAVEL);
					teleport(bot, a, 3, 41, 0);
					ItemStack shovel = new ItemStack(Items.IRON_SHOVEL);
					shovel.enchant(a.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
							.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.FORTUNE), 3);
					bot.getInventory().add(shovel);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.FLINT, 3, Mode.NORMAL);
				}, Scenario.has(Items.FLINT, 3, c -> !c.logHas("不要了"), "放弃了一个燧石（没捡到）")),

				new Scenario("W32", "水底黏土上盖着一层泥土（玩家报告）：站到泥土上挖开，再挖黏土", 4 * MINUTE, (a, bot, me) -> {
					// 4 deep water (as the player had it) over a strip of clay, each covered by dirt: from
					// afloat, the dirt in the way and then the clay are too slow to mine on the air left;
					// standing on them, they aren't.
					a.fill(6, 37, -3, 12, 40, 3, Blocks.WATER);
					a.fill(7, 35, -1, 11, 35, 1, Blocks.CLAY);
					a.fill(7, 36, -1, 11, 36, 1, Blocks.DIRT);
					teleport(bot, a, 3, 41, 0);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.CLAY_BALL, 8, Mode.NORMAL);
				}, Scenario.has(Items.CLAY_BALL, 8)),

				new Scenario("W33", "同上但水深 7 格：一口气干不完\"挖泥土 + 挖黏土\"，分两趟下水", 5 * MINUTE, (a, bot, me) -> {
					// What's in the way is a job of its own: one dive for the dirt, the next for the clay.
					a.fill(6, 34, -3, 12, 40, 3, Blocks.WATER);
					a.fill(7, 32, -1, 11, 32, 1, Blocks.CLAY);
					a.fill(7, 33, -1, 11, 33, 1, Blocks.DIRT);
					teleport(bot, a, 3, 41, 0);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.CLAY_BALL, 8, Mode.NORMAL);
				}, Scenario.has(Items.CLAY_BALL, 8)),

				new Scenario("W34", "发光墨鱼在水下 20 格（玩家报告）：一口气潜不到，直接说明，不来回白跑", 2 * MINUTE, (a, bot, me) -> {
					a.fill(4, 16, -6, 14, 40, 6, Blocks.WATER);
					a.spawn(EntityType.GLOW_SQUID, 9, 18, 0);
					teleport(bot, a, 1, 41, 0);
					give(bot, Items.IRON_SWORD, 1);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.GLOW_INK_SAC, 1, Mode.NORMAL);
				}, c -> c.logCount("该换气了") > 1 ? Scenario.Outcome.fail("潜下去又上来（白跑）")
						: c.ended() && c.logHas("太深") ? Scenario.Outcome.pass("说明了太深、没去")
						: c.ended() ? Scenario.Outcome.fail("结束了，但没说是太深") : null),

				new Scenario("W35", "浅水里的墨鱼（水下 3 格）：照样能打到墨囊", 3 * MINUTE, (a, bot, me) -> {
					a.fill(4, 35, -6, 14, 40, 6, Blocks.WATER);
					for (int i = 0; i < 2; i++) {
						a.spawn(EntityType.SQUID, 8 + i, 37, 0);
					}
					teleport(bot, a, 1, 41, 0);
					give(bot, Items.IRON_SWORD, 1);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.INK_SAC, 1, Mode.NORMAL);
				}, Scenario.has(Items.INK_SAC, 1)),

				new Scenario("W36", "9 格深的水底挖一块沙子（铁锹）：用游泳姿态潜下去", 3 * MINUTE, (a, bot, me) -> {
					a.fill(4, 32, -4, 12, 40, 4, Blocks.WATER);
					a.set(8, 31, 0, Blocks.SAND);
					teleport(bot, a, 2, 41, 0);
					give(bot, Items.IRON_SHOVEL, 1);
					give(bot, Items.BREAD, 16);
					SWAM[0] = false;
					return new AcquireTask(me, Items.SAND, 1, Mode.NORMAL);
				}, c -> {
					SWAM[0] |= c.bot().isSwimming();
					return c.bot().getInventory().countItem(Items.SAND) >= 1
							? (SWAM[0] ? Scenario.Outcome.pass("拿到了，用了游泳姿态") : Scenario.Outcome.fail("拿到了，但一直没用游泳姿态"))
							: c.ended() ? Scenario.Outcome.fail("没拿到") : null;
				}),

				new Scenario("F1", "装一桶水：地面上，9 格外有个小水池（血不多）", 2 * MINUTE, (a, bot, me) -> {
					a.fill(10, 39, 4, 12, 40, 6, Blocks.WATER); // a pond 3x3, 2 deep, in the grass
					teleport(bot, a, 1, 41, 0);
					bot.setHealth(8);
					give(bot, Items.BUCKET, 1);
					give(bot, Items.STONE_PICKAXE, 1);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.WATER_BUCKET, 1, Mode.NORMAL);
				}, Scenario.has(Items.WATER_BUCKET, 1, c -> !c.logHas("找不到路"), "说找不到路")),

				new Scenario("F2", "装一桶水：在地下小坑里，水池在斜上方（玩家报告）", 2 * MINUTE, (a, bot, me) -> {
					a.fill(10, 39, 4, 12, 40, 6, Blocks.WATER);
					// A pocket it dug, under the ground and beside the pond, a block lower than its bottom.
					a.fill(8, 37, 5, 8, 38, 5, Blocks.AIR);
					teleport(bot, a, 8, 37, 5);
					bot.setHealth(8);
					give(bot, Items.BUCKET, 1);
					give(bot, Items.STONE_PICKAXE, 1);
					give(bot, Items.COBBLESTONE, 32);
					give(bot, Items.BREAD, 16);
					return new AcquireTask(me, Items.WATER_BUCKET, 1, Mode.NORMAL);
				}, Scenario.has(Items.WATER_BUCKET, 1, c -> !c.logHas("找不到路"), "说找不到路"))
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

	/** S1 (a ravine 4 wide and 20 deep between the bot and you) in a given mode. */
	private static Scenario ravine(String id, String title, Mode mode) {
		return new Scenario(id, title, 2 * MINUTE, (a, bot, me) -> {
			a.fill(10, 20, -6, 13, 40, 6, Blocks.AIR);
			me.teleportTo(a.level, a.origin.getX() + 25.5, Arena.SURFACE, a.origin.getZ() + 0.5, Set.of(), 0, 0, true);
			give(bot, Items.COBBLESTONE, 32);
			give(bot, Items.BREAD, 16);
			return new ComeTask(me, mode);
		}, c -> c.bot().position().distanceTo(c.arena().at(25, Arena.SURFACE, 0).getBottomCenter()) <= 3
				? Scenario.Outcome.pass("到了") : c.ended() ? Scenario.Outcome.fail("任务结束了但没到") : null);
	}

	/**
	 * A cellar: three stone stairs side by side going down seven steps (x 0..6, rising westwards)
	 * into a room (x 7..11, floor y=34, 3 high), all in solid stone: deeper down each step has 3
	 * blocks of air above it, just what a player needs to walk up (2 isn't: the head hits the
	 * ceiling of the step it's leaving). No doors, no tight spots.
	 */
	private static void cellar(Arena a) {
		a.fill(-1, 30, -3, 13, Arena.SURFACE - 1, 3, Blocks.STONE);
		a.fill(7, 34, -2, 11, 36, 2, Blocks.AIR);
		var stair = Blocks.STONE_STAIRS.defaultBlockState()
				.setValue(net.minecraft.world.level.block.StairBlock.FACING, net.minecraft.core.Direction.WEST);
		for (int k = 0; k <= 6; k++) {
			int y = Arena.SURFACE - 1 - k;
			for (int z = -1; z <= 1; z++) {
				a.set(k, y, z, stair);
				a.set(k, y + 1, z, Blocks.AIR);
				a.set(k, y + 2, z, Blocks.AIR);
				a.set(k, y + 3, z, Blocks.AIR);
			}
		}
		// Step off the last stair into the room.
		a.fill(7, 34, -1, 7, 36, 1, Blocks.AIR);
	}

	/** N1: [0] the impostor, [1] the golem with the bot's name. */
	private static final net.minecraft.world.entity.Entity[] NAME_TEST = new net.minecraft.world.entity.Entity[2];

	private static final net.minecraft.world.entity.Entity[] ZONE_HUSK = new net.minecraft.world.entity.Entity[1];
	private static final int[] ZONE_TARGETED = {0};
	private static final int[] ZONE_COUNT = {0};

	/** Protects the chunk holding arena cell (x, z), if it isn't already. */
	/** 13 by 13 protected chunks (past the view distance of these tests), walls making dead ends to the east and north. */
	private static void zoneWithDeadEnds(Arena a) {
		for (int cx = -6; cx <= 6; cx++) {
			for (int cz = -6; cz <= 6; cz++) {
				protect(a, cx * 16, cz * 16);
			}
		}
		// A pocket open to the west only: the nearest edges of the area, east and north, are behind its wall.
		a.fill(40, 41, -40, 40, 44, 40, Blocks.STONE);
		a.fill(-20, 41, -40, 40, 44, -40, Blocks.STONE);
		a.fill(-20, 41, 40, 40, 44, 40, Blocks.STONE);
	}

	/** Water 19 deep with a kelp in a crack in a shelf: in arm's reach from on top, but not in sight from anywhere. */
	private static void kelpInACrack(Arena a) {
		a.fill(4, 22, -8, 24, 40, 8, Blocks.WATER);
		a.fill(10, 22, -3, 15, 25, 3, Blocks.STONE);
		a.set(13, 23, 0, Blocks.WATER);
		a.set(12, 23, 0, Blocks.KELP);
	}

	/**
	 * The decision alone, with no walking: rounds of "pick the next target, go by what the planner
	 * says", from the heights and breaths in the log of the player's kelp dives. The crack must come
	 * up once and never again, its neighbour neither, until the breath really is bigger or it's far off.
	 */
	private static Scenario.Outcome decideAgainAndAgain(Scenario.Context c) {
		Arena a = c.arena();
		BotPlayer bot = c.bot();
		BlockPos crack = a.at(12, 23, 0), near = a.at(12, 25, 0), open = a.at(18, 22, 2);
		int[][] rounds = {{12, 41, 300, 360}, {12, 37, 290, 360}, {12, 31, 116, 360}, {10, 36, 75, 360}, {12, 34, 92, 340},
				{13, 41, 350, 360}, {12, 41, 360, 360}, {8, 41, 300, 360}, {12, 41, 300, 360}, {12, 41, 300, 360}};
		com.vale.helperbot.task.OutOfReach memory = new com.vale.helperbot.task.OutOfReach();
		int searched = 0;
		for (int[] r : rounds) {
			BlockPos from = a.at(r[0], r[1], 0);
			if (memory.covers(near, r[3], from) != memory.covers(crack, r[3], from)) {
				return Scenario.Outcome.fail("旁边的方块和岩缝判断不一致");
			}
			if (!memory.covers(crack, r[3], from)) {
				searched++;
				String verdict = verdictOn(a, bot, crack, from, r[2], r[3]);
				if (verdict != null) {
					return Scenario.Outcome.fail("第 " + searched + " 次：" + verdict);
				}
				memory.proven(crack, r[3], from);
			}
			if (memory.covers(open, r[3], from)) {
				return Scenario.Outcome.fail("开阔处的海带被连带排除了");
			}
		}
		if (searched != 1) {
			return Scenario.Outcome.fail(rounds.length + " 轮里下潜了 " + searched + " 次");
		}
		boolean bigger = !memory.covers(crack, 380, a.at(12, 41, 0));
		boolean farOff = !memory.covers(crack, 360, a.at(200, 41, 0));
		return bigger && farOff ? Scenario.Outcome.pass(rounds.length + " 轮只判断 1 次；气变多或走远才重判")
				: Scenario.Outcome.fail("气变多：" + bigger + "，走远：" + farOff);
	}

	/** Is there really nowhere to mine it from? Null if so, otherwise where. */
	private static String verdictOn(Arena a, BotPlayer bot, BlockPos block, BlockPos from, int air, int full) {
		var goal = com.vale.helperbot.task.MineSpot.reachGoal(block, bot.blockInteractionRange() - 0.3, bot.getEyeHeight(), true);
		for (BlockPos p : BlockPos.betweenClosed(block.offset(-5, -5, -5), block.offset(5, 5, 5))) {
			if (!Double.isNaN(com.vale.helperbot.path.LocalPathfinder.floorHeight(a.level, p)) && goal.test(a.level, p)) {
				return "能站在 " + p.toShortString() + " 挖到";
			}
		}
		return null;
	}

	private static void protect(Arena a, int x, int z) {
		com.vale.helperbot.guard.ProtectionZones.protect(a.level, new net.minecraft.world.level.ChunkPos(a.at(x, 0, z)));
	}

	/** Oak logs in the chunk holding arena cell (x, z), surface and up. */
	private static int countLogs(Arena a, int x, int z) {
		return count(a, x, z, Arena.SURFACE, Arena.SURFACE + 12, s -> s.is(Blocks.OAK_LOG));
	}

	/** Non-air blocks in the chunk holding arena cell (x, z), from a few blocks down to a few up. */
	private static int countSolid(Arena a, int x, int z) {
		return count(a, x, z, Arena.SURFACE - 8, Arena.SURFACE + 3, s -> !s.isAir());
	}

	private static int count(Arena a, int x, int z, int y0, int y1, java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> what) {
		net.minecraft.world.level.ChunkPos chunk = new net.minecraft.world.level.ChunkPos(a.at(x, 0, z));
		int n = 0;
		for (BlockPos p : BlockPos.betweenClosed(chunk.getMinBlockX(), y0, chunk.getMinBlockZ(), chunk.getMaxBlockX(), y1, chunk.getMaxBlockZ())) {
			if (what.test(a.level.getBlockState(p))) {
				n++;
			}
		}
		return n;
	}

	private static void restrictObsidian(boolean on) {
		if (com.vale.helperbot.guard.Restrictions.isBlocked(Blocks.OBSIDIAN) != on) {
			com.vale.helperbot.guard.Restrictions.toggle(Blocks.OBSIDIAN);
		}
	}

	private static int countObsidian(Arena a) {
		int n = 0;
		for (BlockPos p : BlockPos.betweenClosed(a.at(-2, Arena.SURFACE - 1, -2), a.at(2, Arena.SURFACE + 3, 2))) {
			if (a.level.getBlockState(p).is(Blocks.OBSIDIAN)) {
				n++;
			}
		}
		return n;
	}

	/** Highest the bot got during a scene (V1, V2). */
	private static final int[] MAX_Y = {Integer.MIN_VALUE};

	/** Result of the plan-only scene: [0] failure reason or null, [1] description. */
	private static final String[] PLAN_RESULT = new String[2];
	/** W36: whether the bot was seen in the swimming pose. */
	private static final boolean[] SWAM = new boolean[1];

	private static void give(BotPlayer bot, Item item, int count) {
		bot.getInventory().add(new ItemStack(item, count));
	}

	private static void teleport(BotPlayer bot, Arena a, int x, int y, int z) {
		bot.teleportTo(a.level, a.origin.getX() + x + 0.5, y, a.origin.getZ() + z + 0.5, Set.of(), 0, 0, true);
	}
}
