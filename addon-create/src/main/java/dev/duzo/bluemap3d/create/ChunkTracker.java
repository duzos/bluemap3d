package dev.duzo.bluemap3d.create;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps track of which chunks are loaded, per level.
 *
 * <p>Same purpose and the same shape as addon-turtles' own {@code ChunkTracker}: there is
 * no public way to enumerate a {@link ServerLevel}'s loaded chunks - {@code ChunkMap} is
 * internal - so watching the load and unload events is the only way to get the answer
 * using public API. Kept as its own copy here rather than shared, since the two addons
 * are independent Gradle modules with no dependency between them.
 *
 * <p>{@link BearingProvider} uses this to slice a discovery scan across publishes rather
 * than walking every loaded chunk every time - see that class for why.
 */
public final class ChunkTracker {

    private final Map<ServerLevel, Set<ChunkPos>> loaded = new ConcurrentHashMap<>();

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        ServerLevel level = serverLevelOf(event.getLevel());
        if (level != null) {
            loaded.computeIfAbsent(level, key -> ConcurrentHashMap.newKeySet())
                    .add(event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        ServerLevel level = serverLevelOf(event.getLevel());
        if (level != null) {
            Set<ChunkPos> set = loaded.get(level);
            if (set != null) {
                set.remove(event.getChunk().getPos());
            }
        }
    }

    /** The currently loaded chunks in a level. Never {@code null}. */
    public Set<ChunkPos> loadedChunks(ServerLevel level) {
        return loaded.getOrDefault(level, Collections.emptySet());
    }

    /** Drops everything. Called when the server stops so levels are not held alive. */
    public void clear() {
        loaded.clear();
    }

    private static ServerLevel serverLevelOf(LevelAccessor accessor) {
        return accessor instanceof ServerLevel serverLevel ? serverLevel : null;
    }
}
