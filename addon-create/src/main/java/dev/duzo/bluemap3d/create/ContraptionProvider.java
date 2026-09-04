package dev.duzo.bluemap3d.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import dev.duzo.bluemap3d.Config;
import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reports every moving Create contraption as a {@link SceneObject}.
 *
 * <p>Train carriages, minecart contraptions, gantry carriages, piston and pulley
 * assemblies, and rotating bearings - windmills, mechanical and clockwork bearings,
 * elevator pulleys. All of them, from one enumeration.
 *
 * <h2>Why entities and not the railway registry</h2>
 * Create's four contraption entity types all extend
 * {@link AbstractContraptionEntity}: {@code ControlledContraptionEntity} for bearings and
 * pulleys, {@code GantryContraptionEntity}, {@code OrientedContraptionEntity} for minecart
 * contraptions, and {@code CarriageContraptionEntity} - a subclass of that last one - for
 * train carriages. So enumerating the base class gets trains and everything else at once,
 * and a fifth subclass would work without a change here.
 *
 * <p>That also disposes of what was supposed to make trains hard. A train articulates, so
 * it cannot be one rigid body - but Create already spawns <em>one entity per carriage</em>,
 * each with its own server-updated pose, so the articulation is solved before this addon
 * sees it. There is nothing to do about it.
 *
 * <p>The obvious alternative - reading {@code Create.RAILWAYS} for the train list, which
 * is what the prior-art mods do - is the wrong layer twice over. Those mods draw
 * <em>markers</em>, and a dot on a 2D map genuinely does want the registry; this wants
 * blocks and a pose, which the entity has and the registry does not. And touching
 * {@code Create} at all would drag in Registrate, which the {@code slim} artifact
 * deliberately keeps off the compile classpath. Nothing here imports it.
 *
 * <h2>The transform</h2>
 * Create defines where a contraption's local block lands in the world in
 * {@code AbstractContraptionEntity.toGlobalVector}:
 *
 * <pre>world = anchor + off + applyRotation(local - off)   where off = (0.5, 0.5, 0.5)</pre>
 *
 * and core applies
 *
 * <pre>world = position + rotation * (local - pivot)</pre>
 *
 * so the two line up term for term with a constant pivot of {@code (0.5, 0.5, 0.5)} and a
 * position of {@link AbstractContraptionEntity#getAnchorVec()} plus that same offset. The
 * anchor is read through the method rather than from the entity position because
 * {@code OrientedContraptionEntity} overrides it, which is the entire reason it is virtual.
 *
 * <p>For a bearing this puts the pivot on the bearing's axis without any special case: the
 * contraption is positioned at the block it is attached to, so anchor plus half a block is
 * that block's centre, which is what it turns about.
 *
 * <h2>What is deliberately absent</h2>
 * No {@code hiddenBlocks()}. Assembling a contraption takes its blocks out of the world, so
 * they are not in world chunks and BlueMap cannot draw them twice. The anchor block left
 * behind - the bearing, the gantry shaft, the cart - really is still there and should keep
 * rendering as ordinary terrain.
 *
 * <p>No labels. A train has a name, but a windmill does not, and half-labelling reads worse
 * than not labelling at all.
 */
public final class ContraptionProvider implements SceneObjectProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Create");

    /** FNV-1a's 64 bit offset basis. Same mixer and basis the ships addon uses. */
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;

    /** Create's own local-space offset, and therefore core's pivot. Never varies. */
    private static final Vec3 PIVOT = new Vec3(0.5, 0.5, 0.5);

    /** Basis vectors, sampled through {@code applyRotation} to recover its matrix. */
    private static final Vec3 UNIT_X = new Vec3(1, 0, 0);
    private static final Vec3 UNIT_Y = new Vec3(0, 1, 0);
    private static final Vec3 UNIT_Z = new Vec3(0, 0, 1);

    /**
     * Entity classes whose rotation could not be sampled, so it is reported once each
     * rather than every interval for as long as the contraption exists.
     */
    private final Set<String> unrotatable = ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return "create_contraptions";
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        List<? extends AbstractContraptionEntity> entities = level.getEntities(
                EntityTypeTest.forClass(AbstractContraptionEntity.class), e -> true);
        if (entities.isEmpty()) {
            return List.of();
        }

        int maxBlocks = Config.MAX_BLOCKS_PER_OBJECT.get();
        List<SceneObject> out = new ArrayList<>(entities.size());
        for (AbstractContraptionEntity entity : entities) {
            SceneObject object = toSceneObject(level, entity, maxBlocks);
            if (object != null) {
                out.add(object);
            }
        }
        return out;
    }

    /**
     * Snapshots one contraption, or {@code null} if there is nothing to draw for it.
     *
     * <p>Everything is read now rather than through the live entity. Core calls
     * {@link SceneObject#position()} and the rest on the server thread in the same pass
     * that builds this list, so a live read would be safe - but a snapshot means the
     * position, rotation and version a single publish reports are all from one instant,
     * which a live read does not guarantee if a tick lands in between.
     */
    private SceneObject toSceneObject(ServerLevel level, AbstractContraptionEntity entity,
                                      int maxBlocks) {
        if (entity.isRemoved()) {
            // Create discards a carriage entity when its train is gone, and a contraption
            // entity when it disassembles. Either way there is nothing left to read.
            return null;
        }
        Contraption contraption = entity.getContraption();
        if (contraption == null) {
            // The window between the entity existing and its contraption being attached.
            return null;
        }
        Map<BlockPos, StructureTemplate.StructureBlockInfo> source = contraption.getBlocks();
        if (source == null || source.isEmpty()) {
            return null;
        }
        if (source.size() > maxBlocks) {
            // Not politeness. Core refuses to mesh an oversized volume and returns an
            // empty mesh, which its tracker then declines to cache - so the object is
            // re-baked, in full, every publish interval for as long as it exists.
            // Skipping it here costs one comparison instead.
            //
            // The comparison is against the raw block count while core's limit applies to
            // the volume's count, which excludes air. That is conservative in the safe
            // direction: this can only refuse slightly early, never slightly late.
            //
            // Debug rather than warn, and deliberately not deduplicated: this runs every
            // publish interval, so a warn would be the same spam the guard exists to stop,
            // and the obvious fix - remembering which entities were reported - is a set
            // keyed by uuid that grows for the life of the server.
            LOGGER.debug("Skipping a {} block contraption at {}, over the {} block limit",
                    source.size(), entity.blockPosition(), maxBlocks);
            return null;
        }

        Map<BlockPos, BlockState> blocks = new HashMap<>(source.size());
        long version = FNV_OFFSET;
        for (Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo> entry : source.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue().state();
            blocks.put(pos, state);
            // Folded commutatively, because a HashMap's iteration order is not a promise
            // and an order-sensitive hash would re-mesh a contraption that merely rehashed.
            // Each entry is seeded with its position first: without that, two identical
            // states at different positions cancel each other out, and any change with an
            // even number of matching blocks - a pair of doors opening - is invisible.
            version ^= mix(mix(FNV_OFFSET, pos.asLong()), Block.getId(state));
        }
        version = mix(version, source.size());

        // Create stores block positions already relative to the anchor, which is exactly
        // what BlockVolume.of wants. Nothing to rebase.
        BlockVolume volume = BlockVolume.of(blocks, PIVOT);
        Vec3 position = entity.getAnchorVec().add(PIVOT);
        Quaternionf rotation = rotationOf(entity);
        // The dimension goes in the id because a carriage spanning a portal exists as one
        // entity per dimension, and those two entities are restored from a single shared
        // serialised tag - so they carry the same uuid. Core keys objects on provider and
        // id alone, so without this they would collide: one mesh, and the carriage
        // flickering between two dimensions at the publish rate. Namespace included:
        // two mods can both call a dimension "the_nether".
        net.minecraft.resources.ResourceLocation dim = level.dimension().location();
        String objectId = dim.getNamespace() + "/" + dim.getPath() + "/" + entity.getUUID();
        ResourceKey<Level> dimension = level.dimension();
        long finalVersion = version;

        return new SceneObject() {
            @Override
            public String id() {
                return objectId;
            }

            @Override
            public BlockVolume geometry() {
                return volume;
            }

            @Override
            public long geometryVersion() {
                return finalVersion;
            }

            @Override
            public Vec3 position() {
                return position;
            }

            @Override
            public Quaternionf rotation() {
                return rotation;
            }

            @Override
            public ResourceKey<Level> dimension() {
                return dimension;
            }
        };
    }

    /**
     * Recovers a contraption's rotation by sampling, rather than by reimplementing it.
     *
     * <p>{@code applyRotation} is abstract and each subclass does something different with
     * it - three rotations for a minecart contraption, one about an axis for a bearing,
     * nothing at all for a gantry. But every implementation is a pure rotation, so passing
     * the three basis vectors through it gives the three columns of its matrix, and the
     * quaternion follows. That is subclass-agnostic and stays correct for a subclass that
     * does not exist yet.
     *
     * <p>{@code partialTicks} is 1 because this samples the state at the end of the current
     * tick, which is what a published transform should mean. Create special-cases exactly
     * that value to return the un-interpolated current field.
     *
     * <p>The sampling is guarded because {@code ControlledContraptionEntity} hands its
     * {@code rotationAxis} field to {@code VecHelper.rotate} without a null check, while
     * Create's own renderer checks it - and that field is only ever set for bearings, so a
     * piston or pulley may well be carrying null. A contraption drawn unrotated is a much
     * smaller problem than a provider that throws part-way through a collect and takes
     * every other contraption in the level with it.
     */
    private Quaternionf rotationOf(AbstractContraptionEntity entity) {
        try {
            Vec3 x = entity.applyRotation(UNIT_X, 1f);
            Vec3 y = entity.applyRotation(UNIT_Y, 1f);
            Vec3 z = entity.applyRotation(UNIT_Z, 1f);
            // JOML's nine-float constructor reads column by column, and the images of the
            // basis vectors are the columns. Transposing this gives the inverse rotation,
            // which looks entirely plausible on a windmill and is wrong on everything else.
            Matrix3f matrix = new Matrix3f(
                    (float) x.x, (float) x.y, (float) x.z,
                    (float) y.x, (float) y.y, (float) y.z,
                    (float) z.x, (float) z.y, (float) z.z);
            return new Quaternionf().setFromNormalized(matrix);
        } catch (RuntimeException e) {
            String type = entity.getClass().getName();
            if (unrotatable.add(type)) {
                LOGGER.warn("Could not read a rotation from {}; drawing it unrotated. {}",
                        type, e.toString());
            }
            return new Quaternionf();
        }
    }

    /** FNV-1a's mixing step. */
    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }
}
