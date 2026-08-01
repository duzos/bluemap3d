package dev.duzo.bluemap3d.devharness;

import dev.duzo.bluemap3d.api.BlueMap3D;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local test harness. Not published, and not part of the bundle jar.
 *
 * <p>Registers {@link DemoFleet}, which is a provider like any other - it goes through the
 * exact same seam an addon does, so what you see in the browser is the real pipeline and
 * not a special case.
 */
@Mod(DevHarness.MOD_ID)
public final class DevHarness {

    public static final String MOD_ID = "bluemap3d_devharness";

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/DevHarness");

    private final DemoFleet fleet = new DemoFleet();

    public DevHarness() {
        NeoForge.EVENT_BUS.register(this);
        BlueMap3D.register(fleet);
        LOGGER.warn("BlueMap3D dev harness is active: 4 fake objects will appear on the map. "
                + "This module is for local testing and should never be on a real server.");
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        fleet.tick();
    }
}
