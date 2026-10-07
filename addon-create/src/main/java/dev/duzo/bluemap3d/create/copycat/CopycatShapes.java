package dev.duzo.bluemap3d.create.copycat;

import dev.duzo.bluemap3d.api.BlockAppearance;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The geometry of the copycats whose shape is not a few boxes: slopes, pipes and cogwheels.
 *
 * <p>Copycats+ builds these at render time by copying regions of the material's full-block
 * model onto a shape, and Create's pipes and cogs are drawn by block entity renderers. None
 * of that runs on a server, and the voxel shape the server does have is a staircase for a
 * slope and a bare box for a pipe. So the geometry is rebuilt here, from the same numbers,
 * as free {@link BlockAppearance.Face faces}.
 *
 * <p>Holds no Copycats+ types, only vanilla and joml ones: the caller in {@link CopycatsPlus}
 * reads the block's properties and passes plain values. Everything is in 0..16 model space,
 * about a block centre of 8.
 *
 * <h2>What is not drawn</h2>
 * Fluid pipes have rims and drains that depend on what is next to them - the client decides
 * per side whether a neighbour pipe calls for one - and a resolver has no neighbours, so they
 * are left off. Glass pipes are drawn as their open frame with the windows empty. Both are
 * approximations of an approximation, in the way the voxel shape was.
 */
final class CopycatShapes {

    private static final float[] CUBE_MIN = {0, 0, 0};
    private static final float[] CUBE_MAX = {16, 16, 16};

    private CopycatShapes() {
    }

    // ---------------------------------------------------------------------------------
    // Slopes
    // ---------------------------------------------------------------------------------

    /**
     * A wedge: a cube whose height falls linearly from the side it faces.
     *
     * <p>In the block's canonical orientation, facing south, the height is {@code startPx} at
     * the north edge and {@code endPx} at the south one, and every vertex of a unit cube is
     * squashed to it - which is exactly what Copycats+'s {@code QuadSlope} does to its copied
     * quads. The full-height edge is therefore on the side the block faces. A top-half slope
     * is that, flipped over.
     *
     * <p>The faces keep the texture mapping of the cube they were squashed from: the ramp
     * shows the material's top texture as if looked at from straight above, and the sides
     * show a cropped part of it.
     *
     * @param facing  the horizontal side the high edge is on
     * @param top     whether the slope hangs from the top of the block
     * @param startPx the height at the low edge, 0..16
     * @param endPx   the height at the high edge, 0..16
     */
    static BlockAppearance slope(Direction facing, boolean top, float startPx, float endPx,
                                 BlockState material) {
        Matrix4f transform = new Matrix4f();
        if (top) {
            transform.mul(about(new Matrix4f().scale(1f, -1f, 1f)));
        }
        transform.mul(about(new Matrix4f().rotateY(facingRadians(facing))));
        return new BlockAppearance(List.of(), transformed(ramp(startPx, endPx, material), transform), null);
    }

    /**
     * A triangular prism standing on its edge: full faces on {@code facing} and the side
     * counter-clockwise from it, one diagonal face between them.
     *
     * <p>Copycats+ builds it by laying a slope on its side and then turning it to face.
     */
    static BlockAppearance verticalSlope(Direction facing, BlockState material) {
        Matrix4f transform = new Matrix4f();
        transform.mul(about(new Matrix4f().rotateY(facingRadians(facing))));
        // The slope's up becomes the block's west: (x, y) -> (-y, x), a quarter turn about z.
        transform.mul(about(new Matrix4f().rotateZ((float) (Math.PI / 2))));
        return new BlockAppearance(List.of(), transformed(ramp(0f, 16f, material), transform), null);
    }

    /** The six faces of a cube squashed to a ramp, in the canonical orientation. */
    private static List<BlockAppearance.Face> ramp(float startPx, float endPx, BlockState material) {
        List<BlockAppearance.Face> out = new ArrayList<>(6);
        for (BlockAppearance.Face face : BlockAppearance.Face.box(CUBE_MIN, CUBE_MAX, material, null)) {
            float[] p = face.positions().clone();
            for (int i = 0; i < 4; i++) {
                float height = startPx + (endPx - startPx) * p[i * 3 + 2] / 16f;
                p[i * 3 + 1] = p[i * 3 + 1] * height / 16f;
            }
            // A face squashed flat - the north side of a slope that starts at nothing - has
            // no area and would only add a degenerate quad.
            if (area(p) > 0.01f) {
                // Only the ramp keeps the unsquashed cube's mapping, so it shows the top
                // texture as seen from above. The sides are cropped by the squashing, so they
                // read the texture where they actually are; keeping the cube's mapping for them
                // would squeeze the whole texture into the short edge instead.
                float[] uv = face.uvFace() == Direction.UP ? face.uvPositions() : p;
                out.add(new BlockAppearance.Face(p, uv, face.uvFace(), material));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------
    // Pipes
    // ---------------------------------------------------------------------------------

    /**
     * A fluid pipe: a core with an arm to every connected side.
     *
     * <p>The core is drawn whenever there are two or more connections, which includes four
     * and more. Copycats+ leaves it out there, but the arms alone would leave a hole at a
     * junction, and a junction is exactly where a pipe is most visible. The faces where an arm
     * meets the core are the same patch of space seen from both sides, so neither is drawn.
     * With no connection at all there is nothing to draw and this returns {@code null}.
     */
    @Nullable
    static BlockAppearance fluidPipe(BlockState state, BlockState material) {
        List<Direction> connected = new ArrayList<>();
        for (Direction d : Direction.values()) {
            BooleanProperty property = PipeBlock.PROPERTY_BY_DIRECTION.get(d);
            if (property != null && state.hasProperty(property) && state.getValue(property)) {
                connected.add(d);
            }
        }
        if (connected.isEmpty()) {
            return null;
        }

        List<BlockAppearance.Face> faces = new ArrayList<>();
        if (connected.size() >= 2) {
            for (BlockAppearance.Face face : BlockAppearance.Face.box(
                    new float[]{4, 4, 4}, new float[]{12, 12, 12}, material, null)) {
                if (!connected.contains(face.uvFace())) {
                    faces.add(face);
                }
            }
        }
        for (Direction d : connected) {
            List<BlockAppearance.Face> arm = new ArrayList<>();
            for (BlockAppearance.Face face : BlockAppearance.Face.box(
                    new float[]{4, 4, 0}, new float[]{12, 12, 4}, material, null)) {
                // The arm is modelled on the north side, so its inner end is its south face.
                // Filtered while it is still canonical, then the whole arm is turned to its
                // side, which carries each face's texture mapping and direction with it. The
                // inner end is only hidden where there is a core to hide it: a lone arm has
                // none, and would be left open.
                if (face.uvFace() != Direction.SOUTH || connected.size() < 2) {
                    arm.add(face);
                }
            }
            faces.addAll(transformed(arm, about(towards(d))));
        }
        return new BlockAppearance(List.of(), faces, null);
    }

    /**
     * A glass pipe: four corner posts and the frame between them, the windows open.
     *
     * <p>Each of the four quarter turns about the axis contributes a corner post the whole
     * length of the pipe, a one-pixel strip along the edge between two posts, and a collar at
     * each end. What is left between is the window, which is glass in game and empty here.
     */
    static BlockAppearance glassPipe(Direction.Axis axis, BlockState material) {
        List<BlockAppearance.Face> faces = new ArrayList<>();
        Matrix4f axisTurn = alongAxis(axis);
        for (int quarter = 0; quarter < 4; quarter++) {
            // The quarter turn and the lie along the axis are both placement turns, so the
            // whole frame goes through transformed(), which carries each face's texture
            // mapping and direction along with its corners.
            Matrix4f t = new Matrix4f(axisTurn).mul(about(new Matrix4f().rotateZ(quarter * (float) (Math.PI / 2))));
            List<BlockAppearance.Face> frame = new ArrayList<>();
            frame.addAll(BlockAppearance.Face.box(new float[]{4.02f, 4.02f, 0}, new float[]{6.02f, 6.02f, 16}, material, null));
            frame.addAll(BlockAppearance.Face.box(new float[]{6.02f, 4.02f, 3}, new float[]{9.98f, 5.02f, 13}, material, null));
            frame.addAll(BlockAppearance.Face.box(new float[]{6.02f, 4.02f, 0}, new float[]{9.98f, 6.02f, 3}, material, null));
            frame.addAll(BlockAppearance.Face.box(new float[]{6.02f, 4.02f, 13}, new float[]{9.98f, 6.02f, 16}, material, null));
            faces.addAll(transformed(frame, t));
        }
        return new BlockAppearance(List.of(), faces, null);
    }

    // ---------------------------------------------------------------------------------
    // Kinetics
    // ---------------------------------------------------------------------------------

    /** A bare shaft: 4x4 through the block along its axis. */
    static BlockAppearance shaft(Direction.Axis axis, BlockState material) {
        return new BlockAppearance(List.of(),
                transformed(BlockAppearance.Face.box(new float[]{6, 6, 0}, new float[]{10, 10, 16}, material, null),
                        alongAxis(axis)),
                null);
    }

    /**
     * A cogwheel with its teeth, and its shaft in a material of its own.
     *
     * <p>Modelled turned about z and then laid along {@code axis}. A small cog is a hub, a
     * thin plate and four bars crossing at 45 degrees, which read as eight teeth; a large cog
     * is a body with rim bars, two plates and sixteen radial teeth. Copycats+ draws the same
     * parts from a baked model. Where a bar crosses another it is inflated a hair in z, as
     * Copycats+ does, so the crossing faces do not share a plane and flicker.
     *
     * <p>The cog's parts are in the {@code cogwheel} material and the shaft in the
     * {@code shaft} one. If the cogwheel material is itself a cogwheel it is just a block to
     * take textures from, and the teeth are built as usual.
     *
     * <p>The large cog's numbers - rim bars, plates and teeth - are Copycats+'s own, read out
     * of {@code CopycatLargeCogWheelModelCore}'s bytecode. The one thing left out is its
     * per-tooth z jitter, which only exists to stop overlapping teeth z-fighting, and these do
     * not overlap.
     */
    static BlockAppearance cog(Direction.Axis axis, boolean large, BlockState cogMaterial,
                               BlockState shaftMaterial) {
        Matrix4f axisTurn = alongAxis(axis);
        // Every part is built along z and goes through transformed() to lie along the axis, so
        // that its texture mapping and face directions turn with it. The only turn Face.box is
        // given is the in-plane one that must keep the texture frame: a tooth turned about z
        // still reads the texture it would have unturned.
        List<BlockAppearance.Face> faces = new ArrayList<>(transformed(
                BlockAppearance.Face.box(new float[]{6, 6, 0}, new float[]{10, 10, 16}, shaftMaterial, null), axisTurn));

        if (large) {
            faces.addAll(transformed(BlockAppearance.Face.box(
                    new float[]{1, 1, 5.975f}, new float[]{15, 15, 10.025f}, cogMaterial, null), axisTurn));
            float[][] rims = {{-1, 1, 1, 15}, {15, 1, 17, 15}, {1, -1, 15, 1}, {1, 15, 15, 17}};
            for (float[] rim : rims) {
                faces.addAll(transformed(BlockAppearance.Face.box(new float[]{rim[0], rim[1], 5.975f},
                        new float[]{rim[2], rim[3], 10.025f}, cogMaterial, null), axisTurn));
            }
            faces.addAll(transformed(BlockAppearance.Face.box(
                    new float[]{-2, -2, 6.4f}, new float[]{18, 18, 9.6f}, cogMaterial, null), axisTurn));
            faces.addAll(transformed(BlockAppearance.Face.box(
                    new float[]{-2, -2, 6.625f}, new float[]{18, 18, 9.375f}, cogMaterial,
                    about(new Matrix4f().rotateZ((float) (Math.PI / 4)))), axisTurn));
            for (int tooth = 0; tooth < 16; tooth++) {
                // The tooth reaches 9 to 15 px out from the centre, which is y 17 to 23 - out
                // of the block, where its texture coordinates would have to be clamped and
                // would smear. So it is built inside the block, at y 1 to 7, and moved out by
                // a block before it is turned about the centre.
                Matrix4f out = about(new Matrix4f().rotateZ(tooth * (float) (Math.PI / 8)))
                        .translate(0f, 16f, 0f);
                faces.addAll(transformed(BlockAppearance.Face.box(
                        new float[]{6.5f, 1f, 6.6f}, new float[]{9.5f, 7f, 9.5f}, cogMaterial, out), axisTurn));
            }
        } else {
            faces.addAll(transformed(BlockAppearance.Face.box(
                    new float[]{4, 4, 6}, new float[]{12, 12, 10}, cogMaterial, null), axisTurn));
            faces.addAll(transformed(BlockAppearance.Face.box(
                    new float[]{2, 2, 6.55f}, new float[]{14, 14, 9.45f}, cogMaterial, null), axisTurn));
            for (int bar = 0; bar < 4; bar++) {
                float inflate = 0.02f * bar;
                Matrix4f turn = about(new Matrix4f().rotateZ(bar * (float) (Math.PI / 4)));
                faces.addAll(transformed(BlockAppearance.Face.box(new float[]{6.5f, -1f, 6.5f - inflate},
                        new float[]{9.5f, 17f, 9.5f + inflate}, cogMaterial, turn), axisTurn));
            }
        }
        return new BlockAppearance(List.of(), faces, null);
    }

    // ---------------------------------------------------------------------------------
    // Transforms
    // ---------------------------------------------------------------------------------

    /** {@code m} applied about the block's centre rather than its corner. */
    private static Matrix4f about(Matrix4f m) {
        return new Matrix4f().translation(8f, 8f, 8f).mul(m).translate(-8f, -8f, -8f);
    }

    /**
     * Turns a model built along z so that it lies along {@code axis}.
     *
     * <p>Every model that goes through this - cogs, shafts, glass pipes - is symmetric front
     * to back, so which of the two quarter turns reaches a given axis does not matter.
     */
    private static Matrix4f alongAxis(Direction.Axis axis) {
        return switch (axis) {
            case X -> about(new Matrix4f().rotateY((float) (Math.PI / 2)));
            case Y -> about(new Matrix4f().rotateX((float) (Math.PI / 2)));
            case Z -> new Matrix4f();
        };
    }

    /** Turns a model built on the north side to the given side. */
    private static Matrix4f towards(Direction d) {
        return switch (d) {
            case NORTH -> new Matrix4f();
            case SOUTH -> new Matrix4f().rotateY((float) Math.PI);
            case EAST -> new Matrix4f().rotateY((float) (-Math.PI / 2));
            case WEST -> new Matrix4f().rotateY((float) (Math.PI / 2));
            case UP -> new Matrix4f().rotateX((float) (Math.PI / 2));
            case DOWN -> new Matrix4f().rotateX((float) (-Math.PI / 2));
        };
    }

    /**
     * The angle that turns a south-facing model to face {@code facing}, in joml's sense.
     *
     * <p>Copycats+ turns by the block's {@code toYRot} the other way round from joml, so a
     * west-facing block is a quarter turn that carries south to west. That is a joml angle of
     * minus the yaw.
     */
    private static float facingRadians(Direction facing) {
        return (float) Math.toRadians(-facing.toYRot());
    }

    /**
     * Carries faces through a transform, positions and texture mapping both, so the texture
     * goes where the face goes.
     *
     * <p>A transform with a negative determinant - a flip - reverses the winding of every
     * face, and it is put back, or the faces would turn inside out.
     */
    private static List<BlockAppearance.Face> transformed(List<BlockAppearance.Face> faces, Matrix4f t) {
        boolean mirrored = t.determinant() < 0f;
        List<BlockAppearance.Face> out = new ArrayList<>(faces.size());
        Vector3f scratch = new Vector3f();
        for (BlockAppearance.Face f : faces) {
            float[] pos = apply(t, f.positions(), scratch);
            float[] uv = apply(t, f.uvPositions(), scratch);
            if (mirrored) {
                pos = reversed(pos);
                uv = reversed(uv);
            }
            Vector3f normal = t.transformDirection(
                    new Vector3f(f.uvFace().getStepX(), f.uvFace().getStepY(), f.uvFace().getStepZ()));
            out.add(new BlockAppearance.Face(pos, uv,
                    Direction.getNearest(normal.x, normal.y, normal.z), f.material()));
        }
        return out;
    }

    private static float[] apply(Matrix4f t, float[] p, Vector3f scratch) {
        float[] out = new float[12];
        for (int i = 0; i < 4; i++) {
            t.transformPosition(scratch.set(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]));
            out[i * 3] = scratch.x;
            out[i * 3 + 1] = scratch.y;
            out[i * 3 + 2] = scratch.z;
        }
        return out;
    }

    private static float[] reversed(float[] p) {
        float[] out = new float[12];
        int[] order = {0, 3, 2, 1};
        for (int i = 0; i < 4; i++) {
            System.arraycopy(p, order[i] * 3, out, i * 3, 3);
        }
        return out;
    }

    /** The area of a quad, as two triangles; a triangle with a repeated corner works too. */
    private static float area(float[] p) {
        return triangle(p, 0, 1, 2) + triangle(p, 0, 2, 3);
    }

    private static float triangle(float[] p, int a, int b, int c) {
        float ux = p[b * 3] - p[a * 3], uy = p[b * 3 + 1] - p[a * 3 + 1], uz = p[b * 3 + 2] - p[a * 3 + 2];
        float vx = p[c * 3] - p[a * 3], vy = p[c * 3 + 1] - p[a * 3 + 1], vz = p[c * 3 + 2] - p[a * 3 + 2];
        float cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
        return 0.5f * (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
    }
}
