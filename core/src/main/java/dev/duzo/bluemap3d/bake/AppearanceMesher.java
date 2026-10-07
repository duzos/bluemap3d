package dev.duzo.bluemap3d.bake;

import dev.duzo.bluemap3d.api.BlockAppearance;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link BlockAppearance} - boxes dressed in another block's faces - into quads.
 *
 * <p>This is the half of copycat support that lives in core, and it knows nothing about
 * copycats. A {@link dev.duzo.bluemap3d.api.BlockAppearanceResolver} says "this block is
 * these boxes, skinned in oak planks"; this class is what makes that drawable on a
 * dedicated server, where the client code that would normally dress the boxes does not
 * exist.
 *
 * <h2>How a box face is dressed</h2>
 * The material's own model is asked for its quads, and the ones that matter are those that
 * <em>are</em> a face of the block: lying on the boundary plane for that face and spanning
 * all of it. A full-face quad is the only kind that can be stretched or shrunk to a
 * different rectangle and still look like the material - a stair's partial riser or a
 * slab's half-height side would be smeared across the whole face - so anything shorter is
 * ignored rather than distorted. The box face then takes the texture, tint and orientation
 * of that quad, with its UVs read off the quad's own position-to-UV mapping at the box
 * face's corners. Reading the mapping rather than copying the UV window is what keeps a
 * rotated texture, such as a log on its side, turned the right way, and it makes a slab's
 * half-height face show half the texture rather than all of it squeezed.
 *
 * <p>Where the material has no such quad - no resource pack, or a block whose model is
 * geometry-less - the face falls back in the same order the rest of core does: the
 * material's particle sprite, then a flat tint from its map colour. The last step cannot
 * fail, so a box is always drawn.
 *
 * <h2>Threading</h2>
 * The cache is a plain {@link HashMap}. That is correct only because a mesher bakes on one
 * thread, as {@link VolumeMesher} documents, and it is the same assumption every
 * {@link BlockModelSource} cache in this package makes.
 */
public final class AppearanceMesher {

    /** Tolerance for "this corner is on the plane" and "this quad spans the face". */
    private static final float EPS = 1e-4f;

    /**
     * A quad together with the source that owns its texture.
     *
     * <p>An appearance can mix sources - one face textured from a resource pack, the next a
     * map-colour tint - and the atlas has to ask the right one for each image.
     */
    public record SourcedQuad(ModelQuad quad, BlockModelSource source) {
    }

    private final ResourcePackSource packs;
    private final MapColorSource mapColors;
    private final Map<BlockAppearance, List<SourcedQuad>> cache = new HashMap<>();

    /**
     * @param sources the mesher's own source list. The resource pack source is found in it
     *                by type and may be absent - {@code useResourcePacks} off - in which
     *                case every face takes the map-colour route.
     */
    AppearanceMesher(List<BlockModelSource> sources) {
        ResourcePackSource foundPacks = null;
        MapColorSource foundColors = null;
        for (BlockModelSource source : sources) {
            if (foundPacks == null && source instanceof ResourcePackSource rp) {
                foundPacks = rp;
            } else if (foundColors == null && source instanceof MapColorSource mc) {
                foundColors = mc;
            }
        }
        this.packs = foundPacks;
        // Private when the list has none, so the final fallback never depends on how the
        // caller assembled its sources.
        this.mapColors = foundColors != null ? foundColors : new MapColorSource();
    }

    /** The quads for an appearance, in 0..16 model space. Cached. */
    List<SourcedQuad> quadsFor(BlockAppearance appearance) {
        return cache.computeIfAbsent(appearance, this::build);
    }

    private List<SourcedQuad> build(BlockAppearance appearance) {
        List<SourcedQuad> out = new ArrayList<>();
        for (BlockAppearance.Piece piece : appearance.pieces()) {
            float[] from = {piece.minX(), piece.minY(), piece.minZ()};
            float[] to = {piece.maxX(), piece.maxY(), piece.maxZ()};
            for (Direction face : Direction.values()) {
                if (area(from, to, face) < EPS) {
                    // A face with no area is a box with a zero side, which a resolver
                    // should not send but whose cost here is only a degenerate quad.
                    continue;
                }
                dress(out, from, to, face, piece.material());
            }
        }
        for (BlockAppearance.Face face : appearance.faces()) {
            dressFace(out, face);
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    /**
     * Appends the quad for one free face.
     *
     * <p>Dressed like a box face, except that the texture is read from the face's
     * {@code uvPositions} rather than from where the geometry actually is, so a bent face
     * wears the texture of the flat face it was bent from. The face to read is the explicit
     * {@code uvFace}; nothing here guesses it from the normal, because a 45-degree slope has
     * none to guess.
     *
     * <p>Culled against the neighbour only when all four corners lie on the block boundary on
     * the {@code uvFace} side. A sloped face does not, so it is never culled by accident.
     */
    private void dressFace(List<SourcedQuad> out, BlockAppearance.Face f) {
        Direction face = f.uvFace();
        float[] positions = f.positions();
        Direction cull = onBoundary(positions, face) ? face : null;

        if (packs != null) {
            int before = out.size();
            for (ModelQuad faceQuad : packs.quadsFor(f.material())) {
                if (faceQuad.shadeFace() == face && isFullFace(faceQuad.positions(), face)) {
                    out.add(new SourcedQuad(new ModelQuad(
                            cull, face, positions,
                            uvAt(faceQuad.positions(), faceQuad.uvs(), f.uvPositions()),
                            faceQuad.texture(), faceQuad.tint()), packs));
                }
            }
            if (out.size() > before) {
                return;
            }

            String particle = packs.particleTexture(f.material());
            if (particle != null && packs.texture(particle) != null) {
                out.add(new SourcedQuad(new ModelQuad(
                        cull, face, positions, planarUv(face, f.uvPositions()),
                        particle, 0xFFFFFF), packs));
                return;
            }
        }

        int colour = MapColorSource.mapColorOf(f.material());
        out.add(new SourcedQuad(new ModelQuad(
                cull, face, positions, planarUv(face, f.uvPositions()),
                MapColorSource.WHITE, colour < 0 ? 0xFFFFFF : colour), mapColors));
    }

    /**
     * Texture coordinates for corners lying on a block face, the way {@link ShapeSource}
     * gives them to a box face: the whole 16x16 sprite across the full face, with v measured
     * from the top.
     *
     * <p>Built by mapping the corners through a synthetic full face rather than a formula of
     * its own, so it shares the orientation handling of {@link #uvAt} and cannot disagree
     * with it about which way is up.
     */
    private static float[] planarUv(Direction face, float[] corners) {
        float[] full = ResourcePackSource.faceCorners(new float[]{0, 0, 0}, new float[]{16, 16, 16}, face);
        float[] uv = ResourcePackSource.uvCorners(
                ResourcePackSource.autoUv(new float[]{0, 0, 0}, new float[]{16, 16, 16}, face), 0);
        return uvAt(full, uv, corners);
    }

    /** Whether all four corners lie on the block's boundary plane for {@code face}. */
    private static boolean onBoundary(float[] p, Direction face) {
        float plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 16f : 0f;
        int axis = face.getAxis().ordinal();
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i * 3 + axis] - plane) > EPS) {
                return false;
            }
        }
        return true;
    }

    /** Appends the quads for one face of one box. */
    private void dress(List<SourcedQuad> out, float[] from, float[] to, Direction face,
                       BlockState material) {
        Direction cull = cullFaceOf(from, to, face);
        float[] corners = ResourcePackSource.faceCorners(from, to, face);

        if (packs != null) {
            int before = out.size();
            for (ModelQuad faceQuad : packs.quadsFor(material)) {
                if (faceQuad.shadeFace() == face && isFullFace(faceQuad.positions(), face)) {
                    out.add(new SourcedQuad(new ModelQuad(
                            cull, face, corners,
                            uvAt(faceQuad.positions(), faceQuad.uvs(), corners),
                            faceQuad.texture(), faceQuad.tint()), packs));
                }
            }
            if (out.size() > before) {
                return;
            }

            String particle = packs.particleTexture(material);
            if (particle != null && packs.texture(particle) != null) {
                out.add(new SourcedQuad(new ModelQuad(
                        cull, face, corners,
                        ResourcePackSource.uvCorners(ResourcePackSource.autoUv(from, to, face), 0),
                        particle, 0xFFFFFF), packs));
                return;
            }
        }

        // mapColorOf is -1 for air and for blocks that are drawn but not mapped. Neither
        // should reach here, but a box has to come out as something, and white is the one
        // colour that cannot be mistaken for a bug in the tint maths.
        int colour = MapColorSource.mapColorOf(material);
        out.add(new SourcedQuad(new ModelQuad(
                cull, face, corners,
                ResourcePackSource.uvCorners(new float[]{0, 0, 16, 16}, 0),
                MapColorSource.WHITE, colour < 0 ? 0xFFFFFF : colour), mapColors));
    }

    /**
     * Whether four corners are a whole face of the unit block: on its boundary plane and
     * reaching both edges on each in-plane axis.
     */
    private static boolean isFullFace(float[] p, Direction face) {
        int normal = face.getAxis().ordinal();
        float plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 16f : 0f;
        for (int axis = 0; axis < 3; axis++) {
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                float v = p[i * 3 + axis];
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            if (axis == normal) {
                if (Math.abs(min - plane) > EPS || Math.abs(max - plane) > EPS) {
                    return false;
                }
            } else if (min > EPS || max < 16f - EPS || min < -EPS || max > 16f + EPS) {
                // Spanning the face is not enough: a quad that overhangs it is not a face of
                // this block, and stretching a box face to it would smear the texture.
                return false;
            }
        }
        return true;
    }

    /**
     * UVs for {@code target}'s four corners, from the affine map a rectangular quad
     * defines between its positions and its UVs.
     *
     * <p>Two edges of the source quad span its plane, so a corner's coordinates along
     * them, as fractions of each edge, are the same fractions of the way along the UV
     * edges. The target is evaluated in that frame, which is why a rotated or mirrored
     * source texture stays rotated or mirrored.
     */
    private static float[] uvAt(float[] pos, float[] uv, float[] target) {
        float e1x = pos[3] - pos[0], e1y = pos[4] - pos[1], e1z = pos[5] - pos[2];
        float e2x = pos[9] - pos[0], e2y = pos[10] - pos[1], e2z = pos[11] - pos[2];
        float len1 = e1x * e1x + e1y * e1y + e1z * e1z;
        float len2 = e2x * e2x + e2y * e2y + e2z * e2z;
        float du1 = uv[2] - uv[0], dv1 = uv[3] - uv[1];
        float du2 = uv[6] - uv[0], dv2 = uv[7] - uv[1];

        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            float dx = target[i * 3] - pos[0];
            float dy = target[i * 3 + 1] - pos[1];
            float dz = target[i * 3 + 2] - pos[2];
            float s = (dx * e1x + dy * e1y + dz * e1z) / len1;
            float t = (dx * e2x + dy * e2y + dz * e2z) / len2;
            out[i * 2] = uv[0] + s * du1 + t * du2;
            out[i * 2 + 1] = uv[1] + s * dv1 + t * dv2;
        }
        return out;
    }

    private static float area(float[] from, float[] to, Direction face) {
        float dx = to[0] - from[0];
        float dy = to[1] - from[1];
        float dz = to[2] - from[2];
        return switch (face.getAxis()) {
            case X -> dy * dz;
            case Y -> dx * dz;
            case Z -> dx * dy;
        };
    }

    /** Only a face flush with the block boundary can be hidden by a neighbour. Same rule as {@link ShapeSource}. */
    private static Direction cullFaceOf(float[] from, float[] to, Direction face) {
        boolean flush = switch (face) {
            case DOWN -> from[1] <= 0f;
            case UP -> to[1] >= 16f;
            case NORTH -> from[2] <= 0f;
            case SOUTH -> to[2] >= 16f;
            case WEST -> from[0] <= 0f;
            case EAST -> to[0] >= 16f;
        };
        return flush ? face : null;
    }
}
