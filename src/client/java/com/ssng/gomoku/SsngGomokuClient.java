package com.ssng.gomoku;

import com.ssng.gomoku.client.GomokuClientController;
import com.ssng.gomoku.config.SsngConfig;
import com.ssng.gomoku.gui.GomokuScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class SsngGomokuClient implements ClientModInitializer {
    public static final String MOD_ID = "ssng_gomoku";

    private static GomokuClientController controller;
    private static KeyBinding openKey;

    @Override
    public void onInitializeClient() {
        MinecraftClient client = MinecraftClient.getInstance();
        controller = new GomokuClientController(client, SsngConfig.load());
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.ssng_gomoku.open",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "category.ssng_gomoku"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(tickedClient -> {
            controller.tick();
            while (openKey.wasPressed()) {
                tickedClient.setScreen(new GomokuScreen(controller));
            }
        });

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            controller.handleChat(message.getString());
            return true;
        });

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
            controller.handleChat(message.getString())
        );
    }

    public static GomokuClientController controller() {
        return controller;
    }
}
