package dev.duzo.bluemap3d.bake;

import net.minecraft.core.Direction;

/**
 * One textured quad of a block model, in Minecraft's 0..16 model space.
 *
 * <p>This is the currency between a {@link BlockModelSource} and the
 * {@link VolumeMesher}: the source knows how to turn a block state into quads, the
 * mesher knows how to place, cull and pack them. Neither needs to know how the other
 * works, which is what lets a map-colour cube and a full resource-pack model go
 * through the same path.
 *
 * @param cullFace  the neighbour direction that hides this quad, or {@code null} if it
 *                  is always drawn. Interior faces of multi-cuboid models have no cull
 *                  face - only faces flush with the block boundary do.
 * @param shadeFace the face whose directional tint applies. Minecraft shades by the
 *                  nominal facing, not the true normal, so a rotated element still
 *                  takes its parent face's brightness.
 * @param positions 12 floats: four corners as xyz, in 0..16 model space, wound so the
 *                  front face is counter-clockwise.
 * @param uvs       8 floats: four corners as uv, in 0..16 texture space, matching the
 *                  corner order of {@code positions}.
 * @param texture   resolved sprite id, e.g. {@code "minecraft:block/oak_planks"}.
 * @param tint      an 0xRRGGBB multiplier applied on top of the face shade; 0xFFFFFF
 *                  for none.
 */
public record ModelQuad(
        Direction cullFace,
        Direction shadeFace,
        float[] positions,
        float[] uvs,
        String texture,
        int tint
) {
    /**
     * Minecraft's directional face shading, as used by the inventory and by every
     * block renderer. Baking this into vertex colours is what makes an unlit material
     * read as a Minecraft block rather than a flat silhouette.
     */
    public static float shadeOf(Direction face) {
        if (face == null) {
            return 1.0f;
        }
        return switch (face) {
            case UP -> 1.0f;
            case DOWN -> 0.5f;
            case NORTH, SOUTH -> 0.8f;
            case EAST, WEST -> 0.6f;
        };
    }
}
