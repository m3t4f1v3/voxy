package me.cortex.voxy.commonImpl.mixin.chunky;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import org.popcraft.chunky.platform.FabricWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

@Mixin(FabricWorld.class)
public class MixinFabricWorld {

    @WrapOperation(
        method = "getChunkAtAsync",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/world/ChunkHolder;getChunkAt(Lnet/minecraft/world/chunk/ChunkStatus;Lnet/minecraft/server/world/ThreadedAnvilChunkStorage;)Ljava/util/concurrent/CompletableFuture;"
        )
    )
    private CompletableFuture<Chunk> voxy$wrapChunkLoad(
        ChunkHolder instance,
        ChunkStatus status,
        ThreadedAnvilChunkStorage storage,
        Operation<CompletableFuture<Chunk>> original
    ) {
        CompletableFuture<Chunk> future = original.call(instance, status, storage);
        return future.thenApply(chunk -> {
            if (chunk instanceof WorldChunk worldChunk) {
                VoxelIngestService.tryAutoIngestChunk(worldChunk);
            }
            return chunk;
        });
    }
}