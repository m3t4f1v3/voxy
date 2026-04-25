package me.cortex.voxy.client.mixin.sable;

import me.cortex.voxy.client.config.VoxyConfig;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Options;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = SodiumWorldRenderer.class, priority = 500, remap = false)
public class MixinSableSodiumWorldRenderer {
    @Redirect(
            method = "lambda$sable$getOrCreateSubLevelRenderSectionManager$0",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I"),
            require = 0
    )
    private int voxy$useVoxyRenderDistanceForSableSubLevels(Options options) {
        int vanillaRenderDistance = options.getEffectiveRenderDistance();
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            return vanillaRenderDistance;
        }

        int voxyRenderDistance = Mth.ceil(VoxyConfig.CONFIG.sectionRenderDistance * 32.0F);
        return Math.max(vanillaRenderDistance, voxyRenderDistance);
    }
}
