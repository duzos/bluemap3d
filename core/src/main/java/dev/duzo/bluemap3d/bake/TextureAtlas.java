package dev.duzo.bluemap3d.bake;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Packs the sprites a mesh needs into one image, and rewrites uvs to match.
 *
 * <p>A uniform grid rather than a rectangle packer. Minecraft sprites are square and
 * almost always the same size within a pack, so a grid wastes very little and avoids a
 * packer's worth of code and bugs. Sprites of other sizes are scaled to the tile size.
 *
 * <p>Three pitfalls are handled here because all of them are silent when wrong:
 * <ul>
 *   <li><b>Animated textures</b> are stored as a vertical strip of frames. Packing the
 *       whole strip squashes the sprite; only the first frame is taken.</li>
 *   <li><b>Edge bleeding</b>: with a shared atlas, a uv exactly on a tile boundary can
 *       sample its neighbour. Uvs are inset by half a texel.</li>
 *   <li><b>Minification shimmer</b>: see the gutter below.</li>
 * </ul>
 *
 * <h2>The gutter</h2>
 * Every tile is surrounded by a border of its own edge pixels, repeated outwards. It
 * exists so the browser can mipmap the atlas.
 *
 * <p>Without mipmaps, a sprite drawn smaller than its own resolution - which is what
 * happens to anything on a map the moment you zoom out - is point-sampled, and each
 * screen pixel picks whichever texel it lands on. Move the camera a fraction and it
 * lands on a different one. On a large flat face nobody notices; on something a
 * sixteenth of a block thick and high-contrast, like Create's redstone link antenna,
 * it reads as the texture flickering.
 *
 * <p>Mipmaps fix that, but not on a bare atlas: at every level the filter averages a
 * wider area, and past the first level it would be averaging across the tile boundary
 * into an unrelated sprite. The gutter is what it averages instead, and edge pixels
 * repeated outward are exactly what a standalone texture would have given.
 */
final class TextureAtlas {

    /**
     * Border of repeated edge pixels around each tile, as a fraction of the tile.
     *
     * <p>An eighth is two pixels on a 16 pixel sprite, which keeps the first two mip
     * levels clean - by the third a tile is down to a couple of pixels on screen and
     * whatever it bleeds is indistinguishable anyway.
     */
    private static final int GUTTER_DIVISOR = 8;

    /** Sprite ids in insertion order; index in this map is the grid slot. */
    private final Map<String, Integer> slots = new LinkedHashMap<>();
    private final Map<Integer, BufferedImage> images = new LinkedHashMap<>();

    private int tileSize = 16;

    /** Grid geometry, valid after {@link #build()}. */
    private int columns = 1;
    private int atlasSize = 16;
    private int gutter = 2;
    private int cellSize = 20;

    /**
     * Adds a sprite if absent and returns its slot.
     *
     * @param texture sprite id
     * @param image   the sprite, or {@code null} for a solid-white placeholder
     */
    int add(String texture, BufferedImage image) {
        Integer existing = slots.get(texture);
        if (existing != null) {
            return existing;
        }
        int slot = slots.size();
        slots.put(texture, slot);

        BufferedImage sprite = image == null ? white() : firstFrame(image);
        tileSize = Math.max(tileSize, Math.min(sprite.getWidth(), 128));
        images.put(slot, sprite);
        return slot;
    }

    int size() {
        return slots.size();
    }

    /**
     * Builds the atlas image and freezes the grid so {@link #mapUv} can be used.
     */
    BufferedImage build() {
        int n = Math.max(slots.size(), 1);
        columns = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil(n / (double) columns);
        gutter = Math.max(1, tileSize / GUTTER_DIVISOR);
        cellSize = tileSize + 2 * gutter;
        // Rounded up to a power of two. The grid rarely lands on one by itself once the
        // gutter is added, and a non-power-of-two texture cannot be mipmapped at all on
        // WebGL 1 - it renders black rather than degrading. The slack is unused atlas
        // that no uv points at.
        atlasSize = nextPowerOfTwo(Math.max(columns, rows) * cellSize);

        BufferedImage atlas = new BufferedImage(atlasSize, atlasSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        try {
            for (Map.Entry<Integer, BufferedImage> e : images.entrySet()) {
                int slot = e.getKey();
                g.drawImage(cell(e.getValue()),
                        (slot % columns) * cellSize, (slot / columns) * cellSize, null);
            }
        } finally {
            g.dispose();
        }
        return atlas;
    }

    /**
     * One sprite, scaled to the tile size and surrounded by its own repeated edge pixels.
     *
     * <p>Built as its own image and then blitted whole. Extending the border in place, by
     * reading the atlas back through the same {@code Graphics2D} that is writing it, is
     * not defined to work and in practice smears each sprite sideways across its
     * neighbours - which then shows up as faces disappearing, because a uv window landing
     * on the wrong content plus an alpha test is an invisible quad.
     */
    private BufferedImage cell(BufferedImage sprite) {
        BufferedImage out = new BufferedImage(cellSize, cellSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(sprite, gutter, gutter, tileSize, tileSize, null);
        } finally {
            g.dispose();
        }

        // Pixel copies rather than more drawImage calls: the source and the destination
        // are the same raster, so this has to be a read-then-write and nothing else.
        int last = gutter + tileSize - 1;
        for (int y = gutter; y <= last; y++) {
            int left = out.getRGB(gutter, y);
            int right = out.getRGB(last, y);
            for (int i = 0; i < gutter; i++) {
                out.setRGB(gutter - 1 - i, y, left);
                out.setRGB(last + 1 + i, y, right);
            }
        }
        for (int x = 0; x < cellSize; x++) {
            int top = out.getRGB(x, gutter);
            int bottom = out.getRGB(x, last);
            for (int i = 0; i < gutter; i++) {
                out.setRGB(x, gutter - 1 - i, top);
                out.setRGB(x, last + 1 + i, bottom);
            }
        }
        return out;
    }

    /**
     * Maps a quad's uvs from a sprite's own 0..16 space into normalised atlas space.
     *
     * <p>Must be called after {@link #build()}.
     *
     * @param slot the sprite's slot
     * @param uv16 8 floats, four corners in 0..16 sprite space
     * @param out  8 floats, four corners in 0..1 atlas space
     */
    void mapUv(int slot, float[] uv16, float[] out) {
        float tile = tileSize / (float) atlasSize;
        float cell = cellSize / (float) atlasSize;
        float pad = gutter / (float) atlasSize;
        // The tile itself, inside its gutter.
        float originU = (slot % columns) * cell + pad;
        float originV = (slot / columns) * cell + pad;

        // Half a texel, so a uv sitting exactly on the boundary lands inside the tile
        // rather than on the first pixel of the gutter.
        float inset = 0.5f / atlasSize;

        for (int i = 0; i < 4; i++) {
            float u = clamp(uv16[i * 2] / 16f, 0f, 1f);
            float v = clamp(uv16[i * 2 + 1] / 16f, 0f, 1f);
            out[i * 2] = originU + inset + u * (tile - 2 * inset);
            out[i * 2 + 1] = originV + inset + v * (tile - 2 * inset);
        }
    }

    /**
     * Animated sprites are a vertical strip of square frames. Take the first.
     */
    private static BufferedImage firstFrame(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (h > w && w > 0 && h % w == 0) {
            return image.getSubimage(0, 0, w, w);
        }
        return image;
    }

    private static BufferedImage white() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                img.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        return img;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static int nextPowerOfTwo(int value) {
        int n = 16;
        while (n < value) {
            n <<= 1;
        }
        return n;
    }
}
