package me.cortex.voxy.commonImpl.mixin.sable;

import me.cortex.voxy.commonImpl.compat.sable.SableClientChunkRetention;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.ClientSubLevel")
public class MixinClientSubLevelFinalizeLighting {
    @Shadow(remap = false)
    private int latestSkyLightScale;

    @Shadow(remap = false)
    public native ClientLevel getLevel();

    @Inject(method = "setFinalized", at = @At("TAIL"), remap = false)
    private void voxy$invalidateInitialSkyLightScale(CallbackInfo ci) {
        this.latestSkyLightScale = -1;
        SableClientChunkRetention.flushPendingChunkPackets(this.getLevel());
    }
}
