package me.cortex.voxy.client.mixin.minecraft;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.compat.sable.SableClientChunkRetention;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
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

    @Unique
    private final Long2ObjectMap<LevelChunk> voxy$shadowChunks = new Long2ObjectOpenHashMap<>();

    @Shadow
    private volatile ClientChunkCache.Storage storage;

    @Shadow
    @Final
    private ClientLevel level;

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

    @Override
    public @Nullable LevelChunk voxy$getShadowChunk(int x, int z) {
        return this.voxy$shadowChunks.get(ChunkPos.asLong(x, z));
    }

    @Override
    public void voxy$putShadowChunk(LevelChunk chunk) {
        this.voxy$shadowChunks.put(chunk.getPos().toLong(), chunk);
    }

    @Override
    public @Nullable LevelChunk voxy$removeShadowChunk(int x, int z) {
        return this.voxy$shadowChunks.remove(ChunkPos.asLong(x, z));
    }

    @Override
    public LongSet voxy$copyShadowChunkKeys() {
        return new LongOpenHashSet(this.voxy$shadowChunks.keySet());
    }

    @Override
    public boolean voxy$isInStorageRange(int x, int z) {
        return this.storage.inRange(x, z);
    }

    @Unique
    private static boolean voxy$isValidChunk(@Nullable LevelChunk chunk, int x, int z) {
        if (chunk == null) {
            return false;
        }
        ChunkPos pos = chunk.getPos();
        return pos.x == x && pos.z == z;
    }

    @Inject(method = "drop", at = @At("HEAD"))
    public void voxy$captureChunkBeforeUnload(ChunkPos pos, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled && BOBBY_INSTALLED) {
            var chunk = this.voxy$cheekyGetChunk(pos.x, pos.z);
            if (chunk != null) {
                VoxelIngestService.tryAutoIngestChunk(chunk);
            }
        }
    }

    @Inject(
            method = "replaceWithPacketData",
            at = @At("HEAD"),
            cancellable = true
    )
    private void voxy$storeShadowChunk(
            int x,
            int z,
            FriendlyByteBuf buffer,
            CompoundTag heightmaps,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntitiesConsumer,
            CallbackInfoReturnable<LevelChunk> cir
    ) {
        if (this.storage.inRange(x, z)) {
            return;
        }

        ChunkPos chunkPos = new ChunkPos(x, z);
        if (!SableClientChunkRetention.shouldStoreShadowChunk(this.level, chunkPos)) {
            return;
        }

        LevelChunk chunk = this.voxy$getShadowChunk(x, z);
        if (!voxy$isValidChunk(chunk, x, z)) {
            chunk = new LevelChunk(this.level, chunkPos);
        }

        chunk.replaceWithPacketData(buffer, heightmaps, blockEntitiesConsumer);
        this.voxy$putShadowChunk(chunk);
        this.level.onChunkLoaded(chunkPos);
        cir.setReturnValue(chunk);
    }

    @Inject(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void voxy$getShadowChunk(
            int x,
            int z,
            net.minecraft.world.level.chunk.status.ChunkStatus leastStatus,
            boolean load,
            CallbackInfoReturnable<LevelChunk> cir
    ) {
        if (voxy$isValidChunk(this.voxy$cheekyGetChunk(x, z), x, z)) {
            return;
        }

        LevelChunk shadowChunk = this.voxy$getShadowChunk(x, z);
        if (voxy$isValidChunk(shadowChunk, x, z)) {
            cir.setReturnValue(shadowChunk);
        }
    }
}
