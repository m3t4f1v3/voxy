package me.cortex.voxy.client.core.model.bakery;

import me.cortex.voxy.common.Logger;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BakedBlockEntityModel {
    private record LayerConsumer(RenderLayer layer, ReuseVertexConsumer consumer) {}
    private final List<LayerConsumer> layers;
    private BakedBlockEntityModel(List<LayerConsumer> layers) {
        this.layers = layers;
    }

    public void render(Matrix4f matrix, int texId) {
        for (var layer : this.layers) {
            if (layer.consumer.isEmpty()) continue;
            if (layer.layer instanceof RenderLayer.MultiPhase mp) {
                Identifier textureId = mp.phases.texture.getId().orElse(null);
                if (textureId == null) {
                    Logger.error("ERROR: Empty texture id for layer: " + layer);
                } else {
                    texId = MinecraftClient.getInstance().getTextureManager().getTexture(textureId).getGlId();
                }
            }
            if (texId == 0) continue;
            BudgetBufferRenderer.setup(layer.consumer.getAddress(), layer.consumer.quadCount(), texId);
            BudgetBufferRenderer.render(matrix);
        }
    }

    public void release() {
        this.layers.forEach(layer->layer.consumer.free());
    }

    private static int getMetaFromLayer(RenderLayer layer) {
        boolean hasDiscard = layer == RenderLayer.getCutout() ||
                layer == RenderLayer.getCutoutMipped() ||
                layer == RenderLayer.getTripwire();

        boolean isMipped = layer == RenderLayer.getCutoutMipped() ||
                layer == RenderLayer.getSolid() ||
                layer.isTranslucent() ||
                layer == RenderLayer.getTripwire();

        int meta = hasDiscard?1:0;
        meta |= isMipped?2:0;
        return meta;
    }

    public static BakedBlockEntityModel bake(BlockState state) {
        Map<RenderLayer, LayerConsumer> map = new HashMap<>();
        var entity = ((BlockEntityProvider)state.getBlock()).createBlockEntity(BlockPos.ORIGIN, state);
        if (entity == null) {
            return null;
        }
        var renderer = MinecraftClient.getInstance().getBlockEntityRenderDispatcher().get(entity);
        entity.setWorld(MinecraftClient.getInstance().world);
        if (renderer != null) {
            try {
                /*
                var rt = renderer.createRenderState();
                renderer.updateRenderState(entity, rt, 0.0f, new Vec3d(0,0,0), null);

                //TODO: FIXME: FINISH
                var cstate = new CameraRenderState();
                var queue = new OrderedRenderCommandQueueImpl();
                renderer.render(rt, new MatrixStack(), queue, cstate);
                var qq = queue.getBatchingQueue(0);
                 */
                //renderer.render(entity, 0.0f, new MatrixStack(), layer->map.computeIfAbsent(layer, rl -> new LayerConsumer(rl, new ReuseVertexConsumer().setDefaultMeta(getMetaFromLayer(rl)))).consumer, 0, 0, new Vec3d(0,0,0));
            } catch (Exception e) {
                Logger.error("Unable to bake block entity: " + entity, e);
            }
        }
        entity.markRemoved();
        if (map.isEmpty()) {
            return null;
        }
        for (var i : new ArrayList<>(map.values())) {
            if (i.consumer.isEmpty()) {
                map.remove(i.layer);
                i.consumer.free();
            }
        }
        if (map.isEmpty()) {
            return null;
        }
        return new BakedBlockEntityModel(new ArrayList<>(map.values()));
    }
}
