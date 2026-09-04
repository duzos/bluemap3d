package dev.duzo.bluemap3d.bake;

import dev.duzo.bluemap3d.Config;
import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.ModelAttachment;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
        List<ModelAttachment> allAttachments = new ArrayList<>(volume.attachments());
        if (blocks == 0 && allAttachments.isEmpty()) {
            return empty();
        }
        if (blocks > maxBlocks) {
            LOGGER.warn("Refusing to mesh a volume of {} blocks (limit {}). "
                    + "Raise bluemap3d.maxBlocksPerObject if this is expected.", blocks, maxBlocks);
            return empty();
        }
        // A block count of zero clears the check above no matter how many attachments
        // ride along with it, which is exactly the case an attachments-only volume
        // (BlockVolume.attachments) is built for. This is the ceiling that actually
        // sees it. Refusing the whole object rather than dropping the excess matches
        // maxBlocks: a missing object with a warning in the log is a far more visible
        // failure than a silently truncated one, and for a volume that is nothing but
        // attachments, dropping the excess could be most of what there was to draw.
        int maxAttachments = Config.MAX_ATTACHMENTS_PER_OBJECT.get();
        if (allAttachments.size() > maxAttachments) {
            LOGGER.warn("Refusing to mesh a volume of {} attachments (limit {}). "
                    + "Raise bluemap3d.maxAttachmentsPerObject if this is expected.",
                    allAttachments.size(), maxAttachments);
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
                        .getKey(state.getBlock())
                        + " (" + (source == null ? "not drawn" : source.approximation()) + ")");
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
        //
        // Static attachments first, then animated ones, so that everything the browser
        // animates is one contiguous run at the end of the buffer. A BufferGeometry has
        // exactly one draw range, so that tail is the only shape in which the parent mesh
        // can say "draw everything except the parts my children draw". Fold these back
        // into one loop and every animated part renders twice, once stuck in place by the
        // parent and once moving, z-fighting with itself.
        //
        // The cap is applied here, at the partition, and not while emitting. Demoting an
        // attachment after the static pass has closed would leave its geometry past
        // staticIndexCount with no node claiming it, so neither the parent nor any child
        // would draw it and it would vanish outright.
        List<ModelAttachment> staticAttachments = new ArrayList<>();
        List<ModelAttachment> animated = new ArrayList<>();
        for (ModelAttachment attachment : allAttachments) {
            (attachment.motion() == null ? staticAttachments : animated).add(attachment);
        }
        int cap = Config.MAX_SPIN_NODES_PER_OBJECT.get();
        if (animated.size() > cap) {
            LOGGER.warn("{} animated attachments exceeds maxSpinNodesPerObject ({}); "
                    + "the excess is drawn in place instead", animated.size(), cap);
            staticAttachments.addAll(animated.subList(cap, animated.size()));
            animated = animated.subList(0, cap);
        }

        for (ModelAttachment attachment : staticAttachments) {
            emitAttachment(attachment, pivot, atlas, mesh, worldPos);
        }
        int staticIndexCount = mesh.indexCount();

        List<BakedMesh.Node> nodes = new ArrayList<>(animated.size());
        for (ModelAttachment attachment : animated) {
            int start = mesh.indexCount();
            emitAttachment(attachment, pivot, atlas, mesh, worldPos);
            int count = mesh.indexCount() - start;
            if (count == 0) {
                // No source resolved the model. An empty range would make the browser
                // compute a NaN bounding sphere, and a NaN sphere makes frustum culling
                // behave unpredictably rather than merely wrongly.
                continue;
            }
            nodes.add(nodeFor(attachment, pivot, start, count));
        }

        if (!unresolved.isEmpty()) {
            LOGGER.info("No model found for {} block type(s); approximated: {}. "
                            + "Supply models for these through bluemap3d.assets.sources "
                            + "if you want them textured.",
                    unresolved.size(), String.join(", ", unresolved));
        }

        if (mesh.isEmpty()) {
            return empty();
        }
        BakedMesh baked = mesh.build(atlas, blocks, staticIndexCount, nodes);
        LOGGER.debug("Meshed {} blocks -> {} vertices, {} triangles, {} sprites, {} nodes",
                blocks, baked.vertexCount(), baked.triangleCount(), atlas.size(), nodes.size());
        return baked;
    }

    /**
     * Meshes one attachment at its block within the volume.
     *
     * <p>Never culled: an attachment is there precisely because no block state describes it,
     * so there is no neighbour relationship to reason about, and CC's upgrade models already
     * omit the face that sits flush against the turtle.
     */
    private void emitAttachment(ModelAttachment attachment, Vec3 pivot,
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

    /**
     * Puts a motion into the same space the attachment's vertices ended up in.
     *
     * <p>The components take different routes through the attachment transform, and
     * getting any of them wrong is invisible until something is placed off-centre:
     * a pivot is a point, an axis is a direction, and a radius is a length.
     */
    private static BakedMesh.Node nodeFor(ModelAttachment attachment, Vec3 volumePivot,
                                          int indexStart, int indexCount) {
        Matrix4f matrix = attachment.transform();

        Vector3f scale = matrix.getScale(new Vector3f());
        float s = Math.max(scale.x, Math.max(scale.y, scale.z));
        if (Math.abs(scale.x - scale.y) > 1e-4f || Math.abs(scale.y - scale.z) > 1e-4f) {
            LOGGER.warn("Animated attachment {} has a non-uniform scale; one radius cannot "
                    + "describe it, using the largest component", attachment.model());
        }

        return switch (attachment.motion()) {
            case ModelAttachment.Spin spin -> new BakedMesh.Node(
                    BakedMesh.KIND_SPIN, indexStart, indexCount,
                    pivotFor(spin.pivot(), matrix, attachment, volumePivot),
                    axisFor(spin.axis(), matrix),
                    spin.radius() / 16f * s, 0f, 0f);
            case ModelAttachment.Oscillate oscillate -> new BakedMesh.Node(
                    BakedMesh.KIND_OSCILLATE, indexStart, indexCount,
                    // No pivot to orbit or turn about: the offset is added straight to
                    // the baked position, so any wire value would do, and zero is the
                    // one the browser's bounding-sphere maths reads most naturally.
                    new float[]{0f, 0f, 0f},
                    axisFor(oscillate.axis(), matrix),
                    // period lives in the same model-space (0..16) units as amplitude and
                    // radius, so it is scaled here the same way - this is the one place
                    // that conversion happens, and every caller passes period in that
                    // space regardless of what unit its own source figure started in.
                    oscillate.amplitude() / 16f * s, oscillate.period() / 16f * s, 0f);
            case ModelAttachment.Orbit orbit -> new BakedMesh.Node(
                    BakedMesh.KIND_ORBIT, indexStart, indexCount,
                    pivotFor(orbit.pivot(), matrix, attachment, volumePivot),
                    axisFor(orbit.axis(), matrix),
                    // See the Oscillate case above: period is scaled the same way as
                    // radius here, not left raw.
                    orbit.radius() / 16f * s, orbit.period() / 16f * s, 0f);
            case ModelAttachment.Rate rate -> new BakedMesh.Node(
                    BakedMesh.KIND_RATE, indexStart, indexCount,
                    pivotFor(rate.pivot(), matrix, attachment, volumePivot),
                    axisFor(rate.axis(), matrix),
                    // No radius or period: a constant rate is not a length, so the
                    // transform's scale has nothing to act on.
                    0f, 0f, rate.radiansPerSecond());
        };
    }

    /**
     * A model-space pivot, carried through the attachment's transform and into the same
     * pivot-relative block-unit space the mesh's vertices ended up in.
     */
    private static float[] pivotFor(Vector3f modelSpacePivot, Matrix4f matrix,
                                    ModelAttachment attachment, Vec3 volumePivot) {
        // Read into a scratch vector. A record accessor hands back the stored reference
        // and the compact constructor only copies on the way in, so transforming in place
        // would permanently mutate the attachment - fine on a first bake, wrong on every
        // re-bake after it, and re-baking is routine.
        Vector3f p = new Vector3f(modelSpacePivot).mul(1f / 16f);
        matrix.transformPosition(p);
        return new float[]{
                (float) (p.x + attachment.at().getX() - volumePivot.x),
                (float) (p.y + attachment.at().getY() - volumePivot.y),
                (float) (p.z + attachment.at().getZ() - volumePivot.z)};
    }

    /** A model-space direction, carried through the attachment's transform. */
    private static float[] axisFor(Vector3f modelSpaceAxis, Matrix4f matrix) {
        // Direction, not position: a translated attachment must not tilt its own axis.
        Vector3f a = matrix.transformDirection(new Vector3f(modelSpaceAxis)).normalize();
        return new float[]{a.x, a.y, a.z};
    }

    private static BakedMesh empty() {
        return new BakedMesh(new float[0], new float[0], new byte[0], new int[0], null, 0,
                0, List.of());
    }
}
