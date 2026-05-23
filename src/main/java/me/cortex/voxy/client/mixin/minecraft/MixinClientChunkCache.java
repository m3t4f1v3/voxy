package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RequestDistanceHelper;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;

import org.jetbrains.annotations.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

@Mixin(ClientChunkCache.class)
public class MixinClientChunkCache implements ICheekyClientChunkCache {
    @Unique
    private static final boolean BOBBY_INSTALLED = FabricLoader.getInstance().isModLoaded("bobby");

    @Shadow
    private volatile ClientChunkCache.Storage storage;

    @Override
    public @Nullable LevelChunk voxy$cheekyGetChunk(int x, int z) {
        //This doesnt do the in range check stuff, it just gets the chunk at all costs
        var chunk = this.storage.getChunk(this.storage.getIndex(x, z));
        if (chunk == null) {
            return null;
        }
        //Verify that the position of the chunk is the same as the requested position
        if (chunk.getPos().x == x && chunk.getPos().z == z) {
            return chunk;//The chunk is at the requested position
        }
        //Otherwise return null
        return null;
    }

    @Inject(method = "drop", at = @At("HEAD"))
    public void voxy$captureChunkBeforeUnload(int x, int z, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled && (BOBBY_INSTALLED || RequestDistanceHelper.isRequestDistanceActive())) {
            var chunk = this.voxy$cheekyGetChunk(x, z);
            if (chunk != null) {
                if (RequestDistanceHelper.isRequestDistanceActive() && RequestDistanceHelper.isBeyondVanillaRenderDistance(x, z)) {
                    VoxelIngestService.tryAutoIngestDistantChunk(chunk);
                } else {
                    VoxelIngestService.tryAutoIngestChunk(chunk);
                }
            }
        }
    }

    @Inject(method = "replaceWithPacketData", at = @At("TAIL"))
    private void voxy$scheduleDistantIngestOnReceive(
            int x,
            int z,
            FriendlyByteBuf friendlyByteBuf,
            CompoundTag compoundTag,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
            CallbackInfoReturnable<LevelChunk> cir
    ) {
        if (cir.getReturnValue() == null) {
            return;
        }
        if (!VoxyConfig.CONFIG.ingestEnabled || !RequestDistanceHelper.isRequestDistanceActive()) {
            return;
        }
        if (!RequestDistanceHelper.isBeyondVanillaRenderDistance(x, z)) {
            return;
        }
        RequestDistanceHelper.scheduleDistantIngest(x, z, 8);
    }
}
