package dev.duzo.bluemap3d.bake;

import dev.duzo.bluemap3d.api.BlockVolume;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Turns a {@link BlockVolume} into a {@link BakedMesh}.
 *
 * <p>One mesher serves a turtle, a ship and a train carriage, because
 * {@link BlockVolume} has already flattened those into the same shape. What is left
 * here is the part that is genuinely common: ask a {@link BlockModelSource} for a
 * block's quads, drop the faces a neighbour hides, move them from model space into
 * block units relative to the pivot, bake the shading into vertex colours, and pack the
 * textures.
 *
 * <p>Sources are tried in order and the first to return any quads wins, per block. That
 * is what lets a textured {@link ResourcePackSource} handle the blocks whose assets the
 * server can see while {@link MapColorSource} silently covers the rest, instead of a
 * missing texture taking out the whole object.
 */
public final class VolumeMesher {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Mesher");

    private final List<BlockModelSource> sources;
    private final int maxBlocks;

    /**
     * @param sources   model sources in priority order; must not be empty
     * @param maxBlocks refuse to mesh volumes larger than this, so one absurd object
     *                  cannot stall the server or blow out the browser's memory
     */
    public VolumeMesher(List<BlockModelSource> sources, int maxBlocks) {
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("need at least one BlockModelSource");
        }
        this.sources = List.copyOf(sources);
        this.maxBlocks = maxBlocks;
    }

    /**
     * Meshes a volume.
     *
     * <p>Pure with respect to the volume, and touches no level state, so it is safe to
     * call off the server thread as long as the volume itself is a snapshot - which
     * every {@link BlockVolume} factory guarantees.
     *
     * @return the mesh, or an empty mesh if there was nothing to draw or the volume was
     *         over {@code maxBlocks}
     */
    public BakedMesh mesh(BlockVolume volume) {
        int blocks = volume.blockCount();
        if (blocks == 0) {
            return empty();
        }
        if (blocks > maxBlocks) {
            LOGGER.warn("Refusing to mesh a volume of {} blocks (limit {}). "
                    + "Raise bluemap3d.maxBlocksPerObject if this is expected.", blocks, maxBlocks);
            return empty();
        }

        TextureAtlas atlas = new TextureAtlas();
        MeshBuilder mesh = new MeshBuilder();
        Vec3 pivot = volume.pivot();
        BlockModelSource occluder = sources.get(0);

        float[] worldPos = new float[12];
        // Blocks nothing could model faithfully, reported once at the end. An admin
        // seeing a grey lump wants the block's name, and there is nowhere else to get
        // it: the fallback is per block and silent by design.
        java.util.Set<String> unresolved = new java.util.TreeSet<>();

        volume.forEachBlock((x, y, z, state) -> {
            BlockModelSource source = null;
            List<ModelQuad> quads = List.of();
            for (BlockModelSource candidate : sources) {
                List<ModelQuad> got = candidate.quadsFor(state);
                if (!got.isEmpty()) {
                    source = candidate;
                    quads = got;
                    break;
                }
            }
            if (source == null || !source.isFaithful()) {
                unresolved.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(state.getBlock()).toString());
            }
            if (source == null) {
                return;
            }

            for (ModelQuad quad : quads) {
                Direction cull = quad.cullFace();
                if (cull != null) {
                    BlockState neighbour = volume.stateAt(
                            x + cull.getStepX(), y + cull.getStepY(), z + cull.getStepZ());
                    if (occluder.occludes(neighbour)) {
                        continue;
                    }
                }

                float[] model = quad.positions();
                for (int i = 0; i < 4; i++) {
                    // Model space is 0..16 per block; the mesh is in block units,
                    // relative to the pivot so the browser only has to write a
                    // position and a quaternion.
                    //
                    // Subtract the pivot first, and in double. A volume's coordinates are
                    // the source's, and a Sable ship's are its plot's - about 2.05e7,
                    // which is past where a float can tell one block from the next, let
                    // alone a sixteenth of one. Written the obvious way round, `x + m/16f`
                    // is evaluated as a float and the model offset is gone before the
                    // pivot ever gets subtracted. It looks fine on a turtle at the origin
                    // and shreds a ship.
                    worldPos[i * 3] = (float) (x - pivot.x + model[i * 3] / 16.0);
                    worldPos[i * 3 + 1] = (float) (y - pivot.y + model[i * 3 + 1] / 16.0);
                    worldPos[i * 3 + 2] = (float) (z - pivot.z + model[i * 3 + 2] / 16.0);
                }

                float shade = ModelQuad.shadeOf(quad.shadeFace());
                int tint = quad.tint();
                int r = Math.round(((tint >> 16) & 0xFF) * shade);
                int g = Math.round(((tint >> 8) & 0xFF) * shade);
                int b = Math.round((tint & 0xFF) * shade);

                int slot = atlas.add(quad.texture(), source.texture(quad.texture()));
                mesh.quad(worldPos, quad.uvs(), slot, r, g, b);
            }
        });

        // Extra models the block states cannot describe: a turtle's modem, a sign's text.
        // Emitted into the same buffer and atlas, so they cost nothing extra at render time.
        for (dev.duzo.bluemap3d.api.ModelAttachment attachment : volume.attachments()) {
            emitAttachment(attachment, pivot, atlas, mesh, worldPos);
        }

        if (!unresolved.isEmpty()) {
            LOGGER.info("No model found for {} block type(s); drawn as map-colour cubes: {}. "
                            + "Supply models for these through bluemap3d.assets.sources "
                            + "if you want them textured.",
                    unresolved.size(), String.join(", ", unresolved));
        }

        if (mesh.isEmpty()) {
            return empty();
        }
        BakedMesh baked = mesh.build(atlas, blocks);
        LOGGER.debug("Meshed {} blocks -> {} vertices, {} triangles, {} sprites",
                blocks, baked.vertexCount(), baked.triangleCount(), atlas.size());
        return baked;
    }

    /**
     * Meshes one attachment at its block within the volume.
     *
     * <p>Never culled: an attachment is there precisely because no block state describes it,
     * so there is no neighbour relationship to reason about, and CC's upgrade models already
     * omit the face that sits flush against the turtle.
     */
    private void emitAttachment(dev.duzo.bluemap3d.api.ModelAttachment attachment, Vec3 pivot,
                                TextureAtlas atlas, MeshBuilder mesh, float[] worldPos) {
        BlockModelSource source = null;
        List<ModelQuad> quads = List.of();
        for (BlockModelSource candidate : sources) {
            List<ModelQuad> got = candidate.quadsForModel(attachment.model(), attachment.textures());
            if (!got.isEmpty()) {
                source = candidate;
                quads = got;
                break;
            }
        }
        if (source == null) {
            // Most likely an item model, which carries a sprite and no elements because the
            // client builds its shape by extruding that sprite. Nothing to draw here.
            LOGGER.debug("No geometry for attachment {}", attachment.model());
            return;
        }

        int x = attachment.at().getX();
        int y = attachment.at().getY();
        int z = attachment.at().getZ();
        boolean transformed = attachment.hasTransform();
        org.joml.Matrix4f matrix = attachment.transform();
        org.joml.Vector3f scratch = new org.joml.Vector3f();

        for (ModelQuad quad : quads) {
            float[] model = quad.positions();
            for (int i = 0; i < 4; i++) {
                // Model space is 0..16 per block; the transform, when there is one, is
                // defined in block units, so scale down first and transform after.
                float mx = model[i * 3] / 16f;
                float my = model[i * 3 + 1] / 16f;
                float mz = model[i * 3 + 2] / 16f;
                if (transformed) {
                    matrix.transformPosition(scratch.set(mx, my, mz));
                    mx = scratch.x;
                    my = scratch.y;
                    mz = scratch.z;
                }
                // Pivot first and in double, for the same reason as above.
                worldPos[i * 3] = (float) (x - pivot.x + mx);
                worldPos[i * 3 + 1] = (float) (y - pivot.y + my);
                worldPos[i * 3 + 2] = (float) (z - pivot.z + mz);
            }
            float shade = ModelQuad.shadeOf(quad.shadeFace());
            int tint = quad.tint();
            int slot = atlas.add(quad.texture(), source.texture(quad.texture()));
            mesh.quad(worldPos, quad.uvs(), slot,
                    Math.round(((tint >> 16) & 0xFF) * shade),
                    Math.round(((tint >> 8) & 0xFF) * shade),
                    Math.round((tint & 0xFF) * shade));
        }
    }

    private static BakedMesh empty() {
        return new BakedMesh(new float[0], new float[0], new byte[0], new int[0], null, 0);
    }
}
