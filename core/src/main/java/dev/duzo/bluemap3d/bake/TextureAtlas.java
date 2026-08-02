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
 * <p>Two pitfalls are handled here because both are silent when wrong:
 * <ul>
 *   <li><b>Animated textures</b> are stored as a vertical strip of frames. Packing the
 *       whole strip squashes the sprite; only the first frame is taken.</li>
 *   <li><b>Edge bleeding</b>: with a shared atlas, a uv exactly on a tile boundary can
 *       sample its neighbour. Uvs are inset by half a texel.</li>
 * </ul>
 *
 * <h2>Why this is not mipmapped</h2>
 * It was, briefly, to stop thin high-contrast geometry shimmering when the map is zoomed
 * out - the antenna on Create's redstone link is the case that shows it. It made every
 * texture blurry and had to come out.
 *
 * <p>The reason is the grid. Every tile is scaled up to the largest sprite in the atlas,
 * which is 64 or 128 pixels the moment one high-resolution texture is in the same mesh.
 * The mip level a fragment picks comes from how many atlas texels it covers, so anything
 * drawn smaller on screen than the tile size is minified and blurred - and at map zoom a
 * block is a couple of dozen pixels, well under it. The shimmer is real but it is the
 * lesser problem, and fixing it properly means either packing tiles at their native size
 * instead of a uniform grid, or clamping the mip level in a shader.
 */
final class TextureAtlas {

    /** Sprite ids in insertion order; index in this map is the grid slot. */
    private final Map<String, Integer> slots = new LinkedHashMap<>();
    private final Map<Integer, BufferedImage> images = new LinkedHashMap<>();

    private int tileSize = 16;

    /** Grid geometry, valid after {@link #build()}. */
    private int columns = 1;
    private int atlasSize = 16;

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
        atlasSize = Math.max(columns, rows) * tileSize;

        BufferedImage atlas = new BufferedImage(atlasSize, atlasSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        try {
            for (Map.Entry<Integer, BufferedImage> e : images.entrySet()) {
                int slot = e.getKey();
                int x = (slot % columns) * tileSize;
                int y = (slot / columns) * tileSize;
                g.drawImage(e.getValue(), x, y, tileSize, tileSize, null);
            }
        } finally {
            g.dispose();
        }
        return atlas;
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
        float originU = (slot % columns) * tile;
        float originV = (slot / columns) * tile;

        // Half a texel, so a uv sitting exactly on the boundary cannot sample the
        // neighbouring tile.
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
}
