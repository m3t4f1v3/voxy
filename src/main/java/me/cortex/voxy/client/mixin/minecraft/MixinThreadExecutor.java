// package me.cortex.voxy.client.mixin.minecraft;

// import me.cortex.voxy.client.LoadException;
// import net.minecraft.util.thread.ThreadExecutor;
// import org.spongepowered.asm.mixin.Mixin;
// import org.spongepowered.asm.mixin.Shadow;
// import org.spongepowered.asm.mixin.injection.At;
// import org.spongepowered.asm.mixin.injection.Redirect;

// @Mixin(ThreadExecutor.class)
// public abstract class MixinThreadExecutor {
//     @Shadow public static boolean isMemoryError(Throwable exception){return false;};

//     @Redirect(method = "executeTask", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/ThreadExecutor;isMemoryError(Ljava/lang/Throwable;)Z"))
//     private boolean voxy$forceCrashOnError(Throwable exception) {
//         if (exception instanceof LoadException le) {
//             if (le.getCause() instanceof RuntimeException cause) {
//                 throw cause;
//             }
//             throw le;
//         }
//         return isMemoryError(exception);
//     }
// }
