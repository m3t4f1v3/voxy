package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class MixinSodiumWorldRenderer{

    // @Inject(method = "drawChunkLayer", at = @At(value = "HEAD"), cancellable = true)
    // private void cancelThingie(RenderLayer renderLayer, ChunkRenderMatrices matrices, double x, double y, double z, CallbackInfo ci) {
    //     if (VoxyClient.disableSodiumChunkRender()) {
    //         super.begin(renderLayer);
    //         this.doRender(matrices, renderLayer, camera);
    //         super.end(renderLayer);
    //         ci.cancel();
    //     }
    // }

    @Inject(method = "drawChunkLayer", at = @At("TAIL"))
    private void injectRender(RenderLayer renderLayer, ChunkRenderMatrices matrices, double x, double y, double z, CallbackInfo ci) {
        this.doRender(matrices, renderLayer, x, y, z);
    }

    @Unique
    private void doRender(ChunkRenderMatrices matrices, RenderLayer renderLayer, double x, double y, double z) {
        if (renderLayer.equals(RenderLayer.getCutout())) {
            var renderer = ((IGetVoxyRenderSystem) MinecraftClient.getInstance().worldRenderer).getVoxyRenderSystem();
            if (renderer != null) {
                Viewport<?> viewport = null;
                if (IrisUtil.irisShaderPackEnabled()) {
                    viewport = renderer.getViewport();
                } else {
                    viewport = renderer.setupViewport(matrices, x, y, z);
                }
                renderer.renderOpaque(viewport);
            }
        }
    }
}
