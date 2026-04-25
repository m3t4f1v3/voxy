package me.cortex.voxy.commonImpl.mixin.sable;

import me.cortex.voxy.commonImpl.compat.sable.SableLodChunkManager;
import me.cortex.voxy.commonImpl.compat.sable.SableTrackingRefreshManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem")
public class MixinSubLevelTrackingSystem {
    @Shadow(remap = false)
    private ServerLevel level;

    @Inject(method = "shouldLoad", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxy$extendTrackingDistance(Player player, Vector3dc entityPosition, CallbackInfoReturnable<Boolean> cir) {
        long gameTime = this.level.getGameTime();
        boolean withinVoxyRange = SableLodChunkManager.isWithinTrackingRange(
                player.getX(),
                player.getZ(),
                entityPosition.x(),
                entityPosition.z(),
                gameTime);
        boolean withinTrackedBounds = SableTrackingRefreshManager.shouldKeepExtendedTracking(this.level, player, entityPosition, gameTime);
        if (withinVoxyRange || withinTrackedBounds) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "tick(Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;)V", at = @At("TAIL"), remap = false)
    private void voxy$refreshExtendedTrackingChunks(CallbackInfo ci) {
        SableTrackingRefreshManager.tick(this.level, this);
    }
}
