package com.vale.helperbot.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.vale.helperbot.bot.BotManager;
import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.guard.ProtectionZones;
import com.vale.helperbot.guard.Restrictions;
import com.vale.helperbot.guard.Rules;
import com.vale.helperbot.guard.ZoneDisplay;
import net.minecraft.world.level.ChunkPos;
import com.vale.helperbot.knowledge.Obtainability;
import com.vale.helperbot.path.Mode;
import com.vale.helperbot.bot.BotTask;
import com.vale.helperbot.task.ComeTask;
import com.vale.helperbot.task.FetchWaterTask;
import com.vale.helperbot.task.AcquireTask;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

public final class BotCommands {
	private BotCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(Commands.literal("bot")
				.then(Commands.literal("spawn").executes(BotCommands::spawn))
				.then(Commands.literal("remove").executes(ctx -> {
					BotManager.remove(ctx.getSource().getServer(), "机器人已移除。");
					return 1;
				}))
				.then(Commands.literal("status").executes(BotCommands::status))
				.then(Commands.literal("perf").executes(BotCommands::perf))
				.then(Commands.literal("log").executes(ctx -> {
					BotPlayer bot = requireBot(ctx);
					if (bot == null) {
						return 0;
					}
					var lines = bot.recentLog(15);
					ctx.getSource().sendSuccess(() -> Component.literal("最近 " + lines.size() + " 条行为记录："), false);
					for (String line : lines) {
						ctx.getSource().sendSuccess(() -> Component.literal(line), false);
					}
					return lines.size();
				}))
				.then(Commands.literal("cancel").executes(ctx -> {
					BotPlayer bot = requireBot(ctx);
					if (bot != null) {
						bot.cancelTask();
					}
					return 1;
				}))
				.then(Commands.literal("come")
						.executes(ctx -> come(ctx, Mode.DEFAULT))
						.then(Commands.argument("mode", StringArgumentType.word())
								.suggests((c, b) -> suggestModes(b))
								.executes(ctx -> come(ctx, mode(ctx)))))
				.then(Commands.literal("get")
						.then(Commands.argument("item", ItemArgument.item(buildContext))
								.executes(ctx -> get(ctx, 1, Mode.DEFAULT))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 64 * 36))
										.executes(ctx -> get(ctx, IntegerArgumentType.getInteger(ctx, "count"), Mode.DEFAULT))
										.then(Commands.argument("mode", StringArgumentType.word())
												.suggests((c, b) -> suggestModes(b))
												.executes(ctx -> get(ctx, IntegerArgumentType.getInteger(ctx, "count"), mode(ctx)))))))
				.then(Commands.literal("rules")
						.executes(ctx -> {
							ctx.getSource().sendSuccess(() -> Component.literal("当前规则：" + Rules.current().name().toLowerCase(Locale.ROOT)
									+ "（open = 测试用，什么都能挖、能搭；strict = 只挖天然地形、不搭方块）"), false);
							return 1;
						})
						.then(Commands.literal("open").executes(ctx -> setRules(ctx, Rules.Level.OPEN)))
						.then(Commands.literal("strict").executes(ctx -> setRules(ctx, Rules.Level.STRICT))))
				.then(Commands.literal("debug")
						.then(Commands.literal("path").executes(ctx -> {
							BotPlayer bot = requireBot(ctx);
							if (bot != null) {
								bot.setDebugPath(!bot.isDebugPath());
								ctx.getSource().sendSuccess(() -> Component.literal("路线显示：" + (bot.isDebugPath() ? "开" : "关")), false);
							}
							return 1;
						})))
				.then(Commands.literal("restrict")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(ctx -> {
							ctx.getSource().sendSuccess(() -> Component.literal(Restrictions.describe()), false);
							return 1;
						})
						.then(Commands.literal("block")
								.then(Commands.argument("block", net.minecraft.commands.arguments.blocks.BlockStateArgument.block(buildContext))
										.executes(ctx -> {
											var block = net.minecraft.commands.arguments.blocks.BlockStateArgument.getBlock(ctx, "block").getState().getBlock();
											boolean now = Restrictions.toggle(block);
											ctx.getSource().sendSuccess(() -> Component.literal((now ? "已限制：不挖也不放 " : "已取消限制：")
													+ block.getName().getString()), true);
											return 1;
										})))
						.then(Commands.literal("portals")
								.then(Commands.literal("on").executes(ctx -> setPortals(ctx, true)))
								.then(Commands.literal("off").executes(ctx -> setPortals(ctx, false)))))
				// For the remote's item list: where each item comes from (see WhereFrom), one marked
				// line the remote reads and hides.
				.then(Commands.literal("where").executes(BotCommands::whereFrom))
				.then(Commands.literal("protect")
						.executes(BotCommands::protectChunk)
						.then(Commands.literal("list").executes(BotCommands::listChunks))
						// For the remote's chunk map (Helper Bot Remote): protect several chunks, and ask
						// which are protected around a spot. The answer is one marked line the remote
						// reads and hides; typed by hand it's just a list.
						.then(Commands.literal("chunks")
								.then(Commands.argument("list", StringArgumentType.greedyString()).executes(BotCommands::protectChunks)))
						.then(Commands.literal("query")
								.then(Commands.argument("x", IntegerArgumentType.integer())
										.then(Commands.argument("z", IntegerArgumentType.integer())
												.then(Commands.argument("radius", IntegerArgumentType.integer(0, 32))
														.executes(BotCommands::queryChunks)))))
						// Taking protection off: the server console only (permission 4), by number.
						.then(Commands.literal("remove")
								.requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
								.then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(ctx -> {
									int id = IntegerArgumentType.getInteger(ctx, "id");
									boolean ok = ProtectionZones.remove(id);
									ctx.getSource().sendSuccess(() -> Component.literal(ok ? "已取消保护区块 ID " + id
											: "没有 ID 为 " + id + " 的保护区块"), true);
									return ok ? 1 : 0;
								})))
						.then(Commands.literal("show").executes(ctx -> {
							ZoneDisplay.show(ctx.getSource().getPlayerOrException());
							ctx.getSource().sendSuccess(() -> Component.literal("附近保护区块的边界用绿色粒子标出来了（15 秒）。"), false);
							return 1;
						}))));
	}

	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestModes(
			com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		for (Mode m : Mode.values()) {
			builder.suggest(m.name().toLowerCase(Locale.ROOT));
		}
		return builder.buildFuture();
	}

	private static Mode mode(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String raw = StringArgumentType.getString(ctx, "mode").toUpperCase(Locale.ROOT);
		try {
			return Mode.valueOf(raw);
		} catch (IllegalArgumentException e) {
			throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
					Component.literal("模式只能是 safe / normal / aggressive")).create();
		}
	}

	private static BotPlayer requireBot(CommandContext<CommandSourceStack> ctx) {
		BotPlayer bot = BotManager.get();
		if (bot == null) {
			ctx.getSource().sendFailure(Component.literal("还没有机器人，先用 /bot spawn 召唤。"));
		}
		return bot;
	}

	private static int setRules(CommandContext<CommandSourceStack> ctx, Rules.Level level) {
		Rules.set(level);
		ctx.getSource().sendSuccess(() -> Component.literal("规则已切换为 " + level.name().toLowerCase(Locale.ROOT)), true);
		return 1;
	}

	private static int spawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (BotManager.get() != null) {
			ctx.getSource().sendFailure(Component.literal("机器人已经在了。"));
			return 0;
		}
		BotPlayer bot = BotManager.spawn(ctx.getSource().getServer(), player);
		if (bot == null) {
			ctx.getSource().sendFailure(Component.literal("召唤失败：已有同名玩家在线。"));
			return 0;
		}
		String warning = Rules.open() ? "（注意：当前是 open 测试规则，它什么都能挖、能搭，只在测试存档里用）" : "";
		ctx.getSource().sendSuccess(() -> Component.literal("机器人已召唤。右键它可以编辑背包。" + warning), true);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		BotPlayer bot = requireBot(ctx);
		if (bot == null) {
			return 0;
		}
		BotTask task = bot.getTask();
		String text = "位置 " + bot.blockPosition().toShortString()
				// One decimal: 0.5 health showed as "0", which looked dead but wasn't.
				+ "，生命 " + String.format(Locale.ROOT, "%.1f", bot.getHealth()) + "/" + (int) bot.getMaxHealth()
				+ "，饥饿 " + bot.getFoodData().getFoodLevel()
				+ "，任务：" + (task == null ? "空闲" : task.describe())
				+ "，移动：" + bot.movement().describe()
				+ "，着地：" + (bot.onGround() ? "是" : "否（挖掘会慢 5 倍）");
		ctx.getSource().sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	/**
	 * Where the time goes: the bot's own parts (see Perf), and the whole server's average tick,
	 * with how much of the world is loaded. Run it with the bot out, then after /bot remove: the
	 * difference in the server's tick is what having the bot around costs in all, its own work
	 * plus the world it keeps loaded and running like any player.
	 */
	private static int perf(CommandContext<CommandSourceStack> ctx) {
		var server = ctx.getSource().getServer();
		var level = ctx.getSource().getLevel();
		BotPlayer bot = BotManager.get();
		String own = bot == null ? "机器人不在场。" : bot.perf().summary();
		String world = String.format(Locale.ROOT, "服务器每刻平均 %.2f 毫秒（满负荷是 50）；视距 %d、模拟距离 %d；本维度已加载区块 %d 个，在线玩家 %d 个（含机器人）",
				server.getAverageTickTimeNanos() / 1e6, server.getPlayerList().getViewDistance(),
				server.getPlayerList().getSimulationDistance(), level.getChunkSource().getLoadedChunksCount(),
				server.getPlayerList().getPlayerCount());
		for (String line : (own + "\n" + world + "\n想知道机器人在场总共多花多少：在场时看一次这里的\"服务器每刻平均\"，/bot remove 后过半分钟再看一次，两次相减。").split("\n")) {
			ctx.getSource().sendSuccess(() -> Component.literal(line), false);
		}
		return 1;
	}

	private static int come(CommandContext<CommandSourceStack> ctx, Mode mode) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		BotPlayer bot = requireBot(ctx);
		if (bot == null) {
			return 0;
		}
		bot.startTask(new ComeTask(player, mode));
		return 1;
	}

	private static int get(CommandContext<CommandSourceStack> ctx, int count, Mode mode) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		BotPlayer bot = requireBot(ctx);
		if (bot == null) {
			return 0;
		}
		Item item = ItemArgument.getItem(ctx, "item").getItem();
		String refusal = Obtainability.refusal(ctx.getSource().getLevel(), item);
		if (refusal != null) {
			// Refuse up front, so asking for something impossible doesn't cancel the current task.
			ctx.getSource().sendFailure(Component.literal(refusal));
			return 0;
		}
		String modeName = mode.name().toLowerCase(Locale.ROOT);
		// Water buckets too go through the planner now: it makes the bucket if it has none.
		bot.startTask(new AcquireTask(player, item, count, mode));
		ctx.getSource().sendSuccess(() -> Component.literal("任务已下达：获取 ").append(new ItemStack(item).getHoverName())
				.append(" ×" + count + "（" + modeName + "）"), false);
		return 1;
	}

	private static int setPortals(CommandContext<CommandSourceStack> ctx, boolean restrict) {
		Restrictions.setNoPortals(restrict);
		ctx.getSource().sendSuccess(() -> Component.literal(restrict ? "已限制：机器人不会被任何传送门传走（不去下界、末地）"
				: "已取消传送门限制"), true);
		return 1;
	}

	/** Protects the chunk the player stands in. Taking it off is for the server console (by number). */
	private static int protectChunk(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		ChunkPos chunk = player.chunkPosition();
		boolean was = ProtectionZones.isProtected(player.level(), chunk);
		int id = ProtectionZones.protect(player.level(), chunk);
		String where = "区块 " + chunk.x + ", " + chunk.z + "（x " + chunk.getMinBlockX() + "~" + chunk.getMaxBlockX()
				+ "，z " + chunk.getMinBlockZ() + "~" + chunk.getMaxBlockZ() + "）";
		Component text = Component.literal(was
						? where + "已经是保护区块，ID " + id + "。"
						: "已保护" + where + "，ID " + id + "：机器人可以路过，但不碰里面任何东西，路过时也不会触发机关、不会被怪盯上。"
						+ "（取消保护只能在服务器后台：/bot protect remove " + id + "）")
				.append(button(" [显示边界]", "/bot protect show"));
		ctx.getSource().sendSuccess(() -> text, !was);
		ZoneDisplay.show(player);
		return 1;
	}

	/** Start of the reply to /bot protect query: the remote hides lines starting with this. */
	public static final String ZONES_MARKER = "[helperbot-zones]";
	/** Most chunks one /bot protect chunks may protect. */
	private static final int MAX_CHUNKS_AT_ONCE = 64;

	/** "/bot protect chunks x,z x,z ...": protect each (adding only). */
	private static int protectChunks(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		int added = 0, already = 0, bad = 0;
		String[] parts = StringArgumentType.getString(ctx, "list").trim().split("\\s+");
		for (int i = 0; i < parts.length && i < MAX_CHUNKS_AT_ONCE; i++) {
			String[] xz = parts[i].split(",");
			try {
				ChunkPos chunk = new ChunkPos(Integer.parseInt(xz[0]), Integer.parseInt(xz[1]));
				if (ProtectionZones.isProtected(player.level(), chunk)) {
					already++;
				} else {
					ProtectionZones.protect(player.level(), chunk);
					added++;
				}
			} catch (RuntimeException e) {
				bad++;
			}
		}
		String text = "已保护 " + added + " 个区块" + (already > 0 ? "（另有 " + already + " 个本来就是）" : "")
				+ (bad > 0 ? "，" + bad + " 个写法不对没处理" : "") + "。取消保护只能在服务器后台按 ID 执行。";
		ctx.getSource().sendSuccess(() -> Component.literal(text), true);
		return added;
	}

	/** Start of the reply to /bot where: the remote hides lines starting with this. */
	public static final String WHERE_MARKER = "[helperbot-where]";

	/**
	 * "/bot where": the items that need the Nether, the End, or can't be had at all, as
	 * "n=id,id;e=id,...;x=id,...". Everything else comes from the Overworld.
	 */
	private static int whereFrom(CommandContext<CommandSourceStack> ctx) {
		var places = com.vale.helperbot.knowledge.WhereFrom.all(ctx.getSource().getServer());
		StringBuilder n = new StringBuilder("n="), e = new StringBuilder(";e="), x = new StringBuilder(";x=");
		places.forEach((item, place) -> {
			StringBuilder to = switch (place) {
				case NETHER -> n;
				case END -> e;
				case NONE -> x;
				default -> null;
			};
			if (to != null) {
				to.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item)).append(',');
			}
		});
		String text = WHERE_MARKER + n + e + x;
		ctx.getSource().sendSuccess(() -> Component.literal(text), false);
		return places.size();
	}

	/** "/bot protect query x z radius": the protected chunks around chunk (x, z), as "id,x,z;...". */
	private static int queryChunks(CommandContext<CommandSourceStack> ctx) {
		ChunkPos center = new ChunkPos(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "z"));
		var near = ProtectionZones.near(ctx.getSource().getLevel(), center, IntegerArgumentType.getInteger(ctx, "radius"));
		StringBuilder sb = new StringBuilder(ZONES_MARKER);
		for (ProtectionZones.Entry e : near) {
			sb.append(e.id()).append(',').append(e.x()).append(',').append(e.z()).append(';');
		}
		String text = sb.toString();
		ctx.getSource().sendSuccess(() -> Component.literal(text), false);
		return near.size();
	}

	private static Component button(String label, String command) {
		return Component.literal(label).withStyle(s -> s.withColor(net.minecraft.ChatFormatting.AQUA)
				.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(command)));
	}

	private static int listChunks(CommandContext<CommandSourceStack> ctx) {
		var chunks = ProtectionZones.entries(ctx.getSource().getLevel());
		if (chunks.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal("这个维度还没有保护区块。站在要保护的区块里输入 /bot protect。"), false);
			return 0;
		}
		StringBuilder sb = new StringBuilder("保护区块（" + chunks.size() + " 个，ID：区块坐标）：");
		for (ProtectionZones.Entry c : chunks) {
			sb.append(" ").append(c.id()).append(":[").append(c.x()).append(", ").append(c.z()).append("]");
		}
		String text = sb.toString();
		ctx.getSource().sendSuccess(() -> Component.literal(text).append(button(" [显示边界]", "/bot protect show")), false);
		return chunks.size();
	}
}
