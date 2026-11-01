package com.vale.helperbot.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client side only: press H for a menu that builds /bot commands from buttons and sends them, so
 * nothing has to be typed. The server needs the Helper Bot mod; this one only sends commands.
 */
public class HelperBotClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath("helperbotclient", "main"));
	private static KeyMapping openMenu;

	@Override
	public void onInitializeClient() {
		openMenu = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.helperbotclient.menu",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openMenu.consumeClick()) {
				if (client.screen == null && client.player != null) {
					client.setScreen(new BotMenuScreen());
				}
			}
		});
	}

	/** Sends a /bot command (without the slash) as the player, then closes the menu. */
	static void send(String command) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		mc.player.connection.sendCommand(command);
		mc.player.displayClientMessage(Component.literal("§7已发送：/" + command), false);
		mc.setScreen(null);
	}
}
