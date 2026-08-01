package dev.duzo.bluemap3d.bake;

import java.awt.image.BufferedImage;

/**
 * Growable accumulator for {@link BakedMesh} data.
 *
 * <p>Plain arrays rather than a collection of vertex objects: a large ship hull runs to
 * hundreds of thousands of vertices, and boxing each one costs more than the mesh.
 *
 * <p>Uvs go in as sprite-local 0..16 coordinates and are rewritten into normalised
 * atlas coordinates by {@link #build}. That ordering is forced: the atlas layout is not
 * known until every sprite has been seen, so uvs cannot be final until the walk is
 * over. Remapping in place avoids keeping a second copy of the whole mesh.
 */
final class MeshBuilder {

    private float[] positions = new float[3 * 256];
    private float[] uvs = new float[2 * 256];
    private byte[] colors = new byte[3 * 256];
    private int[] indices = new int[6 * 128];
    /** Atlas slot per quad, parallel to groups of four vertices. */
    private int[] quadSlots = new int[128];

    private int vertexCount;
    private int indexCount;
    private int quadCount;

    /**
     * Appends a quad as two triangles.
     *
     * @param pos  12 floats, four corners xyz, in block units
     * @param uv16 8 floats, four corners uv, in the sprite's own 0..16 space
     * @param slot the sprite's atlas slot
     * @param r    red 0..255
     * @param g    green 0..255
     * @param b    blue 0..255
     */
    void quad(float[] pos, float[] uv16, int slot, int r, int g, int b) {
        int base = vertexCount;

        positions = ensure(positions, vertexCount * 3 + 12);
        uvs = ensure(uvs, vertexCount * 2 + 8);
        colors = ensure(colors, vertexCount * 3 + 12);
        quadSlots = ensure(quadSlots, quadCount + 1);

        System.arraycopy(pos, 0, positions, vertexCount * 3, 12);
        System.arraycopy(uv16, 0, uvs, vertexCount * 2, 8);
        for (int i = 0; i < 4; i++) {
            int o = (vertexCount + i) * 3;
            colors[o] = (byte) r;
            colors[o + 1] = (byte) g;
            colors[o + 2] = (byte) b;
        }
        quadSlots[quadCount++] = slot;
        vertexCount += 4;

        indices = ensure(indices, indexCount + 6);
        indices[indexCount] = base;
        indices[indexCount + 1] = base + 1;
        indices[indexCount + 2] = base + 2;
        indices[indexCount + 3] = base;
        indices[indexCount + 4] = base + 2;
        indices[indexCount + 5] = base + 3;
        indexCount += 6;
    }

    int vertexCount() {
        return vertexCount;
    }

    boolean isEmpty() {
        return quadCount == 0;
    }

    /**
     * Builds the atlas, rewrites every uv into it, and returns the finished mesh.
     */
    BakedMesh build(TextureAtlas atlas, int sourceBlocks) {
        BufferedImage image = atlas.build();

        float[] scratchIn = new float[8];
        float[] scratchOut = new float[8];
        for (int q = 0; q < quadCount; q++) {
            int o = q * 8;
            System.arraycopy(uvs, o, scratchIn, 0, 8);
            atlas.mapUv(quadSlots[q], scratchIn, scratchOut);
            System.arraycopy(scratchOut, 0, uvs, o, 8);
        }

        return new BakedMesh(
                trim(positions, vertexCount * 3),
                trim(uvs, vertexCount * 2),
                trim(colors, vertexCount * 3),
                trim(indices, indexCount),
                image,
                sourceBlocks);
    }

    private static float[] ensure(float[] a, int needed) {
        if (a.length >= needed) {
            return a;
        }
        float[] bigger = new float[Math.max(needed, a.length * 2)];
        System.arraycopy(a, 0, bigger, 0, a.length);
        return bigger;
    }

    private static byte[] ensure(byte[] a, int needed) {
        if (a.length >= needed) {
            return a;
        }
        byte[] bigger = new byte[Math.max(needed, a.length * 2)];
        System.arraycopy(a, 0, bigger, 0, a.length);
        return bigger;
    }

    private static int[] ensure(int[] a, int needed) {
        if (a.length >= needed) {
            return a;
        }
        int[] bigger = new int[Math.max(needed, a.length * 2)];
        System.arraycopy(a, 0, bigger, 0, a.length);
        return bigger;
    }

    private static float[] trim(float[] a, int n) {
        float[] out = new float[n];
        System.arraycopy(a, 0, out, 0, n);
        return out;
    }

    private static byte[] trim(byte[] a, int n) {
        byte[] out = new byte[n];
        System.arraycopy(a, 0, out, 0, n);
        return out;
    }

    private static int[] trim(int[] a, int n) {
        int[] out = new int[n];
        System.arraycopy(a, 0, out, 0, n);
        return out;
    }
}
