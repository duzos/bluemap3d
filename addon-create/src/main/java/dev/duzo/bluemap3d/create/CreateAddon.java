package dev.duzo.bluemap3d.create;

import dev.duzo.bluemap3d.api.BlueMap3D;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * Registers this addon's providers and its own config. See {@link ContraptionProvider} and
 * {@link CurvedTrackProvider}.
 */
@Mod(CreateAddon.MOD_ID)
public final class CreateAddon {

    public static final String MOD_ID = "bluemap3d_create";

    public CreateAddon(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, CreateConfig.SPEC);
        BlueMap3D.register(new ContraptionProvider());
        BlueMap3D.register(new CurvedTrackProvider());
    }
}
