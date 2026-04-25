package me.cortex.voxy.commonImpl.mixin.minecraft;

import me.cortex.voxy.client.compat.sable.SableSubLevelVoxyManager;
import me.cortex.voxy.commonImpl.compat.sable.SableClientChunkRetention;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
    @Shadow
    private ClientLevel level;

    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"), cancellable = true)
    private void voxy$deferSableParentChunkUntilReady(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (this.level == null) {
            return;
        }

        if (SableClientChunkRetention.deferChunkPacketIfNeeded((ClientPacketListener) (Object) this, this.level, packet)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleForgetLevelChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;drop(Lnet/minecraft/world/level/ChunkPos;)V"
            ),
            cancellable = true
    )
    private void voxy$retainSableParentChunks(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        if (this.level == null) {
            return;
        }

        if (SableClientChunkRetention.retainChunkIfNeeded(this.level, packet.pos())) {
            ci.cancel();
        }
    }

    @Inject(method = "enableChunkLight", at = @At("TAIL"))
    private void voxy$bootstrapSableParentChunkLighting(LevelChunk chunk, int chunkX, int chunkZ, CallbackInfo ci) {
        if (this.level == null) {
            return;
        }

        if (SableSubLevelVoxyManager.tryIngestPlotChunk(this.level, chunk)) {
            return;
        }

        SableClientChunkRetention.onChunkLightReady(this.level, chunk);
    }
}
