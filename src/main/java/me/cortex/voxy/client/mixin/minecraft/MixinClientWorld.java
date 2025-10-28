package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;

import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientWorld.class)
public abstract class MixinClientWorld {

    @Unique
    private int bottomSectionY;

    @Shadow @Final public WorldRenderer worldRenderer;

    @Shadow public abstract ClientChunkManager getChunkManager();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$getBottom(
            ClientPlayNetworkHandler networkHandler,
            ClientWorld.Properties properties,
            RegistryKey<World> registryRef,
            RegistryEntry<DimensionType> dimensionType,
            int loadDistance,
            int simulationDistance,
            Supplier<Profiler> profiler,
            WorldRenderer worldRenderer,
            boolean debugWorld,
            long seed,
            CallbackInfo cir) {
        this.bottomSectionY = ((World)(Object)this).getBottomY()>>4;
    }

    @Inject(method = "scheduleBlockRerenderIfNeeded", at = @At("TAIL"))
    private void voxy$injectIngestOnStateChange(BlockPos pos, BlockState old, BlockState updated, CallbackInfo cir) {
        if (old == updated) return;

        //TODO: is this _really_ needed, we should have enough processing power to not need todo it if its only a
        // block removal
        if (!updated.isAir()) return;

        if (!VoxyConfig.CONFIG.ingestEnabled) return;//Only ingest if setting enabled

        var self = (World)(Object)this;
        var wi = WorldIdentifier.of(self);
        if (wi == null) {
            return;
        }

        int x = pos.getX()&15;
        int y = pos.getY()&15;
        int z = pos.getZ()&15;
        if (x == 0 || x==15 || y==0 || y==15 || z==0||z==15) {//Update if there is a statechange on the boarder
            var csp = ChunkSectionPos.from(pos);

            var section = self.getChunk(pos).getSection(csp.getSectionY()-this.bottomSectionY);
            var lp = self.getLightingProvider();

            var blp = lp.get(LightType.BLOCK).getLightSection(csp);
            var slp = lp.get(LightType.SKY).getLightSection(csp);

            VoxelIngestService.rawIngest(wi, section, csp.getSectionX(), csp.getSectionY(), csp.getSectionZ(), blp==null?null:blp.copy(), slp==null?null:slp.copy());
        }
    }
}
