package dev.duzo.bluemap3d.sable;

import dev.duzo.bluemap3d.api.BlueMap3D;
import net.neoforged.fml.common.Mod;

/** Registers the ship provider. See {@link ShipProvider} for implementation status. */
@Mod(SableAddon.MOD_ID)
public final class SableAddon {

    public static final String MOD_ID = "bluemap3d_sable";

    public SableAddon() {
        BlueMap3D.register(new ShipProvider());
    }
}
