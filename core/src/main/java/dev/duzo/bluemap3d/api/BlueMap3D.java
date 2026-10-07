package dev.duzo.bluemap3d.api;

import dev.duzo.bluemap3d.runtime.Appearances;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
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
    // Block appearances
    // ---------------------------------------------------------------------------------

    /**
     * Registers a resolver for blocks whose look lives in their block entity.
     *
     * <p>Thread-safe, and may be called at any point in the mod lifecycle. Nothing is
     * re-meshed when a resolver arrives late, but every mesh baked afterwards sees it.
     *
     * @param resolver the resolver to add; the first registered one that handles a state wins
     * @see BlockAppearanceResolver
     */
    public static void registerAppearanceResolver(BlockAppearanceResolver resolver) {
        Appearances.register(Objects.requireNonNull(resolver, "resolver"));
    }

    /**
     * Unregisters a resolver previously passed to {@link #registerAppearanceResolver}.
     *
     * @return whether it was registered
     */
    public static boolean unregisterAppearanceResolver(BlockAppearanceResolver resolver) {
        return Appearances.unregister(resolver);
    }

    /** Whether a registered resolver claims this state. */
    public static boolean hasAppearance(BlockState state) {
        return Appearances.handled(state);
    }

    /**
     * The appearance of a block from its saved block entity data, for a provider that holds
     * block entities as tags and builds its own {@link BlockVolume#of(java.util.Map,
     * net.minecraft.world.phys.Vec3, java.util.Collection, java.util.Map) volume}.
     *
     * @return the appearance, or {@code null} if no resolver handles the state or the
     *         resolver declines
     */
    public static BlockAppearance resolveAppearance(BlockState state, CompoundTag blockEntityTag) {
        return Appearances.resolve(state, blockEntityTag);
    }

    /**
     * The fingerprint of one block's saved data, for a provider that holds block entities as
     * tags. {@code 0} when no resolver handles the state.
     *
     * @see BlockAppearanceResolver#fingerprint(CompoundTag)
     */
    public static long appearanceFingerprint(BlockState state, CompoundTag blockEntityTag) {
        return Appearances.fingerprint(state, blockEntityTag);
    }

    /**
     * The fingerprint of one live block entity. {@code 0} when no resolver handles it.
     *
     * @see BlockAppearanceResolver#fingerprint(BlockEntity)
     */
    public static long appearanceFingerprint(BlockEntity blockEntity) {
        return Appearances.fingerprint(blockEntity);
    }

    /**
     * One number for every handled appearance in a chunk, for a provider that reads a level
     * to fold into its {@link SceneObject#geometryVersion()}. Order-independent, and
     * {@code 0} - at no cost - when no resolver is registered.
     */
    public static long appearanceFingerprint(LevelChunk chunk) {
        return Appearances.fingerprint(chunk);
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
