package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.systems.RenderSystem;

@Mixin(BackgroundRenderer.class)
public class MixinFogRenderer {
    @Inject(
        method = "applyFog(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/BackgroundRenderer$FogType;FZF)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void voxy$overrideFog(
        Camera camera,
        BackgroundRenderer.FogType fogType,
        float viewDistance,
        boolean thickFog,
        float tickDelta,
        CallbackInfo ci
    ) {
        var vrs = (IGetVoxyRenderSystem) MinecraftClient.getInstance().worldRenderer;

        if (VoxyConfig.CONFIG.renderVanillaFog || vrs == null || vrs.getVoxyRenderSystem() == null) {
            RenderSystem.setShaderFogEnd(viewDistance);
        } else {
            RenderSystem.setShaderFogStart(999999999);
            RenderSystem.setShaderFogEnd(999999999);
        }
    }
}
