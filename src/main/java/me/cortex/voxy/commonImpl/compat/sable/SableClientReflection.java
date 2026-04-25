package me.cortex.voxy.commonImpl.compat.sable;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class SableClientReflection {
    private static SableClientReflection instance;

    private final boolean available;
    private final Method getContainer;
    private final Method getAllSubLevels;
    private final Method inBounds;
    private final Method isRemoved;
    private final Method isFinalized;
    private final Method getUniqueId;
    private final Method getPlot;
    private final Method plotContains;
    private final Method getLoadedChunks;
    private final Method getChunk;
    private final Method renderPose;
    private final Method transformPositionInverse;
    private final Method orientation;

    private SableClientReflection(
            boolean available,
            Method getContainer,
            Method getAllSubLevels,
            Method inBounds,
            Method isRemoved,
            Method isFinalized,
            Method getUniqueId,
            Method getPlot,
            Method plotContains,
            Method getLoadedChunks,
            Method getChunk,
            Method renderPose,
            Method transformPositionInverse,
            Method orientation
    ) {
        this.available = available;
        this.getContainer = getContainer;
        this.getAllSubLevels = getAllSubLevels;
        this.inBounds = inBounds;
        this.isRemoved = isRemoved;
        this.isFinalized = isFinalized;
        this.getUniqueId = getUniqueId;
        this.getPlot = getPlot;
        this.plotContains = plotContains;
        this.getLoadedChunks = getLoadedChunks;
        this.getChunk = getChunk;
        this.renderPose = renderPose;
        this.transformPositionInverse = transformPositionInverse;
        this.orientation = orientation;
    }

    public static @Nullable SableClientReflection get() {
        SableClientReflection reflection = instance;
        if (reflection == null) {
            reflection = create();
            instance = reflection;
        }
        return reflection.available ? reflection : null;
    }

    private static SableClientReflection create() {
        try {
            Class<?> containerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            Class<?> subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
            Class<?> clientSubLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel");
            Class<?> plotClass = Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot");
            Class<?> plotChunkHolderClass = Class.forName("dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder");

            Method renderPose = clientSubLevelClass.getMethod("renderPose");
            Class<?> poseClass = renderPose.getReturnType();

            return new SableClientReflection(
                    true,
                    containerClass.getMethod("getContainer", Level.class),
                    containerClass.getMethod("getAllSubLevels"),
                    containerClass.getMethod("inBounds", int.class, int.class),
                    subLevelClass.getMethod("isRemoved"),
                    clientSubLevelClass.getMethod("isFinalized"),
                    subLevelClass.getMethod("getUniqueId"),
                    subLevelClass.getMethod("getPlot"),
                    plotClass.getMethod("contains", ChunkPos.class),
                    plotClass.getMethod("getLoadedChunks"),
                    plotChunkHolderClass.getMethod("getChunk"),
                    renderPose,
                    findVectorTransformMethod(poseClass, "transformPositionInverse"),
                    poseClass.getMethod("orientation")
            );
        } catch (ReflectiveOperationException e) {
            return unavailable();
        }
    }

    private static Method findVectorTransformMethod(Class<?> owner, String name) {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) {
                continue;
            }

            if (method.getParameterTypes()[0].isAssignableFrom(Vector3d.class)) {
                return method;
            }
        }
        throw new IllegalStateException("Unable to find method " + owner.getName() + "#" + name + "(Vector3d)");
    }

    private static SableClientReflection unavailable() {
        return new SableClientReflection(false, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public boolean isPlotChunk(ClientLevel level, int chunkX, int chunkZ) {
        Object container = this.getContainer(level);
        return container != null && this.invokeBoolean(this.inBounds, container, chunkX, chunkZ);
    }

    public @Nullable Object findSubLevelForChunk(ClientLevel level, int chunkX, int chunkZ) {
        Object container = this.getContainer(level);
        if (container == null || !this.invokeBoolean(this.inBounds, container, chunkX, chunkZ)) {
            return null;
        }

        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        for (Object subLevel : this.getAllSubLevels(container)) {
            if (this.isRemoved(subLevel)) {
                continue;
            }

            Object plot = this.getPlot(subLevel);
            if (plot != null && this.invokeBoolean(this.plotContains, plot, chunkPos)) {
                return subLevel;
            }
        }
        return null;
    }

    public List<?> getAllSubLevels(ClientLevel level) {
        Object container = this.getContainer(level);
        return container == null ? List.of() : this.getAllSubLevels(container);
    }

    private List<?> getAllSubLevels(Object container) {
        try {
            return (List<?>) this.getAllSubLevels.invoke(container);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private @Nullable Object getContainer(ClientLevel level) {
        try {
            return this.getContainer.invoke(null, level);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isRemoved(Object subLevel) {
        return this.invokeBoolean(this.isRemoved, subLevel);
    }

    public boolean isFinalized(Object subLevel) {
        return this.invokeBoolean(this.isFinalized, subLevel);
    }

    public UUID getUniqueId(Object subLevel) {
        try {
            return (UUID) this.getUniqueId.invoke(subLevel);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public Collection<?> getLoadedChunkHolders(Object subLevel) {
        Object plot = this.getPlot(subLevel);
        if (plot == null) {
            return List.of();
        }

        try {
            return (Collection<?>) this.getLoadedChunks.invoke(plot);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public @Nullable LevelChunk getChunk(Object holder) {
        try {
            return (LevelChunk) this.getChunk.invoke(holder);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public Vector3d transformPositionInverse(Object subLevel, double x, double y, double z) {
        Object pose = this.getRenderPose(subLevel);
        Vector3d camera = new Vector3d(x, y, z);
        try {
            Object result = this.transformPositionInverse.invoke(pose, camera);
            return result instanceof Vector3d transformed ? transformed : camera;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public Quaterniondc getOrientation(Object subLevel) {
        Object pose = this.getRenderPose(subLevel);
        try {
            return (Quaterniondc) this.orientation.invoke(pose);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private @Nullable Object getPlot(Object subLevel) {
        try {
            return this.getPlot.invoke(subLevel);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private Object getRenderPose(Object subLevel) {
        try {
            return this.renderPose.invoke(subLevel);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean invokeBoolean(Method method, Object owner, Object... args) {
        try {
            return (boolean) method.invoke(owner, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
