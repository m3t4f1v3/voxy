package me.cortex.voxy.client.core.util;

import it.unimi.dsi.fastutil.ints.Int2ObjectFunction;
import me.cortex.voxy.common.util.HierarchicalBitSet;

public class ExpandingObjectAllocationList<T> {
    private static final float GROWTH_FACTOR = 0.75f;

    private final Int2ObjectFunction<T[]> arrayGenerator;
    private final HierarchicalBitSet bitSet;
    private T[] objects;//Should maybe make a getter function instead

    public ExpandingObjectAllocationList(Int2ObjectFunction<T[]> arrayGenerator) {
        this(arrayGenerator, -1);
    }
    public ExpandingObjectAllocationList(Int2ObjectFunction<T[]> arrayGenerator, int limit) {
        this.arrayGenerator = arrayGenerator;
        this.objects = this.arrayGenerator.apply(16);
        if (limit != -1) {
            this.bitSet = new HierarchicalBitSet(limit);
        } else {
            this.bitSet = new HierarchicalBitSet();
        }
    }

    public int put(T obj) {
        //Gets an unused id for some entry in objects, if its null fill it
        int id = this.bitSet.allocateNext();
        if (id < 0) {
            throw new IllegalStateException("Id over max request capacity");
        }
        if (this.objects.length <= id) {
            //Resize and copy over the objects array
            int newLen = this.objects.length + (int)Math.ceil(this.objects.length*GROWTH_FACTOR);
            T[] newArr = this.arrayGenerator.apply(newLen);
            System.arraycopy(this.objects, 0, newArr, 0, this.objects.length);
            this.objects = newArr;
        }
        this.objects[id] = obj;
        return id;
    }

    public void release(int id) {
        if (!this.bitSet.free(id)) {
            throw new IllegalArgumentException("Index " + id + " was already released");
        }
        this.objects[id] = null;
    }

    public T get(int index) {
        //Make the checking that index is allocated optional, as it might cause overhead due to multiple cacheline misses
        if (!this.bitSet.isSet(index)) {
            throw new IllegalArgumentException("Index " + index + " is not allocated");
        }
        return this.objects[index];
    }

    /**
     * bounds- and allocation-safe lookup. Returns null instead of throwing
     * when the id is out of range or has already been released, so callers can safely
     * probe a possibly-stale id while repairing state.
     */
    public T getOrNull(int index) {
        if (index < 0 || index >= this.objects.length) {
            return null;
        }
        if (!this.bitSet.isSet(index)) {
            return null;
        }
        return this.objects[index];
    }

    public int count() {
        return this.bitSet.getCount();
    }
}
