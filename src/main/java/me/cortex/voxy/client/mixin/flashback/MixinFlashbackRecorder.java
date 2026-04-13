package me.cortex.voxy.client.mixin.flashback;

import com.moulberry.flashback.record.FlashbackMeta;
import com.moulberry.flashback.record.Recorder;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.compat.IFlashbackMeta;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.core.RegistryAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Recorder.class, remap = false)
public class MixinFlashbackRecorder {
    @Shadow @Final private FlashbackMeta metadata;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$getStoragePath(RegistryAccess registryAccess, CallbackInfo retInf) {
        if (VoxyCommon.isAvailable()) {
            var instance = VoxyCommon.getInstance();
            if (instance instanceof VoxyClientInstance ci) {
                ((IFlashbackMeta)this.metadata).setVoxyPath(ci.getStorageBasePath().toFile());
            }
        }
    }
}