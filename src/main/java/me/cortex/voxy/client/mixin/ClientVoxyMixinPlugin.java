package me.cortex.voxy.client.mixin;

import me.cortex.voxy.commonImpl.VoxyCommon;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ClientVoxyMixinPlugin implements IMixinConfigPlugin {
    private static boolean valkyrienSkiesInstalled;
    private static boolean nvidiumInstalled;

    @Override
    public void onLoad(String mixinPackage) {
        valkyrienSkiesInstalled = VoxyCommon.getPlatformUtil().isModLoaded("valkyrienskies");
        nvidiumInstalled = VoxyCommon.getPlatformUtil().isModLoaded("nvidium");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        //Skip the nvidium hook when nvidium isnt installed (always on forge), avoids a "target not found" warning every launch
        return nvidiumInstalled || !mixinClassName.endsWith("nvidium.MixinRenderPipeline");
    }

    @Override public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        if (valkyrienSkiesInstalled && !nvidiumInstalled) {
            mixins.add("sodium.MixinSodiumWorldRendererVS");
        } else {
            mixins.add("sodium.MixinDefaultChunkRenderer");
        }

        return mixins;
    }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}