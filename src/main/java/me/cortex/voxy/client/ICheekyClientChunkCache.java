package me.cortex.voxy.client;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

public interface ICheekyClientChunkCache {
    @Nullable
    LevelChunk voxy$cheekyGetChunk(int x, int z);

    @Nullable
    LevelChunk voxy$getShadowChunk(int x, int z);

    void voxy$putShadowChunk(LevelChunk chunk);

    @Nullable
    LevelChunk voxy$removeShadowChunk(int x, int z);

    LongSet voxy$copyShadowChunkKeys();

    boolean voxy$isInStorageRange(int x, int z);
}
