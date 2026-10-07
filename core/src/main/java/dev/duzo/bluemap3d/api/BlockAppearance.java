package dev.duzo.bluemap3d.api;

import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Objects;

/**
 * What one block position looks like when its block state does not say.
 *
 * <p>A block state is the whole of what a model lookup sees, and for most blocks it is
 * also the whole truth. For a few it is not: a Create copycat is the same
 * {@code create:copycat_panel} whether it is skinned in oak planks or in diamond, because
 * the skin lives in the block entity and the client's baked model reads it at render time.
 * A dedicated server cannot run that code, so the model JSON for these blocks is an empty
 * model and the mesher is left drawing nothing at all.
 *
 * <p>An appearance is the server's replacement: a handful of axis-aligned boxes, each
 * dressed in the faces of some other block. The mesher draws every box with the textures
 * of its {@link Piece#material() material} instead of asking the block's own model for
 * anything. Producing one is the job of a {@link BlockAppearanceResolver}, which knows how
 * to read the block entity data the state leaves out.
 *
 * <p>An appearance is geometry in the same sense a block state is, and a provider that
 * caches meshes has to treat it that way: see {@link BlockVolume#appearanceAt}.
 *
 * <p>Equality is by value, so the mesher can cache the quads it builds for an appearance.
 * The material is a {@link BlockState}, which is interned per registry, so comparing it by
 * identity is correct for as long as the process lives - and nothing here is ever written
 * to disk or hashed into a version that outlives it.
 *
 * @param pieces the boxes to draw, in 0..16 model space; never empty in practice, because
 *               a resolver with nothing to draw returns {@code null} rather than an
 *               empty appearance
 */
public record BlockAppearance(List<Piece> pieces) {

    public BlockAppearance {
        pieces = List.copyOf(pieces);
    }

    /**
     * One box of an appearance.
     *
     * @param minX     lower x of the box, in 0..16 model space
     * @param minY     lower y
     * @param minZ     lower z
     * @param maxX     upper x
     * @param maxY     upper y
     * @param maxZ     upper z
     * @param material the block whose faces dress this box; never {@code null}, and never
     *                 air - a resolver that has nothing better substitutes a placeholder
     *                 rather than leaving the box bare
     */
    public record Piece(float minX, float minY, float minZ,
                        float maxX, float maxY, float maxZ,
                        BlockState material) {

        public Piece {
            Objects.requireNonNull(material, "material");
        }
    }
}
