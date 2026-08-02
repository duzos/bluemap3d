package dev.duzo.bluemap3d.bake;

import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Models every block as a solid cube in its map colour.
 *
 * <p>The always-available source. It needs no assets whatsoever, so it works on a bare
 * dedicated server with no client jar and no resource packs, and it is what core falls
 * back to for any block {@link ResourcePackSource} cannot resolve. The result is real
 * 3D geometry with the correct silhouette and plausible colours - a ship reads as a
 * ship - it just is not textured.
 *
 * <p>Map colours are the same palette BlueMap uses for its own flat map view, so
 * objects meshed this way sit visually alongside the terrain rather than clashing
 * with it.
 */
public final class MapColorSource implements BlockModelSource {

    /** The single sprite this source uses: flat white, tinted per quad. */
    static final String WHITE = "bluemap3d:white";

    private final Map<BlockState, List<ModelQuad>> cache = new HashMap<>();

    @Override
    public List<ModelQuad> quadsFor(BlockState state) {
        return cache.computeIfAbsent(state, MapColorSource::build);
    }

    @Override
    public BufferedImage texture(String texture) {
        // null asks the atlas for its white placeholder, which is exactly what the
        // per-quad tint wants to multiply.
        return null;
    }

    @Override
    public boolean occludes(BlockState state) {
        return isFullOpaqueCube(state);
    }

    @Override
    public boolean isFaithful() {
        return false;
    }

    private static List<ModelQuad> build(BlockState state) {
        int tint = mapColorOf(state);
        if (tint < 0) {
            return List.of();
        }

        // Shrink non-full blocks slightly so a fence or a torch does not read as a
        // solid cube. Without real model geometry this is the only shape cue there is.
        boolean full = isFullOpaqueCube(state);
        float lo = full ? 0f : 4f;
        float hi = full ? 16f : 12f;

        List<ModelQuad> quads = new ArrayList<>(6);
        for (Direction face : Direction.values()) {
            quads.add(new ModelQuad(
                    face,
                    face,
                    cubeFace(face, lo, hi),
                    new float[]{0, 0, 16, 0, 16, 16, 0, 16},
                    WHITE,
                    tint));
        }
        return quads;
    }

    /**
     * The block's map colour as 0xRRGGBB, or -1 if it has none (air, and blocks that
     * are drawn but not mapped).
     */
    static int mapColorOf(BlockState state) {
        try {
            var mapColor = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (mapColor == null || mapColor.col == 0) {
                return -1;
            }
            return mapColor.col & 0xFFFFFF;
        } catch (Exception e) {
            // Some blocks reach into the level for their map colour. EmptyBlockGetter
            // makes those throw rather than lie, and skipping them is correct.
            return -1;
        }
    }

    /**
     * Whether a state is a full opaque cube, and so hides its neighbours' faces.
     *
     * <p>Deliberately strict: a false positive punches a hole through a hull, a false
     * negative only costs triangles.
     */
    static boolean isFullOpaqueCube(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        try {
            return state.canOcclude()
                    && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The four corners of an axis-aligned cube face, wound counter-clockwise seen from
     * outside, in the corner order {@code (u0,v0) (u1,v0) (u1,v1) (u0,v1)} so uvs line
     * up with {@link ModelQuad#uvs()}.
     */
    static float[] cubeFace(Direction face, float lo, float hi) {
        return switch (face) {
            case DOWN -> new float[]{lo, lo, hi, hi, lo, hi, hi, lo, lo, lo, lo, lo};
            case UP -> new float[]{lo, hi, lo, hi, hi, lo, hi, hi, hi, lo, hi, hi};
            case NORTH -> new float[]{hi, hi, lo, lo, hi, lo, lo, lo, lo, hi, lo, lo};
            case SOUTH -> new float[]{lo, hi, hi, hi, hi, hi, hi, lo, hi, lo, lo, hi};
            case WEST -> new float[]{lo, hi, lo, lo, hi, hi, lo, lo, hi, lo, lo, lo};
            case EAST -> new float[]{hi, hi, hi, hi, hi, lo, hi, lo, lo, hi, lo, hi};
        };
    }
}
