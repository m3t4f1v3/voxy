package me.cortex.voxy.commonImpl.compat.sable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.voxy.common.Logger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SableLodChunkManager {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("voxy-config.json");
    private static final TicketType<ChunkPos> VOXY_SABLE_LOD_TICKET = TicketType.create("voxy_sable_lod", Comparator.comparingLong(ChunkPos::toLong));
    private static final int TICKET_DISTANCE = 2;
    private static final int CONFIG_REFRESH_TICKS = 20;
    private static final int HOLDING_INDEX_REFRESH_TICKS = 200;
    private static final int HOLDING_REGION_HEADER_BYTES = 4096;
    private static final int HOLDING_REGION_SIDE_LENGTH = 32;
    private static final int HOLDING_REGION_LOG_SIDE_LENGTH = 5;
    private static final double BLOCKS_PER_SECTION_RENDER_DISTANCE = 512.0;
    private static final double DEDICATED_SERVER_HOLDING_CHUNK_WAKE_PADDING_BLOCKS = 1024.0;
    private static final Pattern HOLDING_REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.slvlr$");

    private static final ConfigSnapshot DISABLED_CONFIG = new ConfigSnapshot(false, 0.0, Long.MIN_VALUE);

    private static SableReflection reflection;
    private static ConfigSnapshot cachedConfig = DISABLED_CONFIG;
    private static long nextConfigRefreshTick;
    private static final Map<String, HoldingChunkIndex> holdingChunkIndexCache = new HashMap<>();
    private static final Map<ServerLevel, LongSet> activeChunkLoads = new WeakHashMap<>();

    private SableLodChunkManager() {
    }

    public static void updateTickets(ServerLevel level, LongSet trackedChunks, LongSet trackedHoldingChunks) {
        SableReflection reflection = getReflection();
        if (reflection == null) {
            clearTickets(level, trackedChunks, trackedHoldingChunks);
            return;
        }

        ConfigSnapshot config = getConfig(level.getGameTime());
        if (!config.enabled()) {
            clearTickets(level, trackedChunks, trackedHoldingChunks);
            return;
        }

        try {
            Object container = reflection.getContainer(level);
            if (container == null) {
                clearTickets(level, trackedChunks, trackedHoldingChunks);
                return;
            }

            List<?> subLevels = reflection.getAllSubLevels(container);
            if (level.players().isEmpty()) {
                clearTickets(level, trackedChunks, trackedHoldingChunks);
                return;
            }

            Object holdingChunkMap = reflection.getHoldingChunkMap(container);
            LongSet desiredChunks = new LongOpenHashSet();
            LongSet desiredHoldingChunks = new LongOpenHashSet();
            double maxHorizontalDistanceSquared = config.horizontalRenderDistanceBlocks() * config.horizontalRenderDistanceBlocks();
            double holdingChunkWakeDistance = config.horizontalRenderDistanceBlocks() + getHoldingChunkWakePadding(level, config);
            double holdingChunkWakeDistanceSquared = holdingChunkWakeDistance * holdingChunkWakeDistance;

            for (Object subLevel : subLevels) {
                if (reflection.isRemoved(subLevel)) {
                    continue;
                }

                Object bounds = reflection.getBoundingBox(subLevel);
                if (bounds == null || !isWithinHorizontalDistance(level, bounds, reflection, maxHorizontalDistanceSquared)) {
                    continue;
                }

                addChunkBounds(bounds, reflection, desiredChunks);
            }

            if (holdingChunkMap != null) {
                updateHoldingChunkLoads(level, reflection, holdingChunkMap, desiredChunks, desiredHoldingChunks, trackedHoldingChunks, maxHorizontalDistanceSquared, holdingChunkWakeDistanceSquared);
            } else {
                trackedHoldingChunks.clear();
            }

            removeStaleTickets(level, trackedChunks, desiredChunks);
            addMissingTickets(level, trackedChunks, desiredChunks);
            activeChunkLoads.put(level, new LongOpenHashSet(desiredChunks));
        } catch (RuntimeException e) {
            Logger.error("Disabling Voxy Sable LOD compatibility after reflective access failed", e);
            reflection = SableReflection.unavailable();
            clearTickets(level, trackedChunks, trackedHoldingChunks);
        }
    }

    public static void clearTickets(ServerLevel level, LongSet trackedChunks, LongSet trackedHoldingChunks) {
        activeChunkLoads.remove(level);

        if (trackedChunks.isEmpty()) {
        } else {
            LongIterator iterator = trackedChunks.iterator();
            while (iterator.hasNext()) {
                long chunk = iterator.nextLong();
                level.getChunkSource().removeRegionTicket(VOXY_SABLE_LOD_TICKET, new ChunkPos(chunk), TICKET_DISTANCE, new ChunkPos(chunk));
                iterator.remove();
            }
        }

        if (trackedHoldingChunks.isEmpty()) {
            return;
        }

        SableReflection reflection = getReflection();
        if (reflection == null) {
            trackedHoldingChunks.clear();
            return;
        }

        try {
            Object container = reflection.getContainer(level);
            Object holdingChunkMap = container != null ? reflection.getHoldingChunkMap(container) : null;

            if (holdingChunkMap == null) {
                trackedHoldingChunks.clear();
                return;
            }

            LongIterator iterator = trackedHoldingChunks.iterator();
            while (iterator.hasNext()) {
                long chunk = iterator.nextLong();
                reflection.updateHoldingChunkStatus(holdingChunkMap, new ChunkPos(chunk), false);
                iterator.remove();
            }
        } catch (RuntimeException e) {
            Logger.error("Failed clearing Voxy Sable holding chunk loads", e);
            trackedHoldingChunks.clear();
        }
    }

    public static boolean isWithinTrackingRange(double playerX, double playerZ, double targetX, double targetZ, long gameTime) {
        ConfigSnapshot config = getConfig(gameTime);
        if (!config.enabled()) {
            return false;
        }

        double dx = playerX - targetX;
        double dz = playerZ - targetZ;
        double maxDistance = config.horizontalRenderDistanceBlocks();
        return (dx * dx) + (dz * dz) <= maxDistance * maxDistance;
    }

    public static boolean shouldTreatChunkAsLoaded(ServerLevel level, int chunkX, int chunkZ, long gameTime) {
        ConfigSnapshot config = getConfig(gameTime);
        if (!config.enabled()) {
            return false;
        }

        if (isChunkWithinHorizontalDistance(level, new ChunkPos(chunkX, chunkZ),
                config.horizontalRenderDistanceBlocks() * config.horizontalRenderDistanceBlocks())) {
            return true;
        }

        LongSet activeChunks = activeChunkLoads.get(level);
        return activeChunks != null && activeChunks.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    private static void addMissingTickets(ServerLevel level, LongSet trackedChunks, LongSet desiredChunks) {
        LongIterator iterator = desiredChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (trackedChunks.add(chunk)) {
                level.getChunkSource().addRegionTicket(VOXY_SABLE_LOD_TICKET, new ChunkPos(chunk), TICKET_DISTANCE, new ChunkPos(chunk));
            }
        }
    }

    private static void removeStaleTickets(ServerLevel level, LongSet trackedChunks, LongSet desiredChunks) {
        LongIterator iterator = trackedChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (!desiredChunks.contains(chunk)) {
                level.getChunkSource().removeRegionTicket(VOXY_SABLE_LOD_TICKET, new ChunkPos(chunk), TICKET_DISTANCE, new ChunkPos(chunk));
                iterator.remove();
            }
        }
    }

    private static void updateHoldingChunkLoads(ServerLevel level,
                                                SableReflection reflection,
                                                Object holdingChunkMap,
                                                LongSet desiredChunks,
                                                LongSet desiredHoldingChunks,
                                                LongSet trackedHoldingChunks,
                                                double maxHorizontalDistanceSquared,
                                                double holdingChunkWakeDistanceSquared) {
        LongSet knownHoldingChunks = new LongOpenHashSet();
        knownHoldingChunks.addAll(getHoldingChunkIndex(reflection, holdingChunkMap, level.getGameTime()).holdingChunks());
        reflection.addLoadedHoldingChunkKeys(holdingChunkMap, knownHoldingChunks);

        LongIterator iterator = knownHoldingChunks.iterator();
        while (iterator.hasNext()) {
            long chunkKey = iterator.nextLong();
            ChunkPos chunkPos = new ChunkPos(chunkKey);
            if (!isChunkWithinHorizontalDistance(level, chunkPos, holdingChunkWakeDistanceSquared)) {
                continue;
            }

            desiredHoldingChunks.add(chunkKey);

            if (trackedHoldingChunks.add(chunkKey)) {
                reflection.updateHoldingChunkStatus(holdingChunkMap, chunkPos, true);
            }

            Object holdingChunk = reflection.getOrLoadHoldingChunk(holdingChunkMap, chunkPos);
            if (holdingChunk == null) {
                continue;
            }

            for (Object holdingSubLevel : reflection.getLoadedHoldingSubLevels(holdingChunk)) {
                Object bounds = reflection.getHoldingSubLevelBounds(holdingSubLevel);
                if (bounds != null && isWithinHorizontalDistance(level, bounds, reflection, maxHorizontalDistanceSquared)) {
                    addChunkBounds(bounds, reflection, desiredChunks);
                }
            }
        }

        removeStaleHoldingChunkLoads(level, reflection, holdingChunkMap, trackedHoldingChunks, desiredHoldingChunks);
    }

    private static void removeStaleHoldingChunkLoads(ServerLevel level,
                                                     SableReflection reflection,
                                                     Object holdingChunkMap,
                                                     LongSet trackedHoldingChunks,
                                                     LongSet desiredHoldingChunks) {
        LongIterator iterator = trackedHoldingChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (desiredHoldingChunks.contains(chunk)) {
                continue;
            }

            ChunkPos chunkPos = new ChunkPos(chunk);
            if (!reflection.isChunkLoadedEnough(level, chunkPos.x, chunkPos.z)) {
                reflection.updateHoldingChunkStatus(holdingChunkMap, chunkPos, false);
            }
            iterator.remove();
        }
    }

    private static void addChunkBounds(Object bounds, SableReflection reflection, LongSet desiredChunks) {
        int minChunkX = Mth.floor(reflection.minX(bounds)) >> 4;
        int maxChunkX = Mth.floor(reflection.maxX(bounds)) >> 4;
        int minChunkZ = Mth.floor(reflection.minZ(bounds)) >> 4;
        int maxChunkZ = Mth.floor(reflection.maxZ(bounds)) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                desiredChunks.add(ChunkPos.asLong(chunkX, chunkZ));
            }
        }
    }

    private static boolean isWithinHorizontalDistance(ServerLevel level, Object bounds, SableReflection reflection, double maxHorizontalDistanceSquared) {
        double minX = reflection.minX(bounds);
        double maxX = reflection.maxX(bounds);
        double minZ = reflection.minZ(bounds);
        double maxZ = reflection.maxZ(bounds);

        for (var player : level.players()) {
            double dx = distanceToRange(player.getX(), minX, maxX);
            double dz = distanceToRange(player.getZ(), minZ, maxZ);
            if ((dx * dx) + (dz * dz) <= maxHorizontalDistanceSquared) {
                return true;
            }
        }

        return false;
    }

    private static boolean isChunkWithinHorizontalDistance(ServerLevel level, ChunkPos chunkPos, double maxHorizontalDistanceSquared) {
        double minX = chunkPos.getMinBlockX();
        double maxX = chunkPos.getMaxBlockX() + 1.0;
        double minZ = chunkPos.getMinBlockZ();
        double maxZ = chunkPos.getMaxBlockZ() + 1.0;

        for (var player : level.players()) {
            double dx = distanceToRange(player.getX(), minX, maxX);
            double dz = distanceToRange(player.getZ(), minZ, maxZ);
            if ((dx * dx) + (dz * dz) <= maxHorizontalDistanceSquared) {
                return true;
            }
        }

        return false;
    }

    private static double getHoldingChunkWakePadding(ServerLevel level, ConfigSnapshot config) {
        if (level.getServer().isDedicatedServer()) {
            return DEDICATED_SERVER_HOLDING_CHUNK_WAKE_PADDING_BLOCKS;
        }
        // Integrated testing should wake holding chunks across the full configured radius so
        // plot-hosted physics objects stay discoverable all the way to the Voxy horizon.
        return config.horizontalRenderDistanceBlocks();
    }

    private static double distanceToRange(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0;
    }

    private static ConfigSnapshot getConfig(long gameTime) {
        if (gameTime < nextConfigRefreshTick) {
            return cachedConfig;
        }
        nextConfigRefreshTick = gameTime + CONFIG_REFRESH_TICKS;

        long lastModified;
        try {
            lastModified = Files.exists(CONFIG_PATH) ? Files.getLastModifiedTime(CONFIG_PATH).toMillis() : Long.MIN_VALUE;
        } catch (IOException e) {
            Logger.error("Failed to stat Voxy config for Sable LOD compatibility", e);
            return cachedConfig;
        }

        if (cachedConfig.lastModifiedMillis() == lastModified) {
            return cachedConfig;
        }

        cachedConfig = loadConfig(lastModified);
        return cachedConfig;
    }

    private static ConfigSnapshot loadConfig(long lastModified) {
        if (!Files.exists(CONFIG_PATH)) {
            return new ConfigSnapshot(true, 16.0 * BLOCKS_PER_SECTION_RENDER_DISTANCE, lastModified);
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            boolean enabled = getBoolean(root, "enabled", true) && getBoolean(root, "enable_rendering", true);
            double sectionRenderDistance = getDouble(root, "section_render_distance", 16.0);

            if (!enabled || sectionRenderDistance <= 0.0) {
                return new ConfigSnapshot(false, 0.0, lastModified);
            }

            return new ConfigSnapshot(true, sectionRenderDistance * BLOCKS_PER_SECTION_RENDER_DISTANCE, lastModified);
        } catch (Exception e) {
            Logger.error("Failed to load Voxy config for Sable LOD compatibility", e);
            return new ConfigSnapshot(false, 0.0, lastModified);
        }
    }

    private static boolean getBoolean(JsonObject root, String key, boolean fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        return root.get(key).getAsBoolean();
    }

    private static double getDouble(JsonObject root, String key, double fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        return root.get(key).getAsDouble();
    }

    private static HoldingChunkIndex getHoldingChunkIndex(SableReflection reflection, Object holdingChunkMap, long gameTime) {
        Path folder = reflection.getHoldingStorageFolder(holdingChunkMap);
        String key = folder.toAbsolutePath().normalize().toString();
        HoldingChunkIndex existing = holdingChunkIndexCache.get(key);
        if (existing != null && gameTime < existing.nextRefreshTick()) {
            return existing;
        }

        HoldingChunkIndex refreshed = new HoldingChunkIndex(scanHoldingChunks(folder), gameTime + HOLDING_INDEX_REFRESH_TICKS);
        holdingChunkIndexCache.put(key, refreshed);
        return refreshed;
    }

    private static LongSet scanHoldingChunks(Path folder) {
        LongSet holdingChunks = new LongOpenHashSet();
        if (!Files.isDirectory(folder)) {
            return holdingChunks;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.slvlr")) {
            for (Path path : stream) {
                addHoldingChunksFromRegion(path, holdingChunks);
            }
        } catch (IOException e) {
            Logger.error("Failed scanning Sable holding chunk index", e);
        }

        return holdingChunks;
    }

    private static void addHoldingChunksFromRegion(Path path, LongSet holdingChunks) {
        Matcher matcher = HOLDING_REGION_FILE_PATTERN.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return;
        }

        int regionX = Integer.parseInt(matcher.group(1));
        int regionZ = Integer.parseInt(matcher.group(2));
        ByteBuffer header = ByteBuffer.allocate(HOLDING_REGION_HEADER_BYTES);

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            while (header.hasRemaining() && channel.read(header) > 0) {
                // Keep reading until the header is full or the file ends.
            }
        } catch (IOException e) {
            Logger.error("Failed reading Sable holding chunk region header " + path, e);
            return;
        }

        header.flip();
        IntBuffer spans = header.asIntBuffer();
        for (int index = 0; index < spans.remaining(); index++) {
            if (spans.get(index) == 0) {
                continue;
            }

            int localX = index & (HOLDING_REGION_SIDE_LENGTH - 1);
            int localZ = index >> HOLDING_REGION_LOG_SIDE_LENGTH;
            int chunkX = (regionX * HOLDING_REGION_SIDE_LENGTH) + localX;
            int chunkZ = (regionZ * HOLDING_REGION_SIDE_LENGTH) + localZ;
            holdingChunks.add(ChunkPos.asLong(chunkX, chunkZ));
        }
    }

    private static SableReflection getReflection() {
        if (reflection != null) {
            return reflection.available() ? reflection : null;
        }

        try {
            Class<?> containerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            Class<?> serverContainerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer");
            Class<?> subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
            Class<?> boundsClass = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3dc");
            Class<?> holdingChunkMapClass = Class.forName("dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap");
            Class<?> holdingChunkClass = Class.forName("dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk");
            Class<?> holdingSubLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel");
            Class<?> subLevelDataClass = Class.forName("dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData");
            Class<?> subLevelStorageClass = Class.forName("dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage");
            Class<?> physicsChunkTicketManagerClass = Class.forName("dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager");

            Method getOrLoadHoldingChunk = holdingChunkMapClass.getDeclaredMethod("getOrLoadHoldingChunk", ChunkPos.class, boolean.class);
            getOrLoadHoldingChunk.setAccessible(true);
            Field loadedHoldingChunks = holdingChunkMapClass.getDeclaredField("loadedHoldingChunks");
            loadedHoldingChunks.setAccessible(true);
            Field storageFolder = subLevelStorageClass.getDeclaredField("folder");
            storageFolder.setAccessible(true);
            reflection = new SableReflection(
                    true,
                    containerClass.getMethod("getContainer", ServerLevel.class),
                    containerClass.getMethod("getAllSubLevels"),
                    serverContainerClass.getMethod("getHoldingChunkMap"),
                    subLevelClass.getMethod("isRemoved"),
                    subLevelClass.getMethod("boundingBox"),
                    boundsClass.getMethod("minX"),
                    boundsClass.getMethod("maxX"),
                    boundsClass.getMethod("minZ"),
                    boundsClass.getMethod("maxZ"),
                    holdingChunkMapClass.getMethod("updateChunkStatus", ChunkPos.class, boolean.class),
                    getOrLoadHoldingChunk,
                    loadedHoldingChunks,
                    holdingChunkMapClass.getMethod("getStorage"),
                    storageFolder,
                    holdingChunkClass.getMethod("getLoadedHoldingSubLevels"),
                    holdingSubLevelClass.getMethod("data"),
                    subLevelDataClass.getMethod("bounds"),
                    physicsChunkTicketManagerClass.getMethod("isChunkLoadedEnough", ServerLevel.class, int.class, int.class)
            );
        } catch (ReflectiveOperationException e) {
            reflection = SableReflection.unavailable();
        }

        return reflection.available() ? reflection : null;
    }

    private record ConfigSnapshot(boolean enabled, double horizontalRenderDistanceBlocks, long lastModifiedMillis) {
    }

    private record HoldingChunkIndex(LongSet holdingChunks, long nextRefreshTick) {
    }

    private record SableReflection(
            boolean available,
            Method getContainer,
            Method getAllSubLevels,
            Method getHoldingChunkMap,
            Method isRemoved,
            Method boundingBox,
            Method minX,
            Method maxX,
            Method minZ,
            Method maxZ,
            Method updateChunkStatus,
            Method getOrLoadHoldingChunk,
            Field loadedHoldingChunks,
            Method getStorage,
            Field storageFolder,
            Method getLoadedHoldingSubLevels,
            Method holdingSubLevelData,
            Method subLevelDataBounds,
            Method isChunkLoadedEnough
    ) {
        private static SableReflection unavailable() {
            return new SableReflection(false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        private Object getContainer(ServerLevel level) {
            try {
                return this.getContainer.invoke(null, level);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private Object getHoldingChunkMap(Object container) {
            try {
                return this.getHoldingChunkMap.invoke(container);
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

        private void updateHoldingChunkStatus(Object holdingChunkMap, ChunkPos chunkPos, boolean loaded) {
            try {
                this.updateChunkStatus.invoke(holdingChunkMap, chunkPos, loaded);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private Object getOrLoadHoldingChunk(Object holdingChunkMap, ChunkPos chunkPos) {
            try {
                return this.getOrLoadHoldingChunk.invoke(holdingChunkMap, chunkPos, false);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        @SuppressWarnings("unchecked")
        private void addLoadedHoldingChunkKeys(Object holdingChunkMap, LongSet knownHoldingChunks) {
            try {
                Object loaded = this.loadedHoldingChunks.get(holdingChunkMap);
                if (loaded instanceof Long2ObjectMap<?> loadedHoldingChunks) {
                    knownHoldingChunks.addAll(loadedHoldingChunks.keySet());
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        private Path getHoldingStorageFolder(Object holdingChunkMap) {
            try {
                Object storage = this.getStorage.invoke(holdingChunkMap);
                return (Path) this.storageFolder.get(storage);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        @SuppressWarnings("unchecked")
        private Iterable<?> getLoadedHoldingSubLevels(Object holdingChunk) {
            try {
                return (Iterable<?>) this.getLoadedHoldingSubLevels.invoke(holdingChunk);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private Object getHoldingSubLevelBounds(Object holdingSubLevel) {
            try {
                Object data = this.holdingSubLevelData.invoke(holdingSubLevel);
                return this.subLevelDataBounds.invoke(data);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private boolean isChunkLoadedEnough(ServerLevel level, int chunkX, int chunkZ) {
            try {
                return (boolean) this.isChunkLoadedEnough.invoke(null, level, chunkX, chunkZ);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
