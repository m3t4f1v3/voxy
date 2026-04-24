package me.cortex.voxy.commonImpl.compat.sable;

import me.cortex.voxy.common.Logger;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3dc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

public final class SableTrackingRefreshManager {
    private static final long REFRESH_INTERVAL_TICKS = 40L;
    private static final double REFRESH_BAND_BLOCKS = 64.0;
    private static final double POSITION_MATCH_PADDING_BLOCKS = 64.0;
    private static final int PARENT_CHUNK_PADDING = 1;

    private static final Map<TrackingKey, RefreshState> refreshStates = new HashMap<>();
    private static final Map<ServerLevel, List<SyntheticTrackingEntry>> syntheticTrackingEntries = new WeakHashMap<>();
    private static Reflection reflection;

    private SableTrackingRefreshManager() {
    }

    public static boolean shouldKeepExtendedTracking(ServerLevel level, Player player, Vector3dc entityPosition, long gameTime) {
        List<SyntheticTrackingEntry> entries = syntheticTrackingEntries.get(level);
        if (entries == null || entries.isEmpty()) {
            return false;
        }

        for (SyntheticTrackingEntry entry : entries) {
            if (!entry.matchesPosition(entityPosition)) {
                continue;
            }

            if (SableLodChunkManager.isWithinTrackingRange(
                    player.getX(),
                    player.getZ(),
                    clamp(player.getX(), entry.minX(), entry.maxX()),
                    clamp(player.getZ(), entry.minZ(), entry.maxZ()),
                    gameTime)) {
                return true;
            }
        }

        return false;
    }

    public static void tick(ServerLevel level, Object trackingSystem) {
        Reflection reflection = getReflection();
        if (reflection == null) {
            refreshStates.clear();
            syntheticTrackingEntries.clear();
            return;
        }

        Object container;
        try {
            container = reflection.getContainer(level);
        } catch (RuntimeException e) {
            Logger.error("Disabling Voxy Sable tracking refresh after reflective access failed", e);
            SableTrackingRefreshManager.reflection = Reflection.unavailable();
            refreshStates.clear();
            syntheticTrackingEntries.clear();
            return;
        }

        if (container == null) {
            refreshStates.clear();
            syntheticTrackingEntries.remove(level);
            return;
        }

        long gameTime = level.getGameTime();
        double baseTrackingRange = reflection.getBaseTrackingRange();
        double baseTrackingRangeSquared = baseTrackingRange * baseTrackingRange;
        Set<TrackingKey> activeSyntheticKeys = new HashSet<>();
        List<SyntheticTrackingEntry> updatedSyntheticEntries = new ArrayList<>();

        for (Object subLevel : reflection.getAllSubLevels(container)) {
            if (reflection.isRemoved(subLevel)) {
                continue;
            }

            UUID subLevelId = reflection.getUniqueId(subLevel);
            if (subLevelId == null) {
                continue;
            }

            Vector3dc position = reflection.getLogicalPosition(subLevel);
            Object bounds = reflection.getBoundingBox(subLevel);
            Collection<UUID> trackingPlayers = reflection.getTrackingPlayers(subLevel);
            boolean anyPlayerWithinExtendedRange = false;

            for (var player : level.players()) {
                UUID playerId = player.getGameProfile().getId();
                boolean withinExtendedRange = isWithinExtendedRange(player, bounds, position, reflection, gameTime);
                double dx = player.getX() - position.x();
                double dy = player.getY() - position.y();
                double dz = player.getZ() - position.z();
                boolean withinBaseRange = (dx * dx) + (dy * dy) + (dz * dz) <= baseTrackingRangeSquared;
                anyPlayerWithinExtendedRange |= withinExtendedRange;

                if (!withinExtendedRange || trackingPlayers.contains(playerId)) {
                    continue;
                }

                trackingPlayers.add(playerId);
                reflection.sendFullSync(trackingSystem, player, subLevel);
                if (!withinBaseRange) {
                    sendParentWorldChunks(level, player, bounds, position, reflection);
                }
            }

            if (bounds != null && anyPlayerWithinExtendedRange) {
                updatedSyntheticEntries.add(SyntheticTrackingEntry.from(position, bounds, reflection));
            }

            if (trackingPlayers.isEmpty()) {
                continue;
            }

            for (UUID playerId : new ArrayList<>(trackingPlayers)) {
                var trackedPlayer = level.getPlayerByUUID(playerId);
                if (!(trackedPlayer instanceof ServerPlayer player)) {
                    continue;
                }

                double dx = player.getX() - position.x();
                double dy = player.getY() - position.y();
                double dz = player.getZ() - position.z();
                boolean withinBaseRange = (dx * dx) + (dy * dy) + (dz * dz) <= baseTrackingRangeSquared;
                boolean withinExtendedRange = isWithinExtendedRange(player, bounds, position, reflection, gameTime);

                TrackingKey key = new TrackingKey(playerId, subLevelId);
                if (!withinExtendedRange || withinBaseRange) {
                    refreshStates.remove(key);
                    continue;
                }

                activeSyntheticKeys.add(key);

                double horizontalDistanceSquared = (dx * dx) + (dz * dz);
                int band = Mth.floor(Math.sqrt(horizontalDistanceSquared) / REFRESH_BAND_BLOCKS);
                RefreshState state = refreshStates.get(key);
                boolean shouldRefresh = state == null
                        || band < state.band()
                        || gameTime >= state.nextRefreshTick();

                if (shouldRefresh) {
                    resendTrackedChunks(level, player, reflection, subLevel, bounds, position);
                    refreshStates.put(key, new RefreshState(gameTime + REFRESH_INTERVAL_TICKS, band));
                } else if (band != state.band()) {
                    refreshStates.put(key, new RefreshState(state.nextRefreshTick(), band));
                }
            }
        }

        refreshStates.keySet().removeIf(key -> !activeSyntheticKeys.contains(key));
        if (updatedSyntheticEntries.isEmpty()) {
            syntheticTrackingEntries.remove(level);
        } else {
            syntheticTrackingEntries.put(level, updatedSyntheticEntries);
        }
    }

    private static void resendTrackedChunks(ServerLevel level, ServerPlayer player, Reflection reflection, Object subLevel, Object bounds, Vector3dc position) {
        Object plot = reflection.getPlot(subLevel);
        LevelLightEngine lightEngine = reflection.getLightEngine(plot);
        for (Object chunkHolder : reflection.getLoadedChunks(plot)) {
            LevelChunk chunk = reflection.getChunk(chunkHolder);
            if (chunk == null) {
                continue;
            }
            player.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null));
        }

        sendParentWorldChunks(level, player, bounds, position, reflection);
    }

    private static void sendParentWorldChunks(ServerLevel level, ServerPlayer player, Object bounds, Vector3dc position, Reflection reflection) {
        ChunkTrackingView trackingView = player.getChunkTrackingView();
        if (trackingView == null) {
            trackingView = ChunkTrackingView.EMPTY;
        }

        int minChunkX;
        int maxChunkX;
        int minChunkZ;
        int maxChunkZ;
        if (bounds != null) {
            minChunkX = (Mth.floor(reflection.minX(bounds)) >> 4) - PARENT_CHUNK_PADDING;
            maxChunkX = (Mth.floor(reflection.maxX(bounds)) >> 4) + PARENT_CHUNK_PADDING;
            minChunkZ = (Mth.floor(reflection.minZ(bounds)) >> 4) - PARENT_CHUNK_PADDING;
            maxChunkZ = (Mth.floor(reflection.maxZ(bounds)) >> 4) + PARENT_CHUNK_PADDING;
        } else {
            int chunkX = Mth.floor(position.x()) >> 4;
            int chunkZ = Mth.floor(position.z()) >> 4;
            minChunkX = chunkX - PARENT_CHUNK_PADDING;
            maxChunkX = chunkX + PARENT_CHUNK_PADDING;
            minChunkZ = chunkZ - PARENT_CHUNK_PADDING;
            maxChunkZ = chunkZ + PARENT_CHUNK_PADDING;
        }

        LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (trackingView.contains(chunkX, chunkZ)) {
                    continue;
                }

                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }

                player.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null));
            }
        }
    }

    private static boolean isWithinExtendedRange(ServerPlayer player, Object bounds, Vector3dc position, Reflection reflection, long gameTime) {
        if (bounds == null) {
            return SableLodChunkManager.isWithinTrackingRange(
                    player.getX(),
                    player.getZ(),
                    position.x(),
                    position.z(),
                    gameTime);
        }

        return SableLodChunkManager.isWithinTrackingRange(
                player.getX(),
                player.getZ(),
                clamp(player.getX(), reflection.minX(bounds), reflection.maxX(bounds)),
                clamp(player.getZ(), reflection.minZ(bounds), reflection.maxZ(bounds)),
                gameTime);
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static Reflection getReflection() {
        if (reflection != null) {
            return reflection.available() ? reflection : null;
        }

        try {
            Class<?> containerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            Class<?> serverSubLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel");
            Class<?> subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
            Class<?> poseClass = Class.forName("dev.ryanhcode.sable.companion.math.Pose3d");
            Class<?> boundsClass = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3dc");
            Class<?> levelPlotClass = Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot");
            Class<?> plotChunkHolderClass = Class.forName("dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder");
            Class<?> sableConfigClass = Class.forName("dev.ryanhcode.sable.SableConfig");
            Class<?> trackingSystemClass = Class.forName("dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem");

            Field trackingRangeField = sableConfigClass.getDeclaredField("SUB_LEVEL_TRACKING_RANGE");
            trackingRangeField.setAccessible(true);
            Object trackingRangeValue = trackingRangeField.get(null);
            Method getAsDouble = trackingRangeValue.getClass().getMethod("getAsDouble");
            Method sendFullSync = trackingSystemClass.getDeclaredMethod("sendFullSync", ServerPlayer.class, serverSubLevelClass, CustomPacketPayload.class);
            sendFullSync.setAccessible(true);

            reflection = new Reflection(
                    true,
                    containerClass.getMethod("getContainer", ServerLevel.class),
                    containerClass.getMethod("getAllSubLevels"),
                    subLevelClass.getMethod("isRemoved"),
                    subLevelClass.getMethod("logicalPose"),
                    poseClass.getMethod("position"),
                    subLevelClass.getMethod("boundingBox"),
                    boundsClass.getMethod("minX"),
                    boundsClass.getMethod("maxX"),
                    boundsClass.getMethod("minZ"),
                    boundsClass.getMethod("maxZ"),
                    serverSubLevelClass.getMethod("getUniqueId"),
                    serverSubLevelClass.getMethod("getTrackingPlayers"),
                    serverSubLevelClass.getMethod("getPlot"),
                    levelPlotClass.getMethod("getLoadedChunks"),
                    levelPlotClass.getMethod("getLightEngine"),
                    plotChunkHolderClass.getMethod("getChunk"),
                    trackingRangeValue,
                    getAsDouble,
                    sendFullSync
            );
        } catch (ReflectiveOperationException e) {
            reflection = Reflection.unavailable();
        }

        return reflection.available() ? reflection : null;
    }

    private record TrackingKey(UUID playerId, UUID subLevelId) {
    }

    private record RefreshState(long nextRefreshTick, int band) {
    }

    private record SyntheticTrackingEntry(double minX, double maxX, double minZ, double maxZ) {
        private static SyntheticTrackingEntry from(Vector3dc position, Object bounds, Reflection reflection) {
            double minX = Math.min(reflection.minX(bounds), position.x());
            double maxX = Math.max(reflection.maxX(bounds), position.x());
            double minZ = Math.min(reflection.minZ(bounds), position.z());
            double maxZ = Math.max(reflection.maxZ(bounds), position.z());
            return new SyntheticTrackingEntry(minX, maxX, minZ, maxZ);
        }

        private boolean matchesPosition(Vector3dc position) {
            return position.x() >= this.minX - POSITION_MATCH_PADDING_BLOCKS
                    && position.x() <= this.maxX + POSITION_MATCH_PADDING_BLOCKS
                    && position.z() >= this.minZ - POSITION_MATCH_PADDING_BLOCKS
                    && position.z() <= this.maxZ + POSITION_MATCH_PADDING_BLOCKS;
        }
    }

    private record Reflection(
            boolean available,
            Method getContainer,
            Method getAllSubLevels,
            Method isRemoved,
            Method logicalPose,
            Method posePosition,
            Method boundingBox,
            Method minX,
            Method maxX,
            Method minZ,
            Method maxZ,
            Method getUniqueId,
            Method getTrackingPlayers,
            Method getPlot,
            Method getLoadedChunks,
            Method getLightEngine,
            Method getChunk,
            Object trackingRangeValue,
            Method getTrackingRangeAsDouble,
            Method sendFullSync
    ) {
        private static Reflection unavailable() {
            return new Reflection(false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        private Object getContainer(ServerLevel level) {
            try {
                return this.getContainer.invoke(null, level);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        @SuppressWarnings("unchecked")
        private List<?> getAllSubLevels(Object container) {
            try {
                return (List<?>) this.getAllSubLevels.invoke(container);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private boolean isRemoved(Object subLevel) {
            try {
                return (boolean) this.isRemoved.invoke(subLevel);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private Vector3dc getLogicalPosition(Object subLevel) {
            try {
                Object pose = this.logicalPose.invoke(subLevel);
                return (Vector3dc) this.posePosition.invoke(pose);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private Object getBoundingBox(Object subLevel) {
            try {
                return this.boundingBox.invoke(subLevel);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private double minX(Object bounds) {
            try {
                return (double) this.minX.invoke(bounds);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private double maxX(Object bounds) {
            try {
                return (double) this.maxX.invoke(bounds);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private double minZ(Object bounds) {
            try {
                return (double) this.minZ.invoke(bounds);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private double maxZ(Object bounds) {
            try {
                return (double) this.maxZ.invoke(bounds);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private UUID getUniqueId(Object subLevel) {
            try {
                return (UUID) this.getUniqueId.invoke(subLevel);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        @SuppressWarnings("unchecked")
        private Collection<UUID> getTrackingPlayers(Object subLevel) {
            try {
                return (Collection<UUID>) this.getTrackingPlayers.invoke(subLevel);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private Object getPlot(Object subLevel) {
            try {
                return this.getPlot.invoke(subLevel);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        @SuppressWarnings("unchecked")
        private Collection<?> getLoadedChunks(Object plot) {
            try {
                return (Collection<?>) this.getLoadedChunks.invoke(plot);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private LevelLightEngine getLightEngine(Object plot) {
            try {
                return (LevelLightEngine) this.getLightEngine.invoke(plot);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private LevelChunk getChunk(Object chunkHolder) {
            try {
                return (LevelChunk) this.getChunk.invoke(chunkHolder);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private double getBaseTrackingRange() {
            try {
                return (double) this.getTrackingRangeAsDouble.invoke(this.trackingRangeValue);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private void sendFullSync(Object trackingSystem, ServerPlayer player, Object subLevel) {
            try {
                this.sendFullSync.invoke(trackingSystem, player, subLevel, null);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
