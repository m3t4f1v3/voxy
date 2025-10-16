package me.cortex.voxy.client.mixin.nvidium;

import me.cortex.nvidium.RenderPipeline;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderPipeline.class, remap = false)
public class MixinRenderPipeline {
    @Inject(method = "renderFrame", at = @At("RETURN"))
    private void voxy$injectRender(Viewport frustum, ChunkRenderMatrices crm, double px, double py, double pz, CallbackInfo ci) {
        var renderer = ((IGetVoxyRenderSystem) MinecraftClient.getInstance().worldRenderer).getVoxyRenderSystem();
        if (renderer != null) {
            renderer.renderOpaque(renderer.setupViewport(crm, px, py, pz));
        }
    }
}
