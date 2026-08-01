package dev.duzo.bluemap3d;

import de.bluecolored.bluemap.api.BlueMapAPI;
import dev.duzo.bluemap3d.api.BlueMap3D;
import dev.duzo.bluemap3d.bake.AssetIndex;
import dev.duzo.bluemap3d.bake.BlockModelSource;
import dev.duzo.bluemap3d.bake.MapColorSource;
import dev.duzo.bluemap3d.bake.ResourcePackSource;
import dev.duzo.bluemap3d.bake.VolumeMesher;
import dev.duzo.bluemap3d.publish.HiddenBlockPack;
import dev.duzo.bluemap3d.publish.TileRefreshQueue;
import dev.duzo.bluemap3d.publish.WebRootPublisher;
import dev.duzo.bluemap3d.runtime.SceneObjectTracker;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Core's entry point.
 *
 * <p>Does nothing until two things are true: BlueMap has started, and at least one addon
 * has registered a {@link dev.duzo.bluemap3d.api.SceneObjectProvider}. With no addons
 * installed this mod loads, logs one line, and never touches a tick again.
 */
@Mod(BlueMap3D.MOD_ID)
public final class BlueMap3DMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D");

    private volatile SceneObjectTracker tracker;
    private volatile AssetIndex assets;
    private volatile TileRefreshQueue refreshQueue;

    public BlueMap3DMod(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        NeoForge.EVENT_BUS.register(this);

        // BlueMap starts well after mod construction, and may restart on /bluemap reload,
        // so this is a subscription rather than a one-off.
        BlueMapAPI.onEnable(this::onBlueMapEnable);
        BlueMapAPI.onDisable(api -> shutdown());

        LOGGER.info("BlueMap3D loaded. Waiting for BlueMap and at least one addon.");
    }

    private void onBlueMapEnable(BlueMapAPI api) {
        try {
            WebRootPublisher publisher = WebRootPublisher.install(api);
            if (publisher == null) {
                return;
            }

            // BlueMap's own directory is the parent of its web root. That is where it
            // keeps the client jar it downloaded and the resource extensions it unpacked,
            // which is everything needed to texture blocks on a server that has no
            // client assets of its own.
            Path blueMapRoot = api.getWebApp().getWebRoot().getParent();

            List<BlockModelSource> sources = new ArrayList<>();
            if (Config.USE_RESOURCE_PACKS.get()) {
                assets = AssetIndex.open(configuredSources(), blueMapRoot);
                sources.add(new ResourcePackSource(assets));
            }
            // Always last, and always present: every block gets at least a shaped cube.
            sources.add(new MapColorSource());

            VolumeMesher mesher = new VolumeMesher(sources, Config.MAX_BLOCKS_PER_OBJECT.get());
            tracker = new SceneObjectTracker(publisher, mesher, level -> mapIdsFor(api, level));

            // Normally already written before BlueMap loaded, by onServerAboutToStart. This
            // is the safety net for a layout the guess got wrong, and no-ops when the pack
            // is already correct. blueMapRoot is the *data* directory; packs live under the
            // config one, which is why the candidate list matters.
            if (Config.HIDE_LIVE_BLOCKS.get()) {
                HiddenBlockPack.write(blueMapRoots(blueMapRoot), hiddenBlocks());
            }

            TileRefreshQueue queue = new TileRefreshQueue(api);
            refreshQueue = queue;
            BlueMap3D.setTileRefresher(queue);
            tracker.setTilesVersionSupplier(queue::version);
            tracker.setDirtyTilesSupplier(queue::drainUndelivered);

            LOGGER.info("BlueMap3D is live (BlueMap {}), {} provider(s) registered",
                    api.getBlueMapVersion(), BlueMap3D.providers().size());
        } catch (RuntimeException e) {
            LOGGER.error("BlueMap3D failed to start; 3D objects are disabled", e);
            shutdown();
        }
    }

    /**
     * Writes the hidden-block pack before BlueMap loads its resources.
     *
     * <p>Timing is the whole point of doing it here. BlueMap reads its packs folder while
     * starting up, and it only hands out its API afterwards - so a pack written from the API
     * callback is always one BlueMap start too late, and the first render of every map draws
     * the blocks we are about to draw live. Writing it now, before BlueMap has started,
     * means it applies to the very first render and nobody has to run
     * {@code /bluemap reload} or purge anything.
     *
     * <p>The cost is guessing BlueMap's directory rather than asking for it. On NeoForge it
     * is {@code <gamedir>/bluemap}, which is where the observed install puts it. If that
     * guess is ever wrong the API callback rewrites the pack at the real path, which is
     * correct but needs the reload.
     */
    @SubscribeEvent
    public void onServerAboutToStart(net.neoforged.neoforge.event.server.ServerAboutToStartEvent event) {
        if (!BlueMap3D.hasProviders() || !Config.HIDE_LIVE_BLOCKS.get()) {
            return;
        }
        try {
            // Written even if the directories do not exist yet: on a fresh install BlueMap
            // creates them during its own startup, which is after this. Requiring them to
            // be there first would put us back to applying one start late, which is the
            // exact problem this exists to avoid.
            HiddenBlockPack.write(blueMapRoots(null), hiddenBlocks());
        } catch (RuntimeException e) {
            LOGGER.debug("Could not pre-write the hidden-block pack: {}", e.toString());
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        SceneObjectTracker current = tracker;
        if (current != null) {
            current.tick(event.getServer(), Config.PUBLISH_INTERVAL_TICKS.get());
        }
        TileRefreshQueue queue = refreshQueue;
        if (queue != null) {
            queue.tick();
        }
    }

    /**
     * Where BlueMap might keep its packs folder, most likely first.
     *
     * <p>BlueMap has two roots. The <b>config</b> directory holds {@code maps/},
     * {@code storages/}, {@code plugin.conf} and {@code packs/} - that last one is what
     * {@code getPacksFolder()} returns, so it is the one that matters. The <b>data</b>
     * directory holds {@code web/} and the downloaded client jar, and is what the API's
     * {@code getWebRoot().getParent()} gives you.
     *
     * <p>They are easy to mistake for each other, and getting it wrong fails silently: the
     * pack is written to a folder BlueMap never reads, with no error to say so.
     *
     * @param dataRoot the data directory if known, else {@code null}
     */
    private static List<Path> blueMapRoots(Path dataRoot) {
        java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
        roots.add(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("bluemap"));
        if (dataRoot != null) {
            roots.add(dataRoot);
        }
        roots.add(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("bluemap"));
        return List.copyOf(roots);
    }

    /** Every block that any registered provider draws itself. */
    private static java.util.Collection<net.minecraft.resources.ResourceLocation> hiddenBlocks() {
        java.util.Set<net.minecraft.resources.ResourceLocation> out = new java.util.LinkedHashSet<>();
        for (var provider : BlueMap3D.providers()) {
            try {
                out.addAll(provider.hiddenBlocks());
            } catch (RuntimeException e) {
                LOGGER.error("Provider '{}' threw listing hidden blocks", provider.id(), e);
            }
        }
        return out;
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        shutdown();
    }

    private synchronized void shutdown() {
        BlueMap3D.setTileRefresher(null);
        refreshQueue = null;
        if (tracker != null) {
            tracker.shutdown();
            tracker = null;
        }
        if (assets != null) {
            assets.close();
            assets = null;
        }
    }

    /**
     * The BlueMap map ids that render a level.
     *
     * <p>{@code getWorld} takes an untyped platform object, and on NeoForge that is a
     * {@link net.minecraft.server.level.ServerLevel}. A world can back several maps
     * (different render presets of the same dimension), so this is a list.
     */
    private static List<String> mapIdsFor(BlueMapAPI api, net.minecraft.server.level.ServerLevel level) {
        try {
            return api.getWorld(level)
                    .map(world -> world.getMaps().stream()
                            .map(de.bluecolored.bluemap.api.BlueMapMap::getId)
                            .toList())
                    .orElse(List.of());
        } catch (RuntimeException e) {
            LOGGER.debug("Could not resolve BlueMap maps for {}: {}",
                    level.dimension().location(), e.toString());
            return List.of();
        }
    }

    private static List<Path> configuredSources() {
        List<Path> paths = new ArrayList<>();
        for (String entry : Config.ASSET_SOURCES.get()) {
            if (entry != null && !entry.isBlank()) {
                paths.add(Paths.get(entry.trim()));
            }
        }
        return paths;
    }
}
