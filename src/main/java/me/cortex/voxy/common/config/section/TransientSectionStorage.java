package me.cortex.voxy.common.config.section;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

public class TransientSectionStorage extends SectionStorage {
    private static final ThreadLocalMemoryBuffer LOAD_SCRATCH =
            new ThreadLocalMemoryBuffer(SectionSerializationStorage.BIGGEST_SERIALIZED_SECTION_SIZE + 1024);

    private final Map<Long, byte[]> sectionData = new ConcurrentHashMap<>();
    private final Map<Integer, byte[]> idMappings = new ConcurrentHashMap<>();

    @Override
    public int loadSection(WorldSection into) {
        byte[] data = this.sectionData.get(into.key);
        if (data == null) {
            return 1;
        }

        MemoryBuffer scratch = LOAD_SCRATCH.get();
        ByteBuffer buffer = scratch.asByteBuffer();
        buffer.clear();
        buffer.put(data);

        MemoryBuffer view = MemoryBuffer.createUntrackedUnfreeableRawFrom(scratch.address, data.length);
        return SaveLoadSystem3.deserialize(into, view) ? 0 : -1;
    }

    @Override
    public void saveSection(WorldSection section) {
        MemoryBuffer serialized = SaveLoadSystem3.serialize(section);
        ByteBuffer buffer = serialized.asByteBuffer();
        byte[] copy = new byte[(int) serialized.size];
        buffer.get(copy);
        this.sectionData.put(section.key, copy);
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        ByteBuffer copySource = data.duplicate();
        byte[] copy = new byte[copySource.remaining()];
        copySource.get(copy);
        this.idMappings.put(id, copy);
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        Int2ObjectOpenHashMap<byte[]> copy = new Int2ObjectOpenHashMap<>(this.idMappings.size());
        for (Map.Entry<Integer, byte[]> entry : this.idMappings.entrySet()) {
            copy.put(entry.getKey().intValue(), entry.getValue());
        }
        return copy;
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        this.sectionData.clear();
        this.idMappings.clear();
    }

    @Override
    public void iteratePositions(int level, LongConsumer callback) {
        for (long key : this.sectionData.keySet()) {
            if (WorldEngine.getLevel(key) == level) {
                callback.accept(key);
            }
        }
    }
}
