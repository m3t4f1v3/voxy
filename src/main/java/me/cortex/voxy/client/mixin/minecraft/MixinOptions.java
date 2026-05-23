package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.core.RequestDistanceHelper;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Options.class)
public class MixinOptions {
    @Shadow @Final private OptionInstance<Integer> renderDistance;

    @Redirect(
            method = "broadcastOptions",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"
            )
    )
    private Object voxy$spoofRenderDistance(OptionInstance<?> instance) {
        Object value = instance.get();
        if (instance == this.renderDistance && value instanceof Integer integer) {
            return RequestDistanceHelper.getSpoofedViewDistance(integer);
        }
        return value;
    }
}
