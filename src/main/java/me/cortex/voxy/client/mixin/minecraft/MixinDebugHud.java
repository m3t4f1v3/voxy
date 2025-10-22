package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.DebugHud;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(DebugHud.class)
public abstract class MixinDebugHud {

    @Inject(at = @At("RETURN"), method = "getLeftText")
    protected void getLeftText(CallbackInfoReturnable<List<String>> info) {
        List<String> voxyLines = new ArrayList<>();

        if (!VoxyCommon.isAvailable()) {
            voxyLines.add(Formatting.RED + "voxy-"+VoxyCommon.MOD_VERSION);//Voxy installed, not avalible
            return;
        }
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            voxyLines.add(Formatting.YELLOW + "voxy-" + VoxyCommon.MOD_VERSION);//Voxy avalible, no instance active
            return;
        }
        VoxyRenderSystem vrs = null;
        var wr = MinecraftClient.getInstance().worldRenderer;
        if (wr != null) vrs = ((IGetVoxyRenderSystem) wr).getVoxyRenderSystem();

        //Voxy instance active
        voxyLines.add((vrs==null?Formatting.DARK_GREEN:Formatting.GREEN)+"voxy-"+VoxyCommon.MOD_VERSION);

        //lines.addLineToSection();
        List<String> instanceLines = new ArrayList<>();
        instance.addDebug(instanceLines);
        voxyLines.addAll(instanceLines);

        if (vrs != null) {
            List<String> renderLines = new ArrayList<>();
            vrs.addDebugInfo(renderLines);
            voxyLines.addAll(renderLines);
        }

        info.getReturnValue().addAll(voxyLines);
    }
}
