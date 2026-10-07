package dev.duzo.bluemap3d.runtime;

import dev.duzo.bluemap3d.api.BlockAppearance;
import dev.duzo.bluemap3d.api.BlockAppearanceResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The registry behind {@link dev.duzo.bluemap3d.api.BlueMap3D#registerAppearanceResolver}
 * and the one place resolvers are called from.
 *
 * <p>Every call here is guarded, because a resolver is somebody else's code reading
 * somebody else's block entity data, and the callers are a mesh bake and a publish tick.
 * A throwing resolver must cost one block its appearance, not a ship its mesh or the
 * whole server its publish pass. The first failure of each resolver class is logged with a
 * stack trace and the rest are silent, for the same reason the mesher does not log per
 * block: a bad resolver fails for every block it handles, every interval.
 *
 * <p>Not API. Addons reach it through {@code BlueMap3D}.
 */
public final class Appearances {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Appearances");
    private static final List<BlockAppearanceResolver> RESOLVERS = new CopyOnWriteArrayList<>();
    /** Resolver classes already reported, so a broken one logs once and not per block. */
    private static final Set<Class<?>> REPORTED = ConcurrentHashMap.newKeySet();

    private Appearances() {
    }

    /** Adds a resolver. Order is registration order, and the first to handle a state wins. */
    public static void register(BlockAppearanceResolver resolver) {
        RESOLVERS.add(resolver);
        LOGGER.info("Registered BlockAppearanceResolver {}", resolver.getClass().getName());
    }

    /** Removes a resolver, returning whether it was registered. */
    public static boolean unregister(BlockAppearanceResolver resolver) {
        return RESOLVERS.remove(resolver);
    }

    /** Whether any resolver is registered. False means every method here is a no-op. */
    public static boolean any() {
        return !RESOLVERS.isEmpty();
    }

    /** Whether some resolver claims this state. */
    public static boolean handled(BlockState state) {
        return resolverFor(state) != null;
    }

    /** The appearance of a block, or {@code null} if nothing handles it or the resolver declines. */
    @Nullable
    public static BlockAppearance resolve(BlockState state, CompoundTag blockEntityTag) {
        BlockAppearanceResolver resolver = resolverFor(state);
        if (resolver == null) {
            return null;
        }
        try {
            return resolver.resolve(state, blockEntityTag == null ? new CompoundTag() : blockEntityTag);
        } catch (RuntimeException | LinkageError e) {
            report(resolver, e);
            return null;
        }
    }

    /**
     * The appearance of a live block entity, or {@code null} if nothing handles its block, it
     * has no level, or anything throws.
     *
     * <p>The save is inside the same guard as the resolve. A block entity with no level
     * cannot be serialised - it needs the level's registry access - and one mid-removal can be
     * exactly that; a block entity whose own save throws must likewise cost one block its
     * appearance, not a ship its bake every interval.
     */
    @Nullable
    public static BlockAppearance resolve(BlockState state, BlockEntity blockEntity) {
        BlockAppearanceResolver resolver = resolverFor(state);
        if (resolver == null || blockEntity.getLevel() == null) {
            return null;
        }
        try {
            return resolver.resolve(state,
                    blockEntity.saveWithoutMetadata(blockEntity.getLevel().registryAccess()));
        } catch (RuntimeException | LinkageError e) {
            report(resolver, e);
            return null;
        }
    }

    /** The appearance fingerprint of a block's saved data, or 0 if nothing handles it. */
    public static long fingerprint(BlockState state, @Nullable CompoundTag blockEntityTag) {
        BlockAppearanceResolver resolver = resolverFor(state);
        if (resolver == null) {
            return 0L;
        }
        try {
            return resolver.fingerprint(blockEntityTag == null ? new CompoundTag() : blockEntityTag);
        } catch (RuntimeException | LinkageError e) {
            report(resolver, e);
            return 0L;
        }
    }

    /** The appearance fingerprint of a live block entity, or 0 if nothing handles its block. */
    public static long fingerprint(BlockEntity blockEntity) {
        BlockAppearanceResolver resolver = resolverFor(blockEntity.getBlockState());
        if (resolver == null) {
            return 0L;
        }
        try {
            return resolver.fingerprint(blockEntity);
        } catch (RuntimeException | LinkageError e) {
            report(resolver, e);
            return 0L;
        }
    }

    /**
     * One number for every appearance in a chunk, for a provider to fold into its version.
     *
     * <p>Each handled block entity contributes {@code mix(pos, fingerprint)} and the
     * contributions are XORed, so the result does not depend on the order the chunk lists
     * its block entities in, and two identical skins at different positions do not cancel.
     * Returns 0 at once when no resolver is registered, which is the common case and must
     * cost nothing: this runs every publish interval for every chunk of every ship.
     */
    public static long fingerprint(LevelChunk chunk) {
        if (RESOLVERS.isEmpty()) {
            return 0L;
        }
        long hash = 0L;
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockEntity be = entry.getValue();
            BlockAppearanceResolver resolver = resolverFor(be.getBlockState());
            if (resolver == null) {
                continue;
            }
            long fp = 0L;
            try {
                fp = resolver.fingerprint(be);
            } catch (RuntimeException | LinkageError e) {
                report(resolver, e);
            }
            hash ^= mix(mix(0xcbf29ce484222325L, entry.getKey().asLong()), fp);
        }
        return hash;
    }

    @Nullable
    private static BlockAppearanceResolver resolverFor(BlockState state) {
        for (BlockAppearanceResolver resolver : RESOLVERS) {
            try {
                if (resolver.handles(state)) {
                    return resolver;
                }
            } catch (RuntimeException | LinkageError e) {
                report(resolver, e);
            }
        }
        return null;
    }

    private static void report(BlockAppearanceResolver resolver, Throwable e) {
        if (REPORTED.add(resolver.getClass())) {
            LOGGER.warn("BlockAppearanceResolver {} failed; its blocks fall back to their own "
                    + "model. Further failures from it are not logged.",
                    resolver.getClass().getName(), e);
        }
    }

    /** FNV-1a's mixing step. */
    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }
}
