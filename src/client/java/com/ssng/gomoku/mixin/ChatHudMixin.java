package com.ssng.gomoku.mixin;

import com.ssng.gomoku.SsngGomokuClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void ssngGomoku$handleHudMessage(Text message, CallbackInfo ci) {
        ssngGomoku$handleText(message);
    }

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V", at = @At("HEAD"))
    private void ssngGomoku$handleDecoratedHudMessage(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {
        ssngGomoku$handleText(message);
    }

    @Inject(method = "addMessage(Lnet/minecraft/client/gui/hud/ChatHudLine;)V", at = @At("HEAD"))
    private void ssngGomoku$handleHudLine(ChatHudLine line, CallbackInfo ci) {
        ssngGomoku$handleText(line.content());
    }

    private void ssngGomoku$handleText(Text message) {
        if (message == null) {
            return;
        }
        String plainText = message.getString();

        // 监听所有IRC相关消息
        boolean isIrcMessage = plainText.contains("[S]") || plainText.contains("[IRC]");

        if (!isIrcMessage) {
            return;
        }

        // 过滤掉自己发送的消息（包含 "You -> " 的是自己发的）
        if (plainText.contains("You -> ") || plainText.contains("You->")) {
            return;
        }

        if (SsngGomokuClient.controller() != null) {
            SsngGomokuClient.controller().handleChat(plainText);
        }
    }
}
