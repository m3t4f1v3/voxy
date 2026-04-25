package me.cortex.voxy.client.compat.sable;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.TransientSectionStorage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.compat.sable.SableClientReflection;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

public final class SableSubLevelVoxyManager {
    private static final Map<ClientLevel, SableSubLevelVoxyManager> MANAGERS = new WeakHashMap<>();
    private static final List<WorldEngine> PENDING_ENGINE_FREES = new ArrayList<>();
    private static boolean disabled;

    private final ClientLevel level;
    private final SableClientReflection reflection;
    private final Map<UUID, SubLevelState> states = new LinkedHashMap<>();
    private long lastSyncGameTime = Long.MIN_VALUE;
    private boolean forceResync;

    private SableSubLevelVoxyManager(ClientLevel level, SableClientReflection reflection) {
        this.level = level;
        this.reflection = reflection;
    }

    public static boolean isPlotChunk(@Nullable ClientLevel level, int chunkX, int chunkZ) {
        if (disabled || level == null) {
            return false;
        }

        SableClientReflection reflection = SableClientReflection.get();
        if (reflection == null) {
            return false;
        }

        try {
            tickPendingEngineFrees();
            return reflection.isPlotChunk(level, chunkX, chunkZ);
        } catch (RuntimeException e) {
            disable("plot chunk lookup", e);
            return false;
        }
    }

    public static boolean tryIngestPlotChunk(@Nullable ClientLevel level, @Nullable LevelChunk chunk) {
        if (disabled || level == null || chunk == null) {
            return false;
        }

        SableSubLevelVoxyManager manager = get(level);
        if (manager == null) {
            return false;
        }

        try {
            tickPendingEngineFrees();
            return manager.handlePlotChunk(chunk);
        } catch (RuntimeException e) {
            disable("plot chunk ingest", e);
            return false;
        }
    }

    public static boolean tryIngestPlotSection(@Nullable ClientLevel level, SectionPos sectionPos) {
        if (disabled || level == null) {
            return false;
        }

        SableSubLevelVoxyManager manager = get(level);
        if (manager == null) {
            return false;
        }

        try {
            tickPendingEngineFrees();
            return manager.handlePlotSection(sectionPos);
        } catch (RuntimeException e) {
            disable("plot section ingest", e);
            return false;
        }
    }

    public static void onPlotChunkRemoved(@Nullable ClientLevel level, ChunkPos chunkPos) {
        if (disabled || level == null) {
            return;
        }

        SableSubLevelVoxyManager manager = get(level);
        if (manager == null) {
            return;
        }

        try {
            tickPendingEngineFrees();
            manager.handlePlotChunkRemoved(chunkPos);
        } catch (RuntimeException e) {
            disable("plot chunk removal", e);
        }
    }

    public static void onSubLevelFinalized(@Nullable ClientLevel level, Object subLevel) {
        if (disabled || level == null) {
            return;
        }

        SableSubLevelVoxyManager manager = get(level);
        if (manager == null) {
            return;
        }

        try {
            tickPendingEngineFrees();
            manager.getOrCreateState(subLevel).markFinalized();
            manager.forceResync = true;
        } catch (RuntimeException e) {
            disable("sublevel finalize", e);
        }
    }

    public static void onSubLevelRemoved(@Nullable ClientLevel level, Object subLevel) {
        if (disabled || level == null) {
            return;
        }

        SableSubLevelVoxyManager manager = get(level);
        if (manager == null) {
            return;
        }

        try {
            tickPendingEngineFrees();
            UUID id = manager.reflection.getUniqueId(subLevel);
            SubLevelState state = manager.states.remove(id);
            if (state != null) {
                state.close();
            }
        } catch (RuntimeException e) {
            disable("sublevel removal", e);
        }
    }

    public static void renderActiveSubLevels(@Nullable ClientLevel level, Matrix4fc projection, Matrix4fc modelView, double cameraX, double cameraY, double cameraZ) {
        if (disabled || level == null) {
            return;
        }

        SableSubLevelVoxyManager manager = get(level);
        if (manager == null) {
            return;
        }

        try {
            tickPendingEngineFrees();
            manager.render(projection, modelView, cameraX, cameraY, cameraZ);
        } catch (RuntimeException e) {
            disable("sublevel render", e);
        }
    }

    private static @Nullable SableSubLevelVoxyManager get(ClientLevel level) {
        if (disabled) {
            return null;
        }

        SableClientReflection reflection = SableClientReflection.get();
        if (reflection == null) {
            return null;
        }

        return MANAGERS.computeIfAbsent(level, ignored -> new SableSubLevelVoxyManager(level, reflection));
    }

    private boolean handlePlotChunk(LevelChunk chunk) {
        Object subLevel = this.reflection.findSubLevelForChunk(this.level, chunk.getPos().x, chunk.getPos().z);
        if (subLevel == null) {
            return this.reflection.isPlotChunk(this.level, chunk.getPos().x, chunk.getPos().z);
        }

        SubLevelState state = this.getOrCreateState(subLevel);
        if (!this.reflection.isFinalized(subLevel)) {
            state.markFinalizedPending();
            return true;
        }

        state.markChunkSeen(chunk);
        state.ingestChunk(chunk);
        return true;
    }

    private boolean handlePlotSection(SectionPos sectionPos) {
        Object subLevel = this.reflection.findSubLevelForChunk(this.level, sectionPos.x(), sectionPos.z());
        if (subLevel == null) {
            return this.reflection.isPlotChunk(this.level, sectionPos.x(), sectionPos.z());
        }

        SubLevelState state = this.getOrCreateState(subLevel);
        if (!this.reflection.isFinalized(subLevel)) {
            state.markFinalizedPending();
            return true;
        }

        state.ingestSection(sectionPos);
        return true;
    }

    private void handlePlotChunkRemoved(ChunkPos chunkPos) {
        for (SubLevelState state : this.states.values()) {
            state.removeChunk(chunkPos.toLong());
        }
    }

    private void render(Matrix4fc projection, Matrix4fc modelView, double cameraX, double cameraY, double cameraZ) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            this.closeAll();
            return;
        }

        List<Object> activeSubLevels = this.syncStates();
        if (activeSubLevels.isEmpty()) {
            return;
        }

        for (Object subLevel : activeSubLevels) {
            if (!this.reflection.isFinalized(subLevel)) {
                continue;
            }

            SubLevelState state = this.states.get(this.reflection.getUniqueId(subLevel));
            if (state == null) {
                continue;
            }

            state.render(subLevel, this.reflection, projection, modelView, cameraX, cameraY, cameraZ);
        }
    }

    private List<Object> syncStates() {
        long gameTime = this.level.getGameTime();
        if (!this.forceResync && this.lastSyncGameTime == gameTime) {
            return List.copyOf(this.reflection.getAllSubLevels(this.level));
        }

        this.forceResync = false;
        this.lastSyncGameTime = gameTime;

        List<Object> activeSubLevels = new ArrayList<>();
        Set<UUID> liveIds = new HashSet<>();

        for (Object subLevel : this.reflection.getAllSubLevels(this.level)) {
            if (this.reflection.isRemoved(subLevel)) {
                continue;
            }

            UUID id = this.reflection.getUniqueId(subLevel);
            if (id == null) {
                continue;
            }

            liveIds.add(id);
            activeSubLevels.add(subLevel);

            SubLevelState state = this.states.computeIfAbsent(id, ignored -> new SubLevelState(this.level, id));
            state.setFinalized(this.reflection.isFinalized(subLevel));
            state.syncLoadedChunks(this.reflection, subLevel);
        }

        Iterator<Map.Entry<UUID, SubLevelState>> iterator = this.states.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, SubLevelState> entry = iterator.next();
            UUID id = entry.getKey();
            if (liveIds.contains(id)) {
                continue;
            }

            entry.getValue().close();
            iterator.remove();
        }

        return activeSubLevels;
    }

    private SubLevelState getOrCreateState(Object subLevel) {
        UUID id = this.reflection.getUniqueId(subLevel);
        return this.states.computeIfAbsent(id, ignored -> new SubLevelState(this.level, id));
    }

    private void closeAll() {
        for (SubLevelState state : this.states.values()) {
            state.close();
        }
        this.states.clear();
    }

    private static void disable(String action, Throwable error) {
        if (!disabled) {
            Logger.error("Disabling Sable Voxy sublevel renderer after " + action + " failed", error);
        }
        disabled = true;
        MANAGERS.values().forEach(SableSubLevelVoxyManager::closeAll);
        MANAGERS.clear();
        PENDING_ENGINE_FREES.clear();
    }

    private static void tickPendingEngineFrees() {
        Iterator<WorldEngine> iterator = PENDING_ENGINE_FREES.iterator();
        while (iterator.hasNext()) {
            WorldEngine engine = iterator.next();
            if (!engine.isLive()) {
                iterator.remove();
                continue;
            }

            if (!engine.isWorldUsed()) {
                engine.free();
                iterator.remove();
            }
        }
    }

    private static boolean saveTransientSection(WorldEngine engine, WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired) {
        if (!section.exchangeIsInSaveQueue(true)) {
            return false;
        }

        if (!sectionAlreadyAcquired) {
            section.acquire();
        }

        try {
            section.setNotDirty();
            engine.storage.saveSection(section);
        } finally {
            section.exchangeIsInSaveQueue(false);
            section.release();
        }
        return true;
    }

    private static final class SubLevelState {
        private final ClientLevel level;
        private final UUID id;
        private final Long2IntOpenHashMap chunkIdentities = new Long2IntOpenHashMap();
        private final LevelChunkSection emptySection;

        private @Nullable WorldEngine engine;
        private @Nullable VoxyRenderSystem renderer;
        private boolean finalized;
        private boolean needsFullRescan = true;

        private SubLevelState(ClientLevel level, UUID id) {
            this.level = level;
            this.id = id;
            this.emptySection = createEmptySection(level);
            this.chunkIdentities.defaultReturnValue(Integer.MIN_VALUE);
        }

        private void markFinalized() {
            this.finalized = true;
            this.needsFullRescan = true;
        }

        private void markFinalizedPending() {
            this.needsFullRescan = true;
        }

        private void setFinalized(boolean finalized) {
            if (this.finalized != finalized) {
                this.needsFullRescan = true;
            }
            this.finalized = finalized;
        }

        private void markChunkSeen(LevelChunk chunk) {
            this.chunkIdentities.put(chunk.getPos().toLong(), System.identityHashCode(chunk));
        }

        private void syncLoadedChunks(SableClientReflection reflection, Object subLevel) {
            if (!this.finalized) {
                this.needsFullRescan = true;
                return;
            }

            if (!this.ensureEngine()) {
                this.needsFullRescan = true;
                return;
            }

            LongOpenHashSet present = new LongOpenHashSet();
            boolean complete = true;

            for (Object holder : reflection.getLoadedChunkHolders(subLevel)) {
                LevelChunk chunk = reflection.getChunk(holder);
                if (chunk == null) {
                    continue;
                }

                long chunkKey = chunk.getPos().toLong();
                present.add(chunkKey);

                int identity = System.identityHashCode(chunk);
                if (this.needsFullRescan || this.chunkIdentities.get(chunkKey) != identity) {
                    this.ingestChunk(chunk);
                    this.chunkIdentities.put(chunkKey, identity);
                    complete &= this.engine != null;
                }
            }

            LongIterator iterator = this.chunkIdentities.keySet().iterator();
            while (iterator.hasNext()) {
                long chunkKey = iterator.nextLong();
                if (present.contains(chunkKey)) {
                    continue;
                }

                this.clearChunk(chunkKey);
                iterator.remove();
            }

            if (complete) {
                this.needsFullRescan = false;
            }
        }

        private void ingestChunk(LevelChunk chunk) {
            if (!this.ensureEngine()) {
                return;
            }

            LevelLightEngine lightEngine = this.level.getLightEngine();
            int sectionY = chunk.getMinSection() - 1;
            for (LevelChunkSection section : chunk.getSections()) {
                sectionY++;
                if (section == null) {
                    continue;
                }

                SectionPos sectionPos = SectionPos.of(chunk.getPos(), sectionY);
                var blockLight = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
                var skyLight = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

                VoxelIngestService.rawIngest(
                        this.engine,
                        section,
                        sectionPos.x(),
                        sectionPos.y(),
                        sectionPos.z(),
                        blockLight == null ? null : blockLight.copy(),
                        skyLight == null ? null : skyLight.copy()
                );
            }
        }

        private void ingestSection(SectionPos sectionPos) {
            if (!this.ensureEngine()) {
                this.needsFullRescan = true;
                return;
            }

            var chunkAccess = this.level.getChunk(sectionPos.x(), sectionPos.z(), ChunkStatus.FULL, false);
            if (!(chunkAccess instanceof LevelChunk chunk)) {
                this.clearSection(sectionPos);
                return;
            }

            int sectionIndex = sectionPos.y() - chunk.getMinSection();
            if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
                return;
            }

            LevelChunkSection section = chunk.getSection(sectionIndex);
            if (section == null) {
                return;
            }

            LevelLightEngine lightEngine = this.level.getLightEngine();
            var blockLight = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
            var skyLight = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

            VoxelIngestService.rawIngest(
                    this.engine,
                    section,
                    sectionPos.x(),
                    sectionPos.y(),
                    sectionPos.z(),
                    blockLight == null ? null : blockLight.copy(),
                    skyLight == null ? null : skyLight.copy()
            );
        }

        private void removeChunk(long chunkKey) {
            if (this.chunkIdentities.remove(chunkKey) != Integer.MIN_VALUE) {
                this.clearChunk(chunkKey);
            }
        }

        private void clearChunk(long chunkKey) {
            if (!this.ensureEngine()) {
                return;
            }

            int minSection = this.level.getMinSection();
            for (int sectionIndex = 0; sectionIndex < this.level.getSectionsCount(); sectionIndex++) {
                this.clearSection(SectionPos.of(ChunkPos.getX(chunkKey), minSection + sectionIndex, ChunkPos.getZ(chunkKey)));
            }
        }

        private void clearSection(SectionPos sectionPos) {
            if (!this.ensureEngine()) {
                return;
            }

            VoxelIngestService.rawIngest(
                    this.engine,
                    this.emptySection,
                    sectionPos.x(),
                    sectionPos.y(),
                    sectionPos.z(),
                    null,
                    null
            );
        }

        private void render(Object subLevel, SableClientReflection reflection, Matrix4fc projection, Matrix4fc baseModelView, double cameraX, double cameraY, double cameraZ) {
            if (!this.finalized || !this.ensureRenderer()) {
                return;
            }

            this.renderer.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);

            Vector3d localCamera = reflection.transformPositionInverse(subLevel, cameraX, cameraY, cameraZ);
            Quaterniondc orientation = reflection.getOrientation(subLevel);
            Matrix4f rotatedModelView = new Matrix4f(baseModelView).rotate(new Quaternionf(orientation));

            this.renderer.renderOpaque(this.renderer.setupViewport(projection, rotatedModelView, localCamera.x, localCamera.y, localCamera.z));
        }

        private boolean ensureEngine() {
            if (this.engine != null) {
                return true;
            }

            if (!(VoxyCommon.getInstance() instanceof VoxyClientInstance instance)) {
                return false;
            }

            WorldEngine engine = new WorldEngine(new TransientSectionStorage(), instance);
            engine.setSaveCallback(SableSubLevelVoxyManager::saveTransientSection);
            this.engine = engine;
            return true;
        }

        private boolean ensureRenderer() {
            if (this.renderer != null) {
                return true;
            }

            if (!VoxyConfig.CONFIG.isRenderingEnabled() || !this.ensureEngine()) {
                return false;
            }

            if (!(VoxyCommon.getInstance() instanceof VoxyClientInstance instance)) {
                return false;
            }

            this.renderer = new VoxyRenderSystem(this.engine, instance.getServiceManager());
            return true;
        }

        private void close() {
            if (this.renderer != null) {
                this.renderer.shutdown();
                this.renderer = null;
            }

            if (this.engine != null) {
                if (this.engine.isLive()) {
                    if (this.engine.isWorldUsed()) {
                        PENDING_ENGINE_FREES.add(this.engine);
                    } else {
                        this.engine.free();
                    }
                }
                this.engine = null;
            }

            this.chunkIdentities.clear();
        }

        private static LevelChunkSection createEmptySection(ClientLevel level) {
            Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
            PalettedContainer<BlockState> states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
            PalettedContainer<Holder<Biome>> biomes = new PalettedContainer<>(biomeRegistry.asHolderIdMap(), biomeRegistry.getHolderOrThrow(Biomes.PLAINS), PalettedContainer.Strategy.SECTION_BIOMES);
            return new LevelChunkSection(states, biomes);
        }
    }
}
