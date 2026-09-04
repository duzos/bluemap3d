package dev.duzo.bluemap3d.bake;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Models a block from its voxel shape, textured with its particle sprite.
 *
 * <p>Sits between {@link ResourcePackSource} and {@link MapColorSource}: it only ever
 * sees blocks whose model JSON had no geometry, and it beats a map-colour cube because a
 * server does know two true things about such a block even with no model at all.
 *
 * <p>The first is its <b>shape</b>. {@code state.getShape} is the outline the client
 * draws round a block you look at, and it is computed server-side from the block's own
 * code - so a Create belt is a slab, a chest is a 14x14x14 box and a modded chest is the
 * same box in its own wood. It is a bag of axis-aligned boxes, which is precisely the
 * form {@link ModelQuad} wants.
 *
 * <p>The second is its <b>particle texture</b>. Even a block drawn entirely in code
 * declares one, because the client needs something to shower when you break it, and it
 * is the only texture in a geometry-less model. It is nearly always a face of the real
 * block: {@code create:block/belt} for a belt, the chest's own planks for a chest.
 *
 * <h2>What it is not</h2>
 * Not correct, and it does not claim to be - {@link #isFaithful()} is false, so the
 * mesher still names these blocks as unresolved. A chest gets no lid seam and no lock; a
 * belt gets no band relief. What it buys is that at map zoom the silhouette is right and
 * the colour comes from the block's real texture rather than a palette entry, which is
 * the difference between "that did not render" and "that is a belt".
 *
 * <h2>Where it goes wrong</h2>
 * <ul>
 *   <li><b>Shapeless blocks.</b> Anything with an empty outline - a torch has one, most
 *       decorative block entities do not - resolves to nothing and falls through to the
 *       map-colour cube, same as today.</li>
 *   <li><b>Shapes that are not the model.</b> A block whose outline is a full cube but
 *       whose real model is not, and the reverse. That is a smaller error than a cube in
 *       an averaged colour, but it is still an error.</li>
 *   <li><b>Tinted particles.</b> The particle sprite is used untinted. A block whose
 *       real sprite is a grey mask coloured at runtime comes out grey. Rare among the
 *       blocks that reach here, and no worse than the alternative.</li>
 * </ul>
 */
public final class ShapeSource implements BlockModelSource {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("BlueMap3D/Models");

    /** More boxes than this and the shape is detail nobody sees at map zoom. */
    private static final int MAX_BOXES = 8;

    private final ResourcePackSource models;
    private final Map<BlockState, List<ModelQuad>> cache = new HashMap<>();

    /**
     * @param models the pack source to borrow textures from - both the particle sprite
     *               lookup and the images themselves, so the atlas sees one entry per
     *               sprite however it was reached
     */
    public ShapeSource(ResourcePackSource models) {
        this.models = models;
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state) {
        return cache.computeIfAbsent(state, this::build);
    }

    @Override
    public BufferedImage texture(String texture) {
        return models.texture(texture);
    }

    @Override
    public boolean occludes(BlockState state) {
        return MapColorSource.isFullOpaqueCube(state);
    }

    @Override
    public boolean isFaithful() {
        return false;
    }

    @Override
    public String approximation() {
        return "voxel shape";
    }

    private List<ModelQuad> build(BlockState state) {
        String particle = models.particleTexture(state);
        if (particle == null || models.texture(particle) == null) {
            return List.of();
        }
        List<AABB> boxes = boxesOf(state);
        if (boxes.isEmpty() || boxes.size() > MAX_BOXES) {
            return List.of();
        }

        List<ModelQuad> quads = new ArrayList<>(boxes.size() * 6);
        for (AABB box : boxes) {
            // Shapes are in 0..1 block space; everything downstream is in 0..16.
            float[] from = {(float) box.minX * 16f, (float) box.minY * 16f, (float) box.minZ * 16f};
            float[] to = {(float) box.maxX * 16f, (float) box.maxY * 16f, (float) box.maxZ * 16f};
            if (to[0] - from[0] < 1e-4f || to[1] - from[1] < 1e-4f || to[2] - from[2] < 1e-4f) {
                // A zero-thickness box is a shape used for interaction, not a solid.
                continue;
            }
            for (Direction face : Direction.values()) {
                quads.add(new ModelQuad(
                        cullFaceOf(from, to, face),
                        face,
                        ResourcePackSource.faceCorners(from, to, face),
                        ResourcePackSource.uvCorners(ResourcePackSource.autoUv(from, to, face), 0),
                        particle,
                        0xFFFFFF));
            }
        }
        if (!quads.isEmpty()) {
            LOGGER.debug("Shape fallback: {} from {} box(es) in {}",
                    state.getBlock(), boxes.size(), particle);
        }
        return quads.isEmpty() ? List.of() : List.copyOf(quads);
    }

    /**
     * The shape to draw: the outline first, then collision.
     *
     * <p>The outline is the better of the two - it is what the client draws round a
     * block you look at, so it is the shape a player would call the block's shape - but
     * some blocks have only a collision box, so collision is the fallback rather than the
     * other way round.
     */
    private static List<AABB> boxesOf(BlockState state) {
        try {
            VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (shape.isEmpty()) {
                shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            }
            return shape.isEmpty() ? List.of() : shape.toAabbs();
        } catch (Exception e) {
            // Blocks that reach into the level for their shape. EmptyBlockGetter makes
            // those throw rather than lie, and skipping them is correct.
            return List.of();
        }
    }

    /**
     * The neighbour that hides this face, or {@code null}.
     *
     * <p>Only a face flush with the block boundary can be hidden. Interior faces of a
     * multi-box shape have to be drawn, because the box next to them may not fill the
     * gap.
     */
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
