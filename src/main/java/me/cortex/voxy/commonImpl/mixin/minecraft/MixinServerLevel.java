package me.cortex.voxy.commonImpl.mixin.minecraft;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.voxy.commonImpl.compat.sable.SableLodChunkManager;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(value = ServerLevel.class, priority = 1100)
public abstract class MixinServerLevel {
    @Unique
    private final LongSet voxy$sableTrackedChunks = new LongOpenHashSet();
    @Unique
    private final LongSet voxy$sableTrackedHoldingChunks = new LongOpenHashSet();

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void voxy$keepSableSublevelsLoaded(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        SableLodChunkManager.updateTickets((ServerLevel) (Object) this, this.voxy$sableTrackedChunks, this.voxy$sableTrackedHoldingChunks);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void voxy$releaseSableTickets(CallbackInfo ci) {
        SableLodChunkManager.clearTickets((ServerLevel) (Object) this, this.voxy$sableTrackedChunks, this.voxy$sableTrackedHoldingChunks);
    }
}
