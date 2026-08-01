package dev.duzo.bluemap3d.bake;

import net.minecraft.core.Direction;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds geometry for an item model out of its sprite, the way the client does.
 *
 * <p>Vanilla item models mostly have no geometry. {@code minecraft:item/diamond_pickaxe} is
 * just {@code parent: item/handheld} and a {@code layer0} texture; the shape you see in game
 * is generated at bake time by extruding the sprite - a quad on the front, one on the back,
 * and a rim around every boundary between an opaque pixel and a transparent one. Without
 * that, an item model resolves to nothing and anything holding an item renders empty.
 *
 * <p>This lives in core rather than in the turtle addon because it is not about turtles. Any
 * {@link dev.duzo.bluemap3d.api.ModelAttachment} naming an item model gets it: a turtle's
 * pickaxe today, an item frame's contents or a Create belt's cargo later.
 *
 * <h2>Cost</h2>
 * A naive version emits one quad per opaque pixel per side, which is around 600 quads for a
 * 16x16 tool. Front and back faces are merged into horizontal runs first, which typically
 * cuts that by an order of magnitude and costs a few lines. Edges stay per-pixel: they are a
 * boundary, so there are far fewer of them, and merging them is fiddlier than it is worth.
 */
final class ItemSpriteMesher {

    /** A pixel counts as solid at or above this alpha - the same cutoff the mesh renders with. */
    private static final int ALPHA_THRESHOLD = 128;

    /** Thickness in model units. The client extrudes items to 1/16 of a block, which is 1. */
    private static final float THICKNESS = 1f;

    private ItemSpriteMesher() {
    }

    /**
     * Extrudes a sprite into quads, in the 0..16 model space of a single block.
     *
     * @param sprite  the item texture; only its first frame is used if it is animated
     * @param texture the sprite id, so the quads can be packed into the atlas
     * @return the quads, or empty if the sprite is blank
     */
    static List<ModelQuad> extrude(BufferedImage sprite, String texture) {
        int w = sprite.getWidth();
        int h = sprite.getHeight();
        if (w <= 0 || h <= 0) {
            return List.of();
        }
        // Animated item textures are a vertical strip of square frames; take the first.
        if (h > w && h % w == 0) {
            h = w;
        }

        boolean[][] solid = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                solid[y][x] = ((sprite.getRGB(x, y) >>> 24) & 0xFF) >= ALPHA_THRESHOLD;
            }
        }

        List<ModelQuad> out = new ArrayList<>();
        float front = 8f + THICKNESS / 2f;
        float back = 8f - THICKNESS / 2f;
        float px = 16f / w;
        float py = 16f / h;

        // Front and back, merged into horizontal runs.
        for (int y = 0; y < h; y++) {
            int x = 0;
            while (x < w) {
                if (!solid[y][x]) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < w && solid[y][x]) {
                    x++;
                }
                addRun(out, texture, start, x, y, px, py, w, h, front, back);
            }
        }

        // Rim: one quad wherever a solid pixel meets a transparent one, or the sprite edge.
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!solid[y][x]) {
                    continue;
                }
                if (x == 0 || !solid[y][x - 1]) {
                    addEdge(out, texture, x, y, px, py, w, h, front, back, Direction.WEST);
                }
                if (x == w - 1 || !solid[y][x + 1]) {
                    addEdge(out, texture, x, y, px, py, w, h, front, back, Direction.EAST);
                }
                if (y == 0 || !solid[y - 1][x]) {
                    addEdge(out, texture, x, y, px, py, w, h, front, back, Direction.UP);
                }
                if (y == h - 1 || !solid[y + 1][x]) {
                    addEdge(out, texture, x, y, px, py, w, h, front, back, Direction.DOWN);
                }
            }
        }
        return out;
    }

    /**
     * A horizontal run of pixels as a front and a back quad.
     *
     * <p>Sprite rows count downwards and model space counts upwards, so y is flipped here;
     * getting that wrong renders every item upside down.
     */
    private static void addRun(List<ModelQuad> out, String texture, int x0, int x1, int y,
                               float px, float py, int w, int h, float front, float back) {
        float left = x0 * px;
        float right = x1 * px;
        float top = 16f - y * py;
        float bottom = 16f - (y + 1) * py;

        float u0 = x0 * 16f / w;
        float u1 = x1 * 16f / w;
        float v0 = y * 16f / h;
        float v1 = (y + 1) * 16f / h;

        // Front, facing +z.
        out.add(new ModelQuad(null, Direction.SOUTH,
                new float[]{left, top, front, right, top, front, right, bottom, front, left, bottom, front},
                new float[]{u0, v0, u1, v0, u1, v1, u0, v1}, texture, 0xFFFFFF));
        // Back, facing -z, wound the other way round.
        out.add(new ModelQuad(null, Direction.NORTH,
                new float[]{right, top, back, left, top, back, left, bottom, back, right, bottom, back},
                new float[]{u1, v0, u0, v0, u0, v1, u1, v1}, texture, 0xFFFFFF));
    }

    /** One pixel's worth of rim on the given side. */
    private static void addEdge(List<ModelQuad> out, String texture, int x, int y,
                                float px, float py, int w, int h, float front, float back,
                                Direction side) {
        float left = x * px;
        float right = (x + 1) * px;
        float top = 16f - y * py;
        float bottom = 16f - (y + 1) * py;

        // The rim samples the pixel it belongs to, so it takes that pixel's colour.
        float u0 = x * 16f / w;
        float u1 = (x + 1) * 16f / w;
        float v0 = y * 16f / h;
        float v1 = (y + 1) * 16f / h;
        float[] uv = {u0, v0, u1, v0, u1, v1, u0, v1};

        float[] pos = switch (side) {
            case WEST -> new float[]{left, top, back, left, top, front, left, bottom, front, left, bottom, back};
            case EAST -> new float[]{right, top, front, right, top, back, right, bottom, back, right, bottom, front};
            case UP -> new float[]{left, top, back, right, top, back, right, top, front, left, top, front};
            default -> new float[]{left, bottom, front, right, bottom, front, right, bottom, back, left, bottom, back};
        };
        out.add(new ModelQuad(null, side, pos, uv, texture, 0xFFFFFF));
    }
}
