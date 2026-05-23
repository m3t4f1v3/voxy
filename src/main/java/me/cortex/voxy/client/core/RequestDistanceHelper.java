package me.cortex.voxy.client.core;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.ChunkStatus;

public abstract class RequestDistanceHelper {
    private RequestDistanceHelper() {}

    public static int getSpoofedViewDistance(int viewDistance) {
        var mc = Minecraft.getInstance();
        if (mc.options == null) {
            return viewDistance;
        }
        int vanilla = mc.options.renderDistance().get();
        return VoxyConfig.CONFIG.getEffectiveRequestDistance(Math.max(viewDistance, vanilla));
    }

    public static boolean isRequestDistanceActive() {
        var mc = Minecraft.getInstance();
        if (mc.options == null) {
            return false;
        }
        return VoxyConfig.CONFIG.isRequestDistanceActive(mc.options.renderDistance().get());
    }

    public static boolean isBeyondVanillaRenderDistance(int chunkX, int chunkZ) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.options == null) {
            return false;
        }
        int vanilla = mc.options.renderDistance().get();
        int px = mc.player.chunkPosition().x;
        int pz = mc.player.chunkPosition().z;
        return Math.max(Math.abs(chunkX - px), Math.abs(chunkZ - pz)) > vanilla;
    }

    public static int getVanillaRenderDistance() {
        var mc = Minecraft.getInstance();
        if (mc.options == null) {
            return 0;
        }
        return mc.options.renderDistance().get();
    }

    public static void resendClientSettings() {
        var mc = Minecraft.getInstance();
        if (mc.options != null) {
            mc.options.broadcastOptions();
        }
    }

    public static void scheduleDistantIngest(int chunkX, int chunkZ, int attemptsLeft) {
        Minecraft.getInstance().execute(() -> {
            if (attemptsLeft <= 0) {
                return;
            }
            if (!VoxyConfig.CONFIG.ingestEnabled || !isRequestDistanceActive()) {
                return;
            }
            var mc = Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }
            var chunk = mc.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunk == null) {
                scheduleDistantIngest(chunkX, chunkZ, attemptsLeft - 1);
                return;
            }
            if (VoxelIngestService.tryAutoIngestDistantChunk(chunk)) {
                return;
            }
            scheduleDistantIngest(chunkX, chunkZ, attemptsLeft - 1);
        });
    }
}
