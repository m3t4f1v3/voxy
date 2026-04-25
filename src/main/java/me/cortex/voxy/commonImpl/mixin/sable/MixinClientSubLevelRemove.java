package me.cortex.voxy.commonImpl.mixin.sable;

import me.cortex.voxy.client.compat.sable.SableSubLevelVoxyManager;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.ClientSubLevel")
public class MixinClientSubLevelRemove {
    @Shadow(remap = false)
    public native ClientLevel getLevel();

    @Inject(method = "onRemove", at = @At("HEAD"), remap = false)
    private void voxy$removeSubLevelRenderer(CallbackInfo ci) {
        SableSubLevelVoxyManager.onSubLevelRemoved(this.getLevel(), this);
    }
}
