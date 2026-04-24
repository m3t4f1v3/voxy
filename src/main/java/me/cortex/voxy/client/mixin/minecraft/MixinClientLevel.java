package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.compat.sable.SableClientChunkRetention;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel {

    @Unique
    private int bottomSectionY;

    @Shadow @Final public LevelRenderer levelRenderer;

    @Shadow public abstract ClientChunkCache getChunkSource();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$getBottom(
            ClientPacketListener networkHandler,
            ClientLevel.ClientLevelData properties,
            ResourceKey<Level> registryRef,
            Holder<DimensionType> dimensionType,
            int loadDistance,
            int simulationDistance,
            Supplier<ProfilerFiller> profiler,
            LevelRenderer worldRenderer,
            boolean debugWorld,
            long seed,
            CallbackInfo cir) {
        this.bottomSectionY = ((Level)(Object)this).getMinBuildHeight()>>4;
    }

    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void voxy$injectIngestOnStateChange(BlockPos pos, BlockState old, BlockState updated, CallbackInfo cir) {
        if (old == updated) return;
        if (VoxyCommon.getInstance()==null) return;
        if (!VoxyConfig.CONFIG.ingestEnabled) return;//Only ingest if setting enabled

        var self = (ClientLevel)(Object)this;
        var wi = WorldIdentifier.of(self);
        if (wi == null) {
            return;
        }

        var sectionPos = SectionPos.of(pos);
        boolean retainedChunk = SableClientChunkRetention.isChunkRetained(self, sectionPos.chunk());
        int x = pos.getX()&15;
        int y = pos.getY()&15;
        int z = pos.getZ()&15;
        boolean borderChange = x == 0 || x==15 || y==0 || y==15 || z==0||z==15;

        if (!retainedChunk) {
            //TODO: is this _really_ needed, we should have enough processing power to not need todo it if its only a
            // block removal
            if (!updated.isAir()) return;
            if (!borderChange) return;
        }

        this.voxy$ingestSection(wi, self, sectionPos);

        if (!borderChange) {
            return;
        }

        if (x == 0) this.voxy$ingestSection(wi, self, SectionPos.of(sectionPos.x() - 1, sectionPos.y(), sectionPos.z()));
        if (x == 15) this.voxy$ingestSection(wi, self, SectionPos.of(sectionPos.x() + 1, sectionPos.y(), sectionPos.z()));
        if (y == 0) this.voxy$ingestSection(wi, self, SectionPos.of(sectionPos.x(), sectionPos.y() - 1, sectionPos.z()));
        if (y == 15) this.voxy$ingestSection(wi, self, SectionPos.of(sectionPos.x(), sectionPos.y() + 1, sectionPos.z()));
        if (z == 0) this.voxy$ingestSection(wi, self, SectionPos.of(sectionPos.x(), sectionPos.y(), sectionPos.z() - 1));
        if (z == 15) this.voxy$ingestSection(wi, self, SectionPos.of(sectionPos.x(), sectionPos.y(), sectionPos.z() + 1));
    }

    @Unique
    private void voxy$ingestSection(WorldIdentifier wi, ClientLevel level, SectionPos sectionPos) {
        var chunk = level.getChunk(sectionPos.x(), sectionPos.z(), ChunkStatus.FULL, false);
        if (chunk == null) {
            return;
        }

        int sectionIndex = sectionPos.y() - this.bottomSectionY;
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            return;
        }

        var section = chunk.getSection(sectionIndex);
        var lightEngine = level.getLightEngine();
        var blockLight = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
        var skyLight = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

        VoxelIngestService.rawIngest(
                wi,
                section,
                sectionPos.x(),
                sectionPos.y(),
                sectionPos.z(),
                blockLight == null ? null : blockLight.copy(),
                skyLight == null ? null : skyLight.copy()
        );
    }
}
