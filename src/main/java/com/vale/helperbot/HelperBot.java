package com.vale.helperbot;

import com.vale.helperbot.bot.BotInventoryMenu;
import com.vale.helperbot.bot.BotManager;
import com.vale.helperbot.bot.BotPlayer;
import com.vale.helperbot.command.BotCommands;
import com.vale.helperbot.guard.ProtectionZones;
import com.vale.helperbot.guard.Rules;
import com.vale.helperbot.knowledge.BiomeHints;
import com.vale.helperbot.knowledge.DropAnalysis;
import com.vale.helperbot.knowledge.RecipeIndex;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.entity.player.Player;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HelperBot implements ModInitializer {
	public static final String MOD_ID = "helperbot";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> BotCommands.register(dispatcher, registryAccess));		// Loot tables and world-gen data can change on /reload: forget what we derived from them.
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			DropAnalysis.clearCache();
			BiomeHints.clearCache();
			RecipeIndex.clearCache();
			com.vale.helperbot.knowledge.MobDrops.clearCache();
			warmUp(server);
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			ProtectionZones.load(server);
			com.vale.helperbot.guard.Restrictions.load(server);
			long start = System.nanoTime();
			warmUp(server);
			BiomeHints.biomesFor(server.overworld(), java.util.List.of()); // reads every world-gen feature once
			LOGGER.info("HelperBot drop, recipe, mob and biome indexes built in {} ms", (System.nanoTime() - start) / 1_000_000);
			if (System.getenv("HELPERBOT_BENCH") != null) {
				com.vale.helperbot.knowledge.PlanBench.run(server);
			}
			com.vale.helperbot.test.ScenarioRunner.startIfRequested(server);
			if (Rules.open()) {
				LOGGER.warn("HelperBot is running with OPEN (beta test) rules: it may break and place any block. "
						+ "Do not use on a real server before going through docs/LAUNCH_CHECKLIST.md.");
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> BotManager.remove(server, "服务器关闭，机器人下线。"));
		// Nobody logs in under the bot's name (any case): in offline mode the name is the account,
		// so they'd get its saved inventory, and the bot couldn't be summoned while they're on. The
		// bot itself never goes through login (BotManager places it directly).
		net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, sync) -> {
			String name = handler.getUserName();
			int cut = name.indexOf(" (");
			if (cut >= 0) {
				name = name.substring(0, cut);
			}
			if (BotManager.BOT_NAME.equalsIgnoreCase(name)) {
				handler.disconnect(Component.literal("\"" + BotManager.BOT_NAME + "\" 是机器人专用的名字，请换一个名字进服务器。"));
			}
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(com.vale.helperbot.guard.ZoneDisplay::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(com.vale.helperbot.guard.ZoneReminder::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(com.vale.helperbot.guard.NameGuard::tick);

		// Right-click the bot to edit its inventory.
		UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (level.isClientSide()) {
				// Single player / modded client: the client only sees a plain player, so recognise
				// the bot by name and stop here, before the client goes on to use the held item.
				return entity instanceof Player p && BotManager.BOT_NAME.equals(p.getGameProfile().name())
						? InteractionResult.SUCCESS : InteractionResult.PASS;
			}
			if (!(entity instanceof BotPlayer bot)) {
				return InteractionResult.PASS;
			}
			lastBotInteraction.put(player.getUUID(), level.getGameTime());
			if (hand == InteractionHand.MAIN_HAND) {
				player.openMenu(new SimpleMenuProvider(
						(id, inventory, p) -> new BotInventoryMenu(id, inventory, bot),
						Component.literal(bot.getGameProfile().name() + " 的背包")));
			}
			return InteractionResult.SUCCESS;
		});

		// Vanilla clients don't know the bot consumed the click and follow up with "use item"
		// (placing water, eating...). Ignore that if it comes in the same tick as a bot click.
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide()) {
				return InteractionResult.PASS;
			}
			Long tick = lastBotInteraction.get(player.getUUID());
			return tick != null && level.getGameTime() - tick <= 1 ? InteractionResult.FAIL : InteractionResult.PASS;
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> lastBotInteraction.clear());
	}

	private static final Map<UUID, Long> lastBotInteraction = new HashMap<>();

	/**
	 * Builds what the first plan would otherwise build on the spot, freezing the game meanwhile
	 * (about a third of a second here, some 20 seconds on a phone): every block's drops, every
	 * recipe, every mob's drops (each killed 60 times on paper) and where mobs spawn. The mobs'
	 * "killer" is a stand-in player, since none is around yet; drops come out the same.
	 * Also what the pathfinder asks of each block state (tags may have changed on reload).
	 */
	private static void warmUp(net.minecraft.server.MinecraftServer server) {
		var level = server.overworld();
		com.vale.helperbot.path.BlockTable.build();
		DropAnalysis.warmUp(level);
		RecipeIndex.craftsFor(level, net.minecraft.world.item.Items.STICK);
		com.vale.helperbot.knowledge.MobDrops.sources(level, net.fabricmc.fabric.api.entity.FakePlayer.get(level),
				net.minecraft.world.item.Items.BONE);
		com.vale.helperbot.knowledge.MobDrops.spawnBiomes(level, net.minecraft.world.entity.EntityType.PIG);
	}
}
