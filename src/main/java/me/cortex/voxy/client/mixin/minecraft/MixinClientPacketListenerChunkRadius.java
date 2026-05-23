package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.core.RequestDistanceHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListenerChunkRadius {
    @Unique
    private long voxy$lastResendMs;

    @Shadow @Final private Minecraft minecraft;
    @Shadow private int serverChunkRadius;

    @Inject(method = "handleSetChunkCacheRadius", at = @At("HEAD"), cancellable = true)
    private void voxy$enforceRequestDistance(ClientboundSetChunkCacheRadiusPacket packet, CallbackInfo ci) {
        if (!RequestDistanceHelper.isRequestDistanceActive()) {
            return;
        }

        int requested = RequestDistanceHelper.getSpoofedViewDistance(this.minecraft.options.renderDistance().get());
        if (packet.getRadius() >= requested) {
            return;
        }

        this.serverChunkRadius = requested;
        this.minecraft.options.setServerRenderDistance(requested);
        this.minecraft.level.getChunkSource().updateViewRadius(requested);
        long now = System.currentTimeMillis();
        if (now - this.voxy$lastResendMs > 3000L) {
            this.voxy$lastResendMs = now;
            RequestDistanceHelper.resendClientSettings();
        }
        ci.cancel();
    }
}
