package dev.duzo.bluemap3d.create;

import dev.duzo.bluemap3d.api.BlueMap3D;
import net.neoforged.fml.common.Mod;

/** Registers the train provider. See {@link TrainProvider} for implementation status. */
@Mod(CreateAddon.MOD_ID)
public final class CreateAddon {

    public static final String MOD_ID = "bluemap3d_create";

    public CreateAddon() {
        BlueMap3D.register(new TrainProvider());
    }
}
