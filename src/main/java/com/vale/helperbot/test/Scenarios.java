package com.vale.helperbot.test;

import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.knowledge.CraftPlanner;
import com.vale.helperbot.path.Mode;
import com.vale.helperbot.task.AcquireTask;
import com.vale.helperbot.task.ComeTask;
import net.minecraft.core.BlockPos;
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

	private static final net.minecraft.world.entity.Entity[] ZONE_HUSK = new net.minecraft.world.entity.Entity[1];
	private static final int[] ZONE_TARGETED = {0};
	private static final int[] ZONE_COUNT = {0};

	/** Protects the chunk holding arena cell (x, z), if it isn't already. */
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

	private static void give(BotPlayer bot, Item item, int count) {
		bot.getInventory().add(new ItemStack(item, count));
	}

	private static void teleport(BotPlayer bot, Arena a, int x, int y, int z) {
		bot.teleportTo(a.level, a.origin.getX() + x + 0.5, y, a.origin.getZ() + z + 0.5, Set.of(), 0, 0, true);
	}
}
