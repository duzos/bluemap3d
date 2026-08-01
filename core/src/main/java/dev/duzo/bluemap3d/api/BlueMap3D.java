package dev.duzo.bluemap3d.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The registry. This plus {@link SceneObjectProvider} is the whole public API.
 *
 * <p>Call {@link #register(SceneObjectProvider)} from your mod's constructor or setup
 * event. Registration is safe before BlueMap has started: core picks up whatever is
 * registered when BlueMap's API becomes available, and anything registered later is
 * picked up on the next publish tick.
 *
 * <p>With no providers registered, core does nothing at all - it publishes no assets,
 * injects no script, and costs no ticks.
 */
public final class BlueMap3D {

    /** The core mod id. Addons declare a {@code required} dependency on this. */
    public static final String MOD_ID = "bluemap3d";

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D");
    private static final List<SceneObjectProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private BlueMap3D() {
    }

    /**
     * Registers a provider.
     *
     * <p>Thread-safe, and may be called at any point in the mod lifecycle.
     *
     * @param provider the provider to register
     * @throws IllegalArgumentException if a provider with the same
     *                                  {@link SceneObjectProvider#id()} is already
     *                                  registered
     */
    public static void register(SceneObjectProvider provider) {
        Objects.requireNonNull(provider, "provider");
        String id = Objects.requireNonNull(provider.id(), "provider.id()");

        for (SceneObjectProvider existing : PROVIDERS) {
            if (existing.id().equals(id)) {
                throw new IllegalArgumentException(
                        "A SceneObjectProvider with id '" + id + "' is already registered: "
                                + existing.getClass().getName());
            }
        }
        PROVIDERS.add(provider);
        LOGGER.info("Registered SceneObjectProvider '{}' ({})", id, provider.getClass().getName());
    }

    /**
     * Unregisters a provider previously passed to {@link #register}.
     *
     * <p>Its objects are removed from the map on the next publish tick.
     *
     * @param provider the provider to remove
     * @return whether it was registered
     */
    public static boolean unregister(SceneObjectProvider provider) {
        return PROVIDERS.remove(provider);
    }

    /**
     * The registered providers, in registration order.
     *
     * <p>Safe to iterate without locking. Intended for core; addons have no reason to
     * call this.
     *
     * @return an unmodifiable live view
     */
    public static Collection<SceneObjectProvider> providers() {
        return List.copyOf(PROVIDERS);
    }

    /** Whether anything is registered. Core short-circuits its whole tick when false. */
    public static boolean hasProviders() {
        return !PROVIDERS.isEmpty();
    }

    // ---------------------------------------------------------------------------------
    // Tile refresh
    // ---------------------------------------------------------------------------------

    private static volatile TileRefresher refresher;

    /**
     * Asks BlueMap to re-render the terrain tile containing a position.
     *
     * <p>For when one of your objects changes the world rather than just moving through it:
     * a turtle mining or placing a block, a Create train assembling or disassembling. Those
     * edit real chunks, and until the tile is re-rendered the map keeps showing the terrain
     * as it was.
     *
     * <p>Cheap to call often and safe to call on the server thread. Positions are coalesced
     * into tiles and batched, so a turtle clearing a hundred blocks in one tile costs one
     * update, not a hundred. Calling it when BlueMap is not running does nothing.
     *
     * @param level the level the change happened in
     * @param pos   any position inside the changed area
     */
    public static void refreshArea(net.minecraft.server.level.ServerLevel level,
                                   net.minecraft.core.BlockPos pos) {
        TileRefresher current = refresher;
        if (current != null) {
            current.refresh(level, pos);
        }
    }

    /**
     * How {@link #refreshArea} reaches BlueMap.
     *
     * <p>Implemented by core, not by addons. It exists as an interface only so that the
     * registry does not have to depend on core's runtime package.
     */
    @FunctionalInterface
    public interface TileRefresher {
        void refresh(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos);
    }

    /**
     * Core-internal. Installs the implementation behind {@link #refreshArea}, and clears it
     * with {@code null} when BlueMap shuts down. Addons must not call this.
     */
    public static void setTileRefresher(TileRefresher implementation) {
        refresher = implementation;
    }
}
