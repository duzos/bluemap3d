package dev.duzo.bluemap3d.turtles;

import dev.duzo.bluemap3d.api.BlueMap3D;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Registers the turtle provider. That is the whole addon.
 *
 * <p>No rendering code and no JavaScript, by design: if an addon ever needed either, the
 * abstraction in core would have leaked and core is what should change.
 */
@Mod(TurtleAddon.MOD_ID)
public final class TurtleAddon {

    public static final String MOD_ID = "bluemap3d_computercraft";

    private final ChunkTracker chunks = new ChunkTracker();

    public TurtleAddon() {
        NeoForge.EVENT_BUS.register(chunks);
        NeoForge.EVENT_BUS.register(this);
        BlueMap3D.register(new TurtleProvider(chunks));
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        chunks.clear();
    }
}
