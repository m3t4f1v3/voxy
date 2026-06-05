package me.cortex.voxy.client.iris;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.Minecraft;

public class VoxySamplers {
    public static void addSamplers(IrisRenderingPipeline pipeline, SamplerHolder samplers) {
        var patchData = ((IGetVoxyPatchData)pipeline).voxy$getPatchData();
        if (patchData != null) {
            String[] opaqueNames = new String[]{"vxDepthTexOpaque"};
            String[] translucentNames = new String[]{"vxDepthTexTrans"};
            /*
            if (IrisShaderPatch.IMPERSONATE_DISTANT_HORIZONS) {
                opaqueNames = new String[]{"vxDepthTexOpaque", "dhDepthTex1"};
                translucentNames = new String[]{"vxDepthTexTrans", "dhDepthTex", "dhDepthTex0"};
            }*/

            //Access the current pipeline through the VoxyRenderSystem
            samplers.addDynamicSampler(TextureType.TEXTURE_2D, () -> {
                var voxyRenderSystem = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
                if (voxyRenderSystem == null) {
                    return 0;
                }
                
                var currentPipeline = voxyRenderSystem.getPipeline();
                if (currentPipeline == null || !(currentPipeline instanceof IrisVoxyRenderPipeline irisPipeline)) {
                    return 0;
                }

                //Get the depth texture from the current pipeline's framebuffer
                var dt = irisPipeline.fb.getDepthTex();
                if (dt == null) {
                    return 0;
                }
                return dt.id;
            }, new GlSampler(false, true, false, false), opaqueNames);

            samplers.addDynamicSampler(TextureType.TEXTURE_2D, () -> {
                var voxyRenderSystem = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
                if (voxyRenderSystem == null) {
                    return 0;
                }
                
                var currentPipeline = voxyRenderSystem.getPipeline();
                if (currentPipeline == null || !(currentPipeline instanceof IrisVoxyRenderPipeline irisPipeline)) {
                    return 0;
                }

                //Get the depth texture from the current pipeline's translucent framebuffer
                var dt = irisPipeline.fbTranslucent.getDepthTex();
                if (dt == null) {
                    return 0;
                }
                return dt.id;
            }, new GlSampler(false, true, false, false), translucentNames);
        }
    }
}
