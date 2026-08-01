package dev.duzo.bluemap3d.runtime;

import com.google.gson.stream.JsonWriter;
import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.BlueMap3D;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import dev.duzo.bluemap3d.bake.BakedMesh;
import dev.duzo.bluemap3d.bake.VolumeMesher;
import dev.duzo.bluemap3d.publish.WebRootPublisher;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives the whole thing: asks providers what exists, bakes what has changed, and
 * publishes transforms.
 *
 * <p>The tick is deliberately cheap. Baking a volume is not - a ship hull is hundreds of
 * thousands of vertices - so bakes are queued onto a worker thread and the tick never
 * waits for one. That is safe because every {@link BlockVolume} factory returns a
 * snapshot rather than a live view of the level.
 *
 * <p>While a re-bake is in flight the object keeps rendering its previous mesh, so a
 * train gaining a carriage does not blink out of existence for a second.
 */
public final class SceneObjectTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Tracker");

    private final WebRootPublisher publisher;
    private final VolumeMesher mesher;
    private final MapLookup mapLookup;
    private final ExecutorService baker;

    /**
     * Resolves a level to the BlueMap map ids that show it.
     *
     * <p>The webapp cannot work this out for itself: BlueMap's client-side {@code Map}
     * object carries an id, a name and its data urls, but nothing identifying which
     * world it renders. So the server publishes the mapping and the browser uses it to
     * hide objects that belong to a dimension the viewer is not looking at.
     */
    @FunctionalInterface
    public interface MapLookup {
        List<String> mapIdsFor(ServerLevel level);
    }

    /** Meshes that are published and ready, keyed by {@code provider/object}. */
    private final Map<String, Ready> ready = new ConcurrentHashMap<>();
    /** Keys with a bake in flight, so the same work is not queued twice. */
    private final Set<String> baking = ConcurrentHashMap.newKeySet();

    private int tickCounter;
    private int lastPublishedCount = -1;

    private record Ready(long version, String meshUrl) {
    }

    /** Supplies the tile-refresh version published to the browser. */
    private java.util.function.IntSupplier tilesVersion = () -> 0;
    /** Supplies the tiles the browser has not yet been told about. */
    private java.util.function.Supplier<Map<String, List<int[]>>> dirtyTiles = Map::of;

    public void setTilesVersionSupplier(java.util.function.IntSupplier supplier) {
        this.tilesVersion = supplier;
    }

    public void setDirtyTilesSupplier(java.util.function.Supplier<Map<String, List<int[]>>> supplier) {
        this.dirtyTiles = supplier;
    }

    public SceneObjectTracker(WebRootPublisher publisher, VolumeMesher mesher, MapLookup mapLookup) {
        this.publisher = publisher;
        this.mesher = mesher;
        this.mapLookup = mapLookup;
        this.baker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BlueMap3D-Baker");
            thread.setDaemon(true);
            // Below normal: a mesh arriving a tick later is invisible to a viewer, but
            // stealing time from the server tick is not.
            thread.setPriority(Thread.NORM_PRIORITY - 2);
            return thread;
        });
    }

    /**
     * Called every server tick. Does nothing until the publish interval elapses, and
     * nothing at all when no providers are registered.
     */
    public void tick(MinecraftServer server, int intervalTicks) {
        if (!BlueMap3D.hasProviders()) {
            return;
        }
        if (++tickCounter < intervalTicks) {
            return;
        }
        tickCounter = 0;

        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, List<String>> dimensionMaps = new java.util.LinkedHashMap<>();

        for (ServerLevel level : server.getAllLevels()) {
            boolean any = false;
            for (SceneObjectProvider provider : BlueMap3D.providers()) {
                any |= collect(provider, level, rows, seen);
            }
            if (any) {
                dimensionMaps.computeIfAbsent(level.dimension().location().toString(),
                        key -> mapLookup.mapIdsFor(level));
            }
        }

        // Anything that has gone away loses its mesh files.
        for (String key : List.copyOf(ready.keySet())) {
            if (!seen.contains(key)) {
                ready.remove(key);
                int slash = key.indexOf('/');
                publisher.deleteMeshes(key.substring(0, slash), key.substring(slash + 1));
            }
        }

        publish(rows, dimensionMaps, intervalTicks);
    }

    /** @return whether this provider reported anything at all in this level */
    private boolean collect(SceneObjectProvider provider, ServerLevel level,
                            List<Row> rows, Set<String> seen) {
        String providerId = provider.id();
        Iterable<? extends SceneObject> objects;
        try {
            objects = provider.objects(level);
        } catch (RuntimeException e) {
            // A misbehaving addon must not take down the tick or the other providers.
            LOGGER.error("Provider '{}' threw while listing objects", providerId, e);
            return false;
        }

        boolean any = false;
        for (SceneObject object : objects) {
            any = true;
            String key = providerId + "/" + object.id();
            seen.add(key);

            long version = object.geometryVersion();
            Ready current = ready.get(key);

            if (current == null || current.version() != version) {
                requestBake(providerId, object, key, version);
            }
            if (current != null) {
                // Render the mesh we have, even if a newer one is on its way.
                rows.add(new Row(providerId, object.id(), object.label(),
                        object.dimension().location().toString(),
                        current.meshUrl(), object.position(), object.rotation()));
            }
        }
        return any;
    }

    /**
     * Snapshots the geometry on the server thread and queues the mesh off it.
     */
    private void requestBake(String providerId, SceneObject object, String key, long version) {
        if (!baking.add(key)) {
            return;
        }
        BlockVolume volume;
        try {
            volume = object.geometry();
        } catch (RuntimeException e) {
            LOGGER.error("Provider '{}' threw building geometry for '{}'", providerId, object.id(), e);
            baking.remove(key);
            return;
        }
        String objectId = object.id();

        baker.execute(() -> {
            try {
                BakedMesh mesh = mesher.mesh(volume);
                if (mesh.isEmpty()) {
                    LOGGER.debug("'{}' meshed to nothing; not publishing", key);
                    return;
                }
                String url = publisher.writeMesh(providerId, objectId, version, mesh);
                Ready previous = ready.put(key, new Ready(version, url));
                if (previous != null && previous.version() != version) {
                    // The old mesh is unreferenced now that the feed points at the new
                    // url. Deleting only the superseded file, not the whole object.
                    publisher.deleteMeshVersion(providerId, objectId, previous.version());
                }
                LOGGER.debug("Published {} v{}: {} triangles", key, version, mesh.triangleCount());
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Failed to bake or publish '{}'", key, e);
            } finally {
                baking.remove(key);
            }
        });
    }

    private void publish(List<Row> rows, Map<String, List<String>> dimensionMaps, int intervalTicks) {
        try {
            publisher.writeFeed(toJson(rows, dimensionMaps, intervalTicks,
                    tilesVersion.getAsInt(), dev.duzo.bluemap3d.Config.TILE_RELOAD_MIN_SECONDS.get(),
                    dirtyTiles.get()));
            if (rows.size() != lastPublishedCount) {
                LOGGER.info("Publishing {} 3D object(s)", rows.size());
                lastPublishedCount = rows.size();
            }
        } catch (IOException e) {
            LOGGER.error("Could not write the live feed", e);
        }
    }

    /**
     * The live feed. Positions and rotations only: geometry is already in the browser,
     * so this stays a few dozen bytes per object however large the object is.
     */
    private static String toJson(List<Row> rows, Map<String, List<String>> dimensionMaps,
                                 int intervalTicks, int tilesVersion,
                                 int tileReloadMinSeconds,
                                 Map<String, List<int[]>> dirtyTiles) throws IOException {
        StringWriter out = new StringWriter(64 + rows.size() * 160);
        try (JsonWriter json = new JsonWriter(out)) {
            json.beginObject();
            // Lets the browser size its interpolation window to the real publish rate
            // instead of guessing.
            json.name("intervalMs").value(intervalTicks * 50L);

            // Bumped whenever terrain tiles were queued for re-render. The browser watches
            // this to know its downloaded terrain is stale, because BlueMap's webapp never
            // works that out for itself.
            json.name("tilesVersion").value(tilesVersion);
            // Rate limit for that reload, server-controlled so an admin can tune it without
            // anyone editing the injected script. 0 disables terrain reloading.
            json.name("tileReloadMinMs").value(tileReloadMinSeconds * 1000L);

            // The exact tiles that changed, per map, in the map's own hires tile
            // coordinates. The browser replaces just these instead of dropping every tile
            // it has, which is the difference between a live update and the whole map
            // blinking out and fading back in.
            json.name("dirtyTiles").beginObject();
            for (Map.Entry<String, List<int[]>> entry : dirtyTiles.entrySet()) {
                json.name(entry.getKey()).beginArray();
                for (int[] tile : entry.getValue()) {
                    json.beginArray().value(tile[0]).value(tile[1]).endArray();
                }
                json.endArray();
            }
            json.endObject();

            // dimension -> the map ids that render it, so the browser can hide objects
            // belonging to a world it is not currently looking at.
            json.name("maps").beginObject();
            for (Map.Entry<String, List<String>> entry : dimensionMaps.entrySet()) {
                json.name(entry.getKey()).beginArray();
                for (String mapId : entry.getValue()) {
                    json.value(mapId);
                }
                json.endArray();
            }
            json.endObject();

            json.name("objects").beginArray();
            for (Row row : rows) {
                json.beginObject();
                json.name("id").value(row.provider() + "/" + row.id());
                json.name("provider").value(row.provider());
                json.name("dimension").value(row.dimension());
                json.name("mesh").value(row.meshUrl());
                if (row.label() != null) {
                    json.name("label").value(row.label());
                }
                json.name("pos").beginArray()
                        .value(round(row.position().x))
                        .value(round(row.position().y))
                        .value(round(row.position().z))
                        .endArray();
                Quaternionf rotation = row.rotation();
                json.name("rot").beginArray()
                        .value(round(rotation.x))
                        .value(round(rotation.y))
                        .value(round(rotation.z))
                        .value(round(rotation.w))
                        .endArray();
                json.endObject();
            }
            json.endArray();
            json.endObject();
        }
        return out.toString();
    }

    /** Millimetre precision is well past what a pixel can show, and halves the payload. */
    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** Shuts the baker down. Called when BlueMap disables or the server stops. */
    public void shutdown() {
        baker.shutdownNow();
        ready.clear();
        baking.clear();
    }

    private record Row(String provider, String id, String label, String dimension,
                       String meshUrl, Vec3 position, Quaternionf rotation) {
    }
}
