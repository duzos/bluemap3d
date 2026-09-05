package dev.duzo.bluemap3d.create;

import dev.duzo.bluemap3d.api.BlueMap3D;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Registers this addon's providers and its own config. See {@link ContraptionProvider},
 * {@link CurvedTrackProvider} and {@link BearingProvider}.
 */
@Mod(CreateAddon.MOD_ID)
public final class CreateAddon {

    public static final String MOD_ID = "bluemap3d_create";

    // BearingProvider needs to know which chunks are loaded without scanning the level for
    // them - see ChunkTracker and BearingProvider's own class header - so both it and the
    // provider that reads it are kept as fields rather than built inline below.
    private final ChunkTracker chunks = new ChunkTracker();
    private final BearingProvider bearings = new BearingProvider(chunks);
    private final ContraptionProvider contraptions = new ContraptionProvider();

    public CreateAddon(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, CreateConfig.SPEC);
        NeoForge.EVENT_BUS.register(chunks);
        NeoForge.EVENT_BUS.register(this);
        BlueMap3D.register(contraptions);
        BlueMap3D.register(new CurvedTrackProvider());
        BlueMap3D.register(bearings);
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        chunks.clear();
        bearings.clear();
        contraptions.clear();
    }
}
