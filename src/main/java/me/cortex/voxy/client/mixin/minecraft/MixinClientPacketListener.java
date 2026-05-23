package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RequestDistanceHelper;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
    private static final int DISTANT_INGEST_RETRIES = 8;

    @Shadow
    private ClientLevel level;

    @Inject(method = "handleLogin", at = @At("HEAD"))
    private void voxy$init(ClientboundLoginPacket packet, CallbackInfo ci) {
        if (!ClientSessionEvents.inSession) {
            ClientSessionEvents.sessionStart();
        }
    }

    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void voxy$resendClientSettings(ClientboundLoginPacket packet, CallbackInfo ci) {
        RequestDistanceHelper.resendClientSettings();
    }

    @Inject(method = "enableChunkLight", at = @At("TAIL"))
    private void voxy$ingestDistantChunk(LevelChunk levelChunk, int chunkX, int chunkZ, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.ingestEnabled) {
            return;
        }
        if (!RequestDistanceHelper.isRequestDistanceActive()) {
            return;
        }
        if (!RequestDistanceHelper.isBeyondVanillaRenderDistance(chunkX, chunkZ)) {
            return;
        }
        RequestDistanceHelper.scheduleDistantIngest(chunkX, chunkZ, DISTANT_INGEST_RETRIES);
    }
}
