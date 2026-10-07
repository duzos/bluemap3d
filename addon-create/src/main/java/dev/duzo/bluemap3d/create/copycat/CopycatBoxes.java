package dev.duzo.bluemap3d.create.copycat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Axis-aligned box arithmetic for copycat shapes, in 0..16 model space.
 *
 * <p>A box is a {@code float[6]} of {@code minX, minY, minZ, maxX, maxY, maxZ}. Plain
 * arrays rather than {@link AABB}: everything downstream is in sixteenths, the resolver
 * builds a few of these per block per bake, and the model-space numbers are the ones a
 * {@code BlockAppearance.Piece} wants, so there is no conversion to get wrong at the end.
 *
 * <p>Holds no Copycats+ types, so {@link CopycatAppearances} - which is loaded on every
 * server with Create - can use it freely.
 */
final class CopycatBoxes {

    /** Smaller than this on any axis and a box is a line or a point, not something to draw. */
    private static final float EPS = 1e-4f;

    private CopycatBoxes() {
    }

    /**
     * The boxes of a state's shape, or {@code null} if it has none this can use.
     *
     * <p>The outline first and collision second, the same order and for the same reason as
     * {@code ShapeSource}: a copycat's outline is what the client draws round it, but some
     * have only a collision box. Evaluated against an {@link EmptyBlockGetter}, which makes
     * a shape that reaches into the level throw instead of lie; that, and anything over
     * {@code maxBoxes}, returns {@code null} so the block falls through to today's
     * behaviour rather than drawing a guess.
     */
    static List<float[]> shapeOf(BlockState state, int maxBoxes) {
        try {
            VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (shape.isEmpty()) {
                shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            }
            if (shape.isEmpty()) {
                return null;
            }
            List<AABB> aabbs = shape.toAabbs();
            if (aabbs.size() > maxBoxes) {
                return null;
            }
            List<float[]> out = new ArrayList<>(aabbs.size());
            for (AABB box : aabbs) {
                float[] b = {
                        (float) box.minX * 16f, (float) box.minY * 16f, (float) box.minZ * 16f,
                        (float) box.maxX * 16f, (float) box.maxY * 16f, (float) box.maxZ * 16f};
                // A zero-thickness box is an interaction shape, not a solid.
                if (solid(b)) {
                    out.add(b);
                }
            }
            return out.isEmpty() ? null : out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether a box has volume. */
    static boolean solid(float[] b) {
        return b[3] - b[0] > EPS && b[4] - b[1] > EPS && b[5] - b[2] > EPS;
    }

    /** The overlap of two boxes, or {@code null} if they do not share a volume. */
    static float[] intersect(float[] a, float[] b) {
        float[] out = new float[6];
        for (int i = 0; i < 3; i++) {
            out[i] = Math.max(a[i], b[i]);
            out[i + 3] = Math.min(a[i + 3], b[i + 3]);
        }
        return solid(out) ? out : null;
    }

    /**
     * Appends {@code a} minus {@code b} to {@code out}, as at most six boxes.
     *
     * <p>Peels {@code a} one axis at a time: whatever of {@code a} lies below {@code b} on
     * x is split off, then above on x, then the same on y within the remaining x slab, then
     * z. What is left is the overlap, which is dropped. The pieces tile {@code a} exactly
     * with none overlapping, which is the point - two coplanar duplicates of the same
     * region are exactly what z-fights.
     */
    static void subtract(float[] a, float[] b, List<float[]> out) {
        float[] overlap = intersect(a, b);
        if (overlap == null) {
            out.add(a);
            return;
        }
        float[] rest = a.clone();
        for (int axis = 0; axis < 3; axis++) {
            if (rest[axis] < overlap[axis] - EPS) {
                float[] below = rest.clone();
                below[axis + 3] = overlap[axis];
                out.add(below);
                rest[axis] = overlap[axis];
            }
            if (rest[axis + 3] > overlap[axis + 3] + EPS) {
                float[] above = rest.clone();
                above[axis] = overlap[axis + 3];
                out.add(above);
                rest[axis + 3] = overlap[axis + 3];
            }
        }
    }
}
