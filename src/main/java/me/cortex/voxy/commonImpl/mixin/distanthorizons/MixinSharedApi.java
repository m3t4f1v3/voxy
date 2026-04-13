package me.cortex.voxy.commonImpl.mixin.distanthorizons;

import java.util.ArrayList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;

import loaderCommon.fabric.com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
// import net.minecraft.util.math.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;

@Mixin(value = SharedApi.class, remap = false)
public class MixinSharedApi {
    @Inject(method = "queueChunkUpdate", at = @At(
        value = "NEW",
        target = "Lcom/seibel/distanthorizons/core/api/internal/chunkUpdating/ChunkUpdateData;"),
//        cancellable = true,
        remap = false
    )
    private static void beforeChunkUpdateCreation(
            IChunkWrapper chunkWrapper,
            ArrayList<IChunkWrapper> neighborChunkList,
            IDhLevel dhLevel,
            boolean canGetNeighboringChunks,
            CallbackInfo ci
    ) {
        if (!(chunkWrapper instanceof ChunkWrapper cw)) {
            Logger.error("DH MixinSharedApi: chunkWrapper is not a ChunkWrapper!");
            throw new IllegalArgumentException("DH MixinSharedApi: chunkWrapper is not a ChunkWrapper!");
            // return;
        }
        if (!(cw.getChunk() instanceof LevelChunk wc)) {
            Logger.error("DH MixinSharedApi: chunkWrapper.getChunk() is not a LevelChunk!");
            // return;
            throw new IllegalArgumentException("DH MixinSharedApi: chunkWrapper.getChunk() is not a LevelChunk!");
        }

        // for (int x = 0; x < 16; x++) {
        //     System.out.println(wc.getBlockState(new BlockPos(x, 0, 0)).toString());
        // }

        if (VoxelIngestService.tryAutoIngestChunk(wc)) {
            // Logger.info("DH MixinSharedApi: Auto-ingest triggered for chunk: " + chunkWrapper.getChunkPos());
        } else {
            // Logger.info("DH MixinSharedApi: Auto-ingest NOT triggered for chunk: " + chunkWrapper.getChunkPos());
        }
        // ci.cancel();
    }
}
