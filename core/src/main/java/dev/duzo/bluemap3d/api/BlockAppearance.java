package dev.duzo.bluemap3d.api;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
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
 * <p>Boxes cover what a copycat slab or beam is. A slope, a cogwheel tooth or a pipe bend is
 * not a box, so an appearance can also carry free {@link Face faces}: arbitrary quads and
 * triangles dressed the same way. And a block that turns - a copycat cogwheel on a Sable
 * ship - can say so with {@link #spin()}, which the mesher turns into an animated node.
 *
 * <p>An appearance is geometry in the same sense a block state is, and a provider that
 * caches meshes has to treat it that way: see {@link BlockVolume#appearanceAt}.
 *
 * <p>Equality is by value, so the mesher can cache the quads it builds for an appearance.
 * The material is a {@link BlockState}, which is interned per registry, so comparing it by
 * identity is correct for as long as the process lives - and nothing here is ever written
 * to disk or hashed into a version that outlives it.
 *
 * @param pieces the boxes to draw, in 0..16 model space
 * @param faces  free faces to draw, in 0..16 model space; empty for a box-only appearance
 * @param spin   how the whole appearance turns, or {@code null} if it is still. Only the axis
 *               and the rate are read: the mesher turns it about the middle of its block, and
 *               the {@link ModelAttachment.Rate#pivot() pivot} is ignored. A resolver with
 *               nothing at all to draw returns {@code null} rather than an empty appearance
 */
public record BlockAppearance(List<Piece> pieces, List<Face> faces,
                              @Nullable ModelAttachment.Rate spin) {

    public BlockAppearance {
        pieces = List.copyOf(pieces);
        faces = List.copyOf(faces);
    }

    /** An appearance of boxes alone, standing still. */
    public BlockAppearance(List<Piece> pieces) {
        this(pieces, List.of(), null);
    }

    /** This appearance, turning. */
    public BlockAppearance withSpin(@Nullable ModelAttachment.Rate spin) {
        return new BlockAppearance(pieces, faces, spin);
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

    /**
     * One free face: a quad, or a triangle with its last corner repeated.
     *
     * <p>Positions and texture mapping are separate on purpose. A sloped face is dressed in
     * the material's texture as it would lie on the block's <em>unsloped</em> face - the
     * ramp of a slope shows the material's top texture projected straight down - so the
     * texture is read off {@code uvPositions}, the corners of the face before it was bent,
     * while the geometry goes where {@code positions} says.
     *
     * <p>The two arrays are in the same corner order, which is the winding of
     * {@link #box}'s faces: counter-clockwise seen from outside. A builder that mirrors a
     * face has to reverse that winding in both, or the face turns inside out.
     *
     * @param positions   four corners as xyz, in 0..16 model space
     * @param uvPositions four corners as xyz, in the same order, where the texture is read
     *                    from; on the plane of {@code uvFace}, within 0..16. {@code null}
     *                    means the same as {@code positions}
     * @param uvFace      the face of the material whose texture dresses this one, and the
     *                    face whose directional shade applies. Explicit, because a 45-degree
     *                    slope is exactly between two faces and any guess would be arbitrary
     * @param material    the block whose faces dress this one; never air
     */
    public record Face(float[] positions, @Nullable float[] uvPositions, Direction uvFace,
                       BlockState material) {

        public Face {
            Objects.requireNonNull(uvFace, "uvFace");
            Objects.requireNonNull(material, "material");
            positions = positions.clone();
            uvPositions = uvPositions == null ? positions : uvPositions.clone();
        }

        // Arrays compare by identity in a record's default equals. The mesher caches the
        // quads it builds for an appearance, keyed on the appearance, so equal content has
        // to be equal.
        @Override
        public boolean equals(Object o) {
            return o instanceof Face f
                    && uvFace == f.uvFace
                    && material.equals(f.material)
                    && Arrays.equals(positions, f.positions)
                    && Arrays.equals(uvPositions, f.uvPositions);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(positions), Arrays.hashCode(uvPositions), uvFace, material);
        }

        /**
         * The six faces of a box, optionally carried through a transform.
         *
         * <p>{@code uvPositions} are the box's own corners, untransformed and clamped to
         * 0..16: rotating a tooth must not shear its texture, and a bar that sticks out past
         * the block would otherwise read texture coordinates beyond the sprite. A box built
         * inside the block is not affected by the clamp. The {@code uvFace} of each face is the
         * box's own, untransformed direction.
         *
         * @param min      lower corner, in 0..16 model space
         * @param max      upper corner
         * @param material the block that dresses every face
         * @param t        an in-plane turn that must keep the texture frame - a tooth turned
         *                 about its axle, a plate set at 45 degrees - applied to the positions
         *                 only, in 0..16 units, or {@code null}. The texture mapping and
         *                 {@code uvFace} stay those of the unturned box, which is the point.
         *                 It is not for placing a whole part in the block: a turn that
         *                 changes which way the faces point must carry the texture mapping and
         *                 direction too, so a caller does that on the finished faces. A
         *                 transform with a negative determinant has its winding reversed
         */
        public static List<Face> box(float[] min, float[] max, BlockState material,
                                     @Nullable Matrix4f t) {
            boolean mirrored = t != null && t.determinant() < 0f;
            Vector3f scratch = new Vector3f();
            List<Face> out = new ArrayList<>(6);
            for (Direction d : Direction.values()) {
                float[] corners = corners(min, max, d);
                float[] uv = new float[12];
                for (int i = 0; i < 12; i++) {
                    uv[i] = Math.max(0f, Math.min(16f, corners[i]));
                }
                float[] pos = corners.clone();
                if (t != null) {
                    for (int i = 0; i < 4; i++) {
                        t.transformPosition(scratch.set(corners[i * 3], corners[i * 3 + 1], corners[i * 3 + 2]));
                        pos[i * 3] = scratch.x;
                        pos[i * 3 + 1] = scratch.y;
                        pos[i * 3 + 2] = scratch.z;
                    }
                }
                if (mirrored) {
                    pos = reversed(pos);
                    uv = reversed(uv);
                }
                out.add(new Face(pos, uv, d, material));
            }
            return out;
        }

        /**
         * The same face winding the resource-pack mesher uses for a box, which is what the
         * UV mapping and the browser's culling both assume.
         */
        private static float[] corners(float[] f, float[] t, Direction face) {
            float x0 = f[0], y0 = f[1], z0 = f[2];
            float x1 = t[0], y1 = t[1], z1 = t[2];
            return switch (face) {
                case UP -> new float[]{x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1};
                case DOWN -> new float[]{x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0};
                case NORTH -> new float[]{x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0};
                case SOUTH -> new float[]{x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1};
                case WEST -> new float[]{x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0};
                case EAST -> new float[]{x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1};
            };
        }

        /** Corners 0,3,2,1: the reverse winding, starting from the same corner. */
        private static float[] reversed(float[] p) {
            float[] out = new float[12];
            int[] order = {0, 3, 2, 1};
            for (int i = 0; i < 4; i++) {
                System.arraycopy(p, order[i] * 3, out, i * 3, 3);
            }
            return out;
        }
    }
}
