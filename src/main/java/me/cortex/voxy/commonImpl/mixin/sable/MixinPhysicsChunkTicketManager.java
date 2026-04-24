package me.cortex.voxy.commonImpl.mixin.sable;

import me.cortex.voxy.commonImpl.compat.sable.SableLodChunkManager;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager")
public class MixinPhysicsChunkTicketManager {
    @Inject(method = "isChunkLoadedEnough", at = @At("HEAD"), cancellable = true, remap = false)
    private static void voxy$extendPhysicsLoadedRange(ServerLevel level, int x, int z, CallbackInfoReturnable<Boolean> cir) {
        if (SableLodChunkManager.shouldTreatChunkAsLoaded(level, x, z, level.getGameTime())) {
            cir.setReturnValue(true);
        }
    }
}
