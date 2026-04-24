package me.cortex.voxy.commonImpl.compat.sable;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class SableClientChunkRetention {
    private static final int RETAINED_CHUNK_PADDING = 1;
    private static final long RETAINED_CHUNK_SWEEP_INTERVAL_TICKS = 1L;
    private static final long PENDING_CHUNK_PACKET_TTL_TICKS = 400L;
    private static final int MAX_PENDING_CHUNK_PACKETS = 8192;

    private static final Map<ClientLevel, RetentionState> RETAINED_CHUNKS = new WeakHashMap<>();

    private static ClientReflection reflection;
    private static boolean replayingPendingChunkPacket;

    private SableClientChunkRetention() {
    }

    public static boolean retainChunkIfNeeded(ClientLevel level, ChunkPos chunkPos) {
        ClientReflection reflection = getReflection();
        if (reflection == null) {
            return false;
        }

        try {
            if (!isChunkProtected(level, chunkPos, reflection, false)) {
                return false;
            }

            RetentionState state = RETAINED_CHUNKS.computeIfAbsent(level, ignored -> new RetentionState());
            long chunkKey = chunkPos.toLong();
            state.retainedChunks.add(chunkKey);
            bootstrapChunkIfLoaded(level, state, chunkKey);
            return true;
        } catch (RuntimeException e) {
            Logger.error("Disabling Sable client chunk retention after reflective access failed", e);
            SableClientChunkRetention.reflection = ClientReflection.unavailable();
            return false;
        }
    }

    public static boolean shouldStoreShadowChunk(ClientLevel level, ChunkPos chunkPos) {
        ClientReflection reflection = getReflection();
        if (reflection == null) {
            return false;
        }

        try {
            if (isSablePlotChunk(level, chunkPos.x, chunkPos.z, reflection)) {
                return false;
            }

            return isChunkProtected(level, chunkPos, reflection, true);
        } catch (RuntimeException e) {
            Logger.error("Disabling Sable client chunk retention after shadow chunk check failed", e);
            SableClientChunkRetention.reflection = ClientReflection.unavailable();
            return false;
        }
    }

    public static boolean deferChunkPacketIfNeeded(
            ClientPacketListener listener,
            ClientLevel level,
            ClientboundLevelChunkWithLightPacket packet
    ) {
        if (replayingPendingChunkPacket || isInStorageRange(level, packet.getX(), packet.getZ())) {
            return false;
        }

        ClientReflection reflection = getReflection();
        if (reflection == null) {
            return false;
        }

        ChunkPos chunkPos = new ChunkPos(packet.getX(), packet.getZ());
        try {
            if (isSablePlotChunk(level, chunkPos.x, chunkPos.z, reflection)) {
                return false;
            }

            if (isChunkProtected(level, chunkPos, reflection, true)) {
                return false;
            }

            RetentionState state = RETAINED_CHUNKS.computeIfAbsent(level, ignored -> new RetentionState());
            state.packetListener = listener;
            long chunkKey = chunkPos.toLong();
            if (!state.pendingChunkPackets.containsKey(chunkKey) && state.pendingChunkPackets.size() >= MAX_PENDING_CHUNK_PACKETS) {
                LongIterator iterator = state.pendingChunkPackets.keySet().iterator();
                if (iterator.hasNext()) {
                    iterator.nextLong();
                    iterator.remove();
                }
            }

            state.pendingChunkPackets.put(chunkKey, new PendingChunkPacket(packet, level.getGameTime()));
            return true;
        } catch (RuntimeException e) {
            Logger.error("Disabling Sable client chunk retention after pending chunk packet check failed", e);
            SableClientChunkRetention.reflection = ClientReflection.unavailable();
            return false;
        }
    }

    public static void flushPendingChunkPackets(ClientLevel level) {
        RetentionState state = RETAINED_CHUNKS.get(level);
        ClientReflection reflection = getReflection();
        if (state == null || reflection == null) {
            return;
        }

        try {
            flushPendingChunkPackets(level, state, collectProtectedChunks(level, reflection, true));
        } catch (RuntimeException e) {
            Logger.error("Disabling Sable client chunk retention after pending chunk flush failed", e);
            SableClientChunkRetention.reflection = ClientReflection.unavailable();
            releaseAll(level, state);
            RETAINED_CHUNKS.remove(level);
        }
    }

    public static void tick(ClientLevel level) {
        ClientReflection reflection = getReflection();
        RetentionState state = RETAINED_CHUNKS.get(level);
        if (reflection == null) {
            if (state != null) {
                releaseAll(level, state);
                RETAINED_CHUNKS.remove(level);
            }
            return;
        }

        if (state == null) {
            state = new RetentionState();
            RETAINED_CHUNKS.put(level, state);
        }

        long gameTime = level.getGameTime();
        if (gameTime < state.nextSweepGameTime) {
            return;
        }

        state.nextSweepGameTime = gameTime + RETAINED_CHUNK_SWEEP_INTERVAL_TICKS;

        try {
            LongSet protectedChunks = collectProtectedChunks(level, reflection, false);
            LongSet readyProtectedChunks = collectProtectedChunks(level, reflection, true);
            flushPendingChunkPackets(level, state, readyProtectedChunks);
            prunePendingChunkPackets(state, protectedChunks, gameTime);

            if (protectedChunks.isEmpty()) {
                releaseAll(level, state);
                if (state.pendingChunkPackets.isEmpty()) {
                    RETAINED_CHUNKS.remove(level);
                }
                return;
            }

            pruneShadowChunks(level, state, protectedChunks);
            bootstrapLoadedProtectedChunks(level, state, protectedChunks);

            LongIterator iterator = state.retainedChunks.iterator();
            while (iterator.hasNext()) {
                long chunkKey = iterator.nextLong();
                if (protectedChunks.contains(chunkKey)) {
                    continue;
                }

                releaseChunk(level, new ChunkPos(chunkKey));
                iterator.remove();
                state.bootstrappedChunks.remove(chunkKey);
            }

            if (state.retainedChunks.isEmpty()
                    && state.bootstrappedChunks.isEmpty()
                    && state.pendingChunkPackets.isEmpty()
                    && copyShadowChunkKeys(level).isEmpty()) {
                RETAINED_CHUNKS.remove(level);
            }
        } catch (RuntimeException e) {
            Logger.error("Disabling Sable client chunk retention after retained chunk sweep failed", e);
            SableClientChunkRetention.reflection = ClientReflection.unavailable();
            releaseAll(level, state);
            RETAINED_CHUNKS.remove(level);
        }
    }

    public static boolean isChunkRetained(ClientLevel level, ChunkPos chunkPos) {
        RetentionState state = RETAINED_CHUNKS.get(level);
        return state != null && state.retainedChunks.contains(chunkPos.toLong());
    }

    public static void onChunkLightReady(ClientLevel level, LevelChunk chunk) {
        ChunkPos chunkPos = chunk.getPos();
        if (!isChunkRetained(level, chunkPos) && !shouldStoreShadowChunk(level, chunkPos)) {
            return;
        }

        RetentionState state = RETAINED_CHUNKS.computeIfAbsent(level, ignored -> new RetentionState());
        state.bootstrappedChunks.add(chunkPos.toLong());
        VoxelIngestService.tryAutoIngestChunk(chunk);
    }

    private static void flushPendingChunkPackets(ClientLevel level, RetentionState state, LongSet readyProtectedChunks) {
        if (state.packetListener == null || state.pendingChunkPackets.isEmpty() || readyProtectedChunks.isEmpty()) {
            return;
        }

        LongSet pendingKeys = new LongOpenHashSet(state.pendingChunkPackets.keySet());
        LongIterator iterator = pendingKeys.iterator();
        while (iterator.hasNext()) {
            long chunkKey = iterator.nextLong();
            if (!readyProtectedChunks.contains(chunkKey)) {
                continue;
            }

            PendingChunkPacket pending = state.pendingChunkPackets.remove(chunkKey);
            if (pending == null || isInStorageRange(level, pending.packet().getX(), pending.packet().getZ())) {
                continue;
            }

            replayingPendingChunkPacket = true;
            try {
                state.packetListener.handleLevelChunkWithLight(pending.packet());
            } finally {
                replayingPendingChunkPacket = false;
            }
        }
    }

    private static void prunePendingChunkPackets(RetentionState state, LongSet protectedChunks, long gameTime) {
        LongIterator iterator = state.pendingChunkPackets.keySet().iterator();
        while (iterator.hasNext()) {
            long chunkKey = iterator.nextLong();
            PendingChunkPacket pending = state.pendingChunkPackets.get(chunkKey);
            if (pending == null) {
                iterator.remove();
                continue;
            }

            if (protectedChunks.contains(chunkKey) && gameTime - pending.queuedAtGameTime() <= PENDING_CHUNK_PACKET_TTL_TICKS) {
                continue;
            }

            if (gameTime - pending.queuedAtGameTime() > PENDING_CHUNK_PACKET_TTL_TICKS) {
                iterator.remove();
            }
        }
    }

    private static void releaseAll(ClientLevel level, RetentionState state) {
        LongIterator iterator = state.retainedChunks.iterator();
        while (iterator.hasNext()) {
            long chunkKey = iterator.nextLong();
            releaseChunk(level, new ChunkPos(chunkKey));
            iterator.remove();
            state.bootstrappedChunks.remove(chunkKey);
        }

        clearShadowChunks(level, state);
        state.bootstrappedChunks.clear();
    }

    private static void releaseChunk(ClientLevel level, ChunkPos chunkPos) {
        boolean hasRealChunk = getRealChunk(level, chunkPos.toLong()) != null;
        removeShadowChunk(level, chunkPos);
        level.getChunkSource().drop(chunkPos);
        level.queueLightUpdate(() -> clearLight(level, chunkPos));
    }

    private static void clearShadowChunks(ClientLevel level, RetentionState state) {
        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache)) {
            return;
        }

        LongIterator iterator = chunkCache.voxy$copyShadowChunkKeys().iterator();
        while (iterator.hasNext()) {
            long chunkKey = iterator.nextLong();
            ChunkPos chunkPos = new ChunkPos(chunkKey);
            boolean hasRealChunk = getRealChunk(level, chunkKey) != null;
            if (removeShadowChunk(level, chunkPos) != null && !hasRealChunk) {
                level.queueLightUpdate(() -> clearLight(level, chunkPos));
            }
            state.bootstrappedChunks.remove(chunkKey);
        }
    }

    private static void pruneShadowChunks(ClientLevel level, RetentionState state, LongSet protectedChunks) {
        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache)) {
            return;
        }

        LongIterator iterator = chunkCache.voxy$copyShadowChunkKeys().iterator();
        while (iterator.hasNext()) {
            long chunkKey = iterator.nextLong();
            boolean hasRealChunk = getRealChunk(level, chunkKey) != null;
            if (protectedChunks.contains(chunkKey) && !hasRealChunk) {
                continue;
            }

            ChunkPos chunkPos = new ChunkPos(chunkKey);
            if (removeShadowChunk(level, chunkPos) != null && !hasRealChunk) {
                level.queueLightUpdate(() -> clearLight(level, chunkPos));
            }
            state.bootstrappedChunks.remove(chunkKey);
        }
    }

    private static void clearLight(ClientLevel level, ChunkPos chunkPos) {
        LevelLightEngine lightEngine = level.getLightEngine();
        lightEngine.setLightEnabled(chunkPos, false);

        for (int sectionY = lightEngine.getMinLightSection(); sectionY < lightEngine.getMaxLightSection(); sectionY++) {
            SectionPos sectionPos = SectionPos.of(chunkPos, sectionY);
            lightEngine.queueSectionData(LightLayer.BLOCK, sectionPos, null);
            lightEngine.queueSectionData(LightLayer.SKY, sectionPos, null);
        }

        for (int sectionY = level.getMinSection(); sectionY < level.getMaxSection(); sectionY++) {
            lightEngine.updateSectionStatus(SectionPos.of(chunkPos, sectionY), true);
        }
    }

    private static boolean isChunkProtected(ClientLevel level, ChunkPos chunkPos, ClientReflection reflection, boolean requireFinalized) {
        LongSet protectedChunks = collectProtectedChunks(level, reflection, requireFinalized);
        return protectedChunks.contains(chunkPos.toLong());
    }

    private static LongSet collectProtectedChunks(ClientLevel level, ClientReflection reflection, boolean requireFinalized) {
        LongSet protectedChunks = new LongOpenHashSet();
        Object container = reflection.getContainer(level);
        if (container == null) {
            return protectedChunks;
        }

        for (Object subLevel : reflection.getAllSubLevels(container)) {
            if (reflection.isRemoved(subLevel)) {
                continue;
            }

            if (requireFinalized && !reflection.isFinalized(subLevel)) {
                continue;
            }

            Object bounds = reflection.getBoundingBox(subLevel);
            if (bounds == null) {
                continue;
            }

            int minChunkX = ((int) Math.floor(reflection.minX(bounds)) >> 4) - RETAINED_CHUNK_PADDING;
            int maxChunkX = ((int) Math.floor(reflection.maxX(bounds)) >> 4) + RETAINED_CHUNK_PADDING;
            int minChunkZ = ((int) Math.floor(reflection.minZ(bounds)) >> 4) - RETAINED_CHUNK_PADDING;
            int maxChunkZ = ((int) Math.floor(reflection.maxZ(bounds)) >> 4) + RETAINED_CHUNK_PADDING;

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    protectedChunks.add(ChunkPos.asLong(chunkX, chunkZ));
                }
            }
        }

        return protectedChunks;
    }

    private static boolean isSablePlotChunk(ClientLevel level, int chunkX, int chunkZ, ClientReflection reflection) {
        Object container = reflection.getContainer(level);
        return container != null && reflection.inBounds(container, chunkX, chunkZ);
    }

    private static void bootstrapLoadedProtectedChunks(ClientLevel level, RetentionState state, LongSet protectedChunks) {
        LongIterator iterator = protectedChunks.iterator();
        while (iterator.hasNext()) {
            bootstrapChunkIfLoaded(level, state, iterator.nextLong());
        }
    }

    private static void bootstrapChunkIfLoaded(ClientLevel level, RetentionState state, long chunkKey) {
        LevelChunk chunk = getAvailableChunk(level, chunkKey);
        if (chunk == null || !state.bootstrappedChunks.add(chunkKey)) {
            return;
        }

        VoxelIngestService.tryAutoIngestChunk(chunk);
    }

    private static @Nullable LevelChunk getAvailableChunk(ClientLevel level, long chunkKey) {
        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache)) {
            return null;
        }

        LevelChunk chunk = chunkCache.voxy$cheekyGetChunk(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
        if (chunk != null) {
            return chunk;
        }

        return chunkCache.voxy$getShadowChunk(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
    }

    private static @Nullable LevelChunk getRealChunk(ClientLevel level, long chunkKey) {
        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache)) {
            return null;
        }

        return chunkCache.voxy$cheekyGetChunk(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
    }

    private static @Nullable LevelChunk removeShadowChunk(ClientLevel level, ChunkPos chunkPos) {
        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache)) {
            return null;
        }

        return chunkCache.voxy$removeShadowChunk(chunkPos.x, chunkPos.z);
    }

    private static boolean isInStorageRange(ClientLevel level, int chunkX, int chunkZ) {
        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache)) {
            return true;
        }

        return chunkCache.voxy$isInStorageRange(chunkX, chunkZ);
    }

    private static LongSet copyShadowChunkKeys(ClientLevel level) {
        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache)) {
            return new LongOpenHashSet();
        }

        return chunkCache.voxy$copyShadowChunkKeys();
    }

    private static ClientReflection getReflection() {
        if (reflection != null) {
            return reflection.available() ? reflection : null;
        }

        try {
            Class<?> containerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            Class<?> subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
            Class<?> clientSubLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel");
            Class<?> boundsClass = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3dc");

            reflection = new ClientReflection(
                    true,
                    containerClass.getMethod("getContainer", Level.class),
                    containerClass.getMethod("getAllSubLevels"),
                    containerClass.getMethod("inBounds", int.class, int.class),
                    subLevelClass.getMethod("isRemoved"),
                    clientSubLevelClass.getMethod("isFinalized"),
                    subLevelClass.getMethod("boundingBox"),
                    boundsClass.getMethod("minX"),
                    boundsClass.getMethod("maxX"),
                    boundsClass.getMethod("minZ"),
                    boundsClass.getMethod("maxZ")
            );
        } catch (ReflectiveOperationException e) {
            reflection = ClientReflection.unavailable();
        }

        return reflection.available() ? reflection : null;
    }

    private static final class RetentionState {
        private final LongSet retainedChunks = new LongOpenHashSet();
        private final LongSet bootstrappedChunks = new LongOpenHashSet();
        private final Long2ObjectMap<PendingChunkPacket> pendingChunkPackets = new Long2ObjectOpenHashMap<>();
        private ClientPacketListener packetListener;
        private long nextSweepGameTime;
    }

    private record PendingChunkPacket(ClientboundLevelChunkWithLightPacket packet, long queuedAtGameTime) {
    }

    private record ClientReflection(
            boolean available,
            Method getContainer,
            Method getAllSubLevels,
            Method inBounds,
            Method isRemoved,
            Method isFinalized,
            Method boundingBox,
            Method minX,
            Method maxX,
            Method minZ,
            Method maxZ
    ) {
        private static ClientReflection unavailable() {
            return new ClientReflection(false, null, null, null, null, null, null, null, null, null, null);
        }

        private Object getContainer(Level level) {
            try {
                return this.getContainer.invoke(null, level);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private List<?> getAllSubLevels(Object container) {
            try {
                return (List<?>) this.getAllSubLevels.invoke(container);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private boolean inBounds(Object container, int chunkX, int chunkZ) {
            try {
                return (boolean) this.inBounds.invoke(container, chunkX, chunkZ);
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

        private boolean isFinalized(Object subLevel) {
            try {
                return (boolean) this.isFinalized.invoke(subLevel);
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
    }
}
