package dev.duzo.bluemap3d.bake;

import java.awt.image.BufferedImage;

/**
 * A meshed block volume: one vertex buffer, one index buffer, one texture atlas.
 *
 * <p>Positions are in block units relative to the volume's
 * {@linkplain dev.duzo.bluemap3d.api.BlockVolume#pivot() pivot}, so the browser can
 * place the mesh by writing a position and a quaternion and nothing else.
 *
 * <p>Colours are the block's directional face shading multiplied by any biome or
 * redstone tint, baked per vertex. That is what lets the mesh render with an unlit
 * material: BlueMap's marker scene contains no lights, so a lit material would come
 * out black.
 *
 * @param positions   3 floats per vertex, in block units relative to the pivot
 * @param uvs         2 floats per vertex, normalised 0..1 into {@code atlas}
 * @param colors      3 bytes per vertex, unsigned RGB
 * @param indices     3 per triangle
 * @param atlas       the texture every uv refers to
 * @param sourceBlocks how many blocks went in, for logging and limits
 */
public record BakedMesh(
        float[] positions,
        float[] uvs,
        byte[] colors,
        int[] indices,
        BufferedImage atlas,
        int sourceBlocks
) {
    /** Number of vertices. */
    public int vertexCount() {
        return positions.length / 3;
    }

    /** Number of triangles. */
    public int triangleCount() {
        return indices.length / 3;
    }

    /** Whether this mesh has nothing to draw. */
    public boolean isEmpty() {
        return indices.length == 0;
    }
}
