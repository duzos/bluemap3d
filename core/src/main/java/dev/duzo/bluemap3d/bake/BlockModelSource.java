package dev.duzo.bluemap3d.bake;

import net.minecraft.world.level.block.state.BlockState;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Turns a block state into quads, and supplies the textures those quads name.
 *
 * <p>This exists because there is no good way to mesh a block on a dedicated server.
 * The client's baked-model system is not present, so core has to reconstruct geometry
 * from somewhere, and how much it can reconstruct depends on what assets the server
 * actually has:
 *
 * <ul>
 *   <li>{@link MapColorSource} needs nothing at all. Every block becomes a solid cube
 *       in its map colour. Always available, so it is the fallback.</li>
 *   <li>{@link ResourcePackSource} reads real block models and textures out of jars and
 *       resource packs on disk, which gives correct shapes and textures - but only for
 *       assets the server can see.</li>
 * </ul>
 *
 * <p>Implementations are asked for the same state repeatedly and should cache.
 */
public interface BlockModelSource {

    /**
     * The quads for a block state, in 0..16 model space.
     *
     * @return the quads; empty if this source cannot model the state, which lets the
     *         caller fall back to another source
     */
    List<ModelQuad> quadsFor(BlockState state);

    /**
     * The quads for a named model, in 0..16 model space.
     *
     * <p>Used for {@link dev.duzo.bluemap3d.api.ModelAttachment}s, which name a model
     * directly instead of going through a block state. Sources that cannot resolve models by
     * name return empty, which is why this has a default.
     *
     * @param model    the model id, e.g. {@code computercraft:block/turtle_speaker_left}
     * @param textures overrides for the model's {@code #ref} variables, highest priority
     * @return the quads; empty if this source cannot resolve the model
     */
    default List<ModelQuad> quadsForModel(net.minecraft.resources.ResourceLocation model,
                                          java.util.Map<String, String> textures) {
        return List.of();
    }

    /**
     * The image for a sprite named by {@link ModelQuad#texture()}.
     *
     * @param texture the sprite id
     * @return the image, or {@code null} if unavailable
     */
    BufferedImage texture(String texture);

    /**
     * Whether this state completely hides the faces of its neighbours.
     *
     * <p>Only full, opaque cubes do. Getting this wrong in the permissive direction
     * punches holes in a hull; getting it wrong in the strict direction just costs
     * triangles, so implementations should err strict.
     */
    boolean occludes(BlockState state);

    /**
     * Whether this source reproduces a block's real shape and texture, or is standing in
     * for one that could not be found.
     *
     * <p>Only {@link MapColorSource} says no. The mesher uses it to tell an admin which
     * blocks it could not resolve, which is the difference between "the mod is broken"
     * and "that block has no model on a server and here is its name so you can supply
     * one".
     */
    default boolean isFaithful() {
        return true;
    }

    /**
     * What this source drew instead of the real model, for the unresolved report.
     *
     * <p>Only asked of sources that say they are not {@link #isFaithful() faithful}.
     * Naming it matters because the fallbacks are not equally bad: "voxel shape" is a
     * block that reads correctly at map zoom, "map colour" is a lump.
     */
    default String approximation() {
        return "map colour";
    }
}
