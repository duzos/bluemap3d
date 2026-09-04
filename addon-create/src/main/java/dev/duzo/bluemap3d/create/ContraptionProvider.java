package dev.duzo.bluemap3d.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import dev.duzo.bluemap3d.Config;
import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.ModelAttachment;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
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
 * is what the prior-art mods do - is the wrong layer twice over for contraptions. Those
 * mods draw <em>markers</em>, and a dot on a 2D map genuinely does want the registry; this
 * wants blocks and a pose, which the entity has and the registry does not. (The railway
 * graph earns its keep elsewhere in this addon, for curved track discovery - see
 * {@link TrackCurves} and {@link CurvedTrackProvider} - which is a different problem with
 * no entity to read a pose from.)
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

    // -----------------------------------------------------------------------------
    // Bogey wheels
    // -----------------------------------------------------------------------------
    //
    // A bogey's wheels are drawn by Create's client-only BogeyRenderer, from an obj mesh,
    // never by the block model - the blockstate's only model is the plain rail-top piece,
    // shared by every bogey regardless of size. So a bogey meshed from its block state
    // alone comes out with a track on top of thin air. Attachments fill in what the
    // renderer would otherwise draw.
    //
    // A large bogey is not a scaled-up small one - StandardBogeyRenderer's two nested
    // renderers, $Small and $Large, draw entirely different part sets. Decompiled by
    // javap (see the notes below), $Small draws BOGEY_FRAME plus one SMALL_BOGEY_WHEELS
    // pair per axle; $Large never draws BOGEY_FRAME at all; it draws BOGEY_DRIVE (the
    // gearbox housing, standing where the frame would), BOGEY_DRIVE_BELT, BOGEY_PISTON,
    // one LARGE_BOGEY_WHEELS pair, and BOGEY_PIN. Both also draw a couple of plain
    // create:shaft blocks as visible connecting rod stubs; those are vanilla block
    // models rather than named partials and are left undrawn here as an accepted gap,
    // the same way this whole feature already accepts that a block-state mesh alone
    // cannot show them.
    //
    // Every model here is real geometry in Create's jar and read the normal way; nothing
    // Create-specific is on the classpath for them. Detecting a bogey block and reading
    // its axis needs no Create class either - the axis property Create's own
    // AbstractBogeyBlock exposes is just vanilla BlockStateProperties.HORIZONTAL_AXIS.

    private static final ResourceLocation SMALL_BOGEY =
            ResourceLocation.fromNamespaceAndPath("create", "small_bogey");
    private static final ResourceLocation LARGE_BOGEY =
            ResourceLocation.fromNamespaceAndPath("create", "large_bogey");
    private static final ResourceLocation BOGEY_FRAME_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/track/bogey/bogey_frame");
    private static final ResourceLocation SMALL_BOGEY_WHEEL_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/track/bogey/bogey_wheel");
    private static final ResourceLocation LARGE_BOGEY_WHEEL_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/track/bogey/bogey_drive_wheel");
    private static final ResourceLocation BOGEY_DRIVE_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/track/bogey/bogey_drive");
    private static final ResourceLocation BOGEY_DRIVE_BELT_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/track/bogey/bogey_drive_belt");
    private static final ResourceLocation BOGEY_PISTON_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/track/bogey/bogey_drive_piston");
    private static final ResourceLocation BOGEY_PIN_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/track/bogey/bogey_drive_wheel_pin");

    // Where the parts sit relative to the bogey block, and how fast the wheels should
    // turn. Create's own placement lives in StandardBogeyRenderer, a client class a
    // dedicated server cannot load - so these are not read at runtime, but they are not a
    // guess either. They were decompiled with javap -c -p from that renderer's three
    // classes (the shared base, $Small and $Large), which is the exact arithmetic the
    // client itself uses to place every part relative to the bogey block's own position,
    // read at the render() call's angle parameter equal to 0 degrees (rest pose):
    //
    //   Small.render():  BOGEY_FRAME:          no translate (scale only)
    //                     SMALL_BOGEY_WHEELS:   translate(0, 0.75, +-1) then rotateX(angle),
    //                                           once per axle
    //   Large.render():  BOGEY_DRIVE:           no translate (scale only)
    //                     BOGEY_DRIVE_BELT:     no translate (scale + UV scroll only - the
    //                                           belt's motion is a texture animation, not
    //                                           a vertex one, so it is geometrically static)
    //                     BOGEY_PISTON:         translate(0, 0, 0.25 * sin(angle)) - a
    //                                           reciprocating slide, not a rotation
    //                     LARGE_BOGEY_WHEELS:   translate(0, 1.0, 0) then rotateX(angle),
    //                                           one axle only
    //                     BOGEY_PIN:            translate(0, 1, 0), rotateX(angle),
    //                                           translate(0, 0.25, 0), rotateX(-angle) - an
    //                                           eccentric crank pin that orbits the wheel
    //                                           centre without spinning itself
    //
    // All of these are in block units, in the model's own un-rotated (axis=z) frame - the
    // same frame every one of these obj models is authored in. If Create ever moves a
    // part, this is the place that has to follow; there is no live value to re-read.
    //
    // LARGE_BOGEY_WHEELS is a rotation (rotateX(angle) alone), so it is a Spin below.
    // BOGEY_PISTON's reciprocation is a slide along local Z (Oscillate) and BOGEY_PIN's
    // orbit-without-spin is a circle traced without turning the geometry itself (Orbit) -
    // core gained both motion kinds alongside Spin, so all three large moving parts now
    // animate. BOGEY_DRIVE_BELT is still attached static: its motion is a texture UV
    // scroll (shiftUVScrolling), not geometry, and stays out of scope the same way this
    // file already accepts that a block-state mesh alone cannot show the plain shaft
    // stubs Create's renderer also draws.
    //
    // All three motions are driven by the one wheel angle, so Oscillate's and Orbit's
    // period has to be derived from the wheel radius rather than guessed. The browser
    // computes a Spin's angle as travel / radius (radians), and that is by definition the
    // same "angle" Create's own render() feeds into the piston's sin(rad(angle)) and the
    // pin's rotateX(angle) - one shared float parameter in StandardBogeyRenderer$Large,
    // confirmed by javap. So setting period = WHEEL_RADIUS_LARGE makes
    // sin(travel / period) and the orbit's travel / period complete exactly one cycle per
    // wheel revolution, matching Create exactly - no separate period constant needed.
    //
    // Units: Create's own PISTON_STROKE and PIN_ORBIT_RADIUS below are read off the
    // decompiled render() in block units (0.25 for both), because that is the unit
    // PoseStack.translate uses. ModelAttachment.Oscillate's amplitude and Orbit's radius
    // are documented in the model's own 0..16 space instead, the same space
    // WHEEL_RADIUS_LARGE is already in - so both get multiplied by 16 below, exactly the
    // inverse of VolumeMesher's nodeFor() dividing a Motion's radius back down by 16 when
    // it bakes the node.
    //
    // Orbit is the subtle one. The browser has no way to know which direction in the
    // rotation plane Create calls "angle zero" - it picks its own reference direction from
    // axis alone (perpendicularBasis in bluemap3d.core.js) - so the orbit's phase, as seen
    // in the browser, does not line up with Create's own rest-pose direction. That is
    // harmless for the animation itself: BlueMap never renders this contraption next to
    // Create's own client-side renderer for a side-by-side comparison, so no observer can
    // see the phase differ, and the cycle length (this file's whole reason for deriving a
    // period at all) is unaffected. It does matter at travel = 0, because the pin's
    // existing static transform below was tuned to reproduce Create's true rest pose
    // exactly, and the browser's orbit offset is not zero at travel = 0 - it starts one
    // full radius off in whatever direction perpendicularBasis picked. Left alone, that
    // would show up as a permanent 0.25 block error on a stationary bogey. PIN_ORBIT_DZ
    // below cancels exactly that, folded into the pin's transform the same way BOGEY_DROP
    // already folds in Create's own origin mismatch, so travel = 0 still reproduces the
    // tuned rest pose and only travel > 0 moves the pin.
    //
    // Do not "fix" the trailing rotateX(-angle) in Create's own pin transform - it belongs
    // there. translate(0,1,0) rotateX(angle) translate(0,0.25,0) rotateX(-angle) rotates
    // the crank arm to swing the pin's centre around a circle, then un-rotates by the same
    // angle so the pin itself never turns, only orbits - which is exactly Orbit's contract
    // and exactly why this is not a Spin.
    //
    // Create's own figures are measured from a different origin than bogeyTransform's
    // block centre, so taken as written they hang the whole bogey in mid air above the
    // block. BOGEY_DROP is the correction, and it is applied to every part together: they
    // are one physical object, and giving them independent offsets once already let the
    // wheels move down while the frame stayed behind. BOGEY_DROP was tuned so a *_HEIGHT
    // constant of 0 reproduces SMALL_BOGEY_WHEELS' own raw translate exactly, which makes
    // every other *_HEIGHT below the raw translate above minus 0.75 - except FRAME_HEIGHT,
    // which needed an extra empirical nudge beyond that arithmetic, because bogey_frame.obj
    // carries its own baked-in vertical offset that no render() transform reveals. The four
    // new large-only constants are first-pass estimates from the arithmetic alone, without
    // that same visual nudge, because their meshes were not test-rendered for this change;
    // see the tuning table in the task report if one of them looks off by a fixed amount.
    private static final float BOGEY_DROP = -0.75f;
    private static final float FRAME_HEIGHT = -0.5f;
    private static final float SMALL_AXLE_HEIGHT = 0f;
    private static final float SMALL_AXLE_SPACING = 1.0f;
    // 1.0 (Large's raw wheel translate) minus 0.75 (Small's, folded into BOGEY_DROP above).
    private static final float LARGE_AXLE_HEIGHT = 0.25f;
    // BOGEY_DRIVE, BOGEY_DRIVE_BELT and BOGEY_PISTON's rest position all read raw
    // translate 0 - 0.75, the same arithmetic as LARGE_AXLE_HEIGHT above, and they share
    // one constant because they also share that raw origin.
    private static final float BOGEY_DRIVE_HEIGHT = -0.75f;
    // BOGEY_PIN's angle-0 rest translate is 1.25 (1 + 0.25) - 0.75.
    private static final float BOGEY_PIN_HEIGHT = 0.5f;

    // Create's own amplitude and orbit radius, both in block units, straight off the
    // decompiled render(): translate(0, 0, 0.25 * sin(rad(angle))) for the piston, and
    // the 0.25 in translate(0, 0.25, 0) between the pin's two rotateX calls. Multiplied
    // by 16 below wherever ModelAttachment wants model-space (0..16) units instead.
    private static final float PISTON_STROKE = 0.25f;
    private static final float PIN_ORBIT_RADIUS = 0.25f;
    // Cancels the orbit's own travel=0 offset (one radius, in whatever direction the
    // browser's axis-derived reference happens to be - see the block comment above) so
    // the pin's baked rest position is unchanged from the static placement this replaces.
    private static final float PIN_ORBIT_DZ = PIN_ORBIT_RADIUS;

    // Spin radius, likewise not readable from the client renderer - but AbstractBogeyBlock
    // itself (a normal, both-sides Block class, not the renderer) exposes
    // getWheelRadius(), and AbstractBogeyBlockEntity.animate() turns the wheel by exactly
    // 360 * distance / (2*pi*getWheelRadius()) degrees, which is the same
    // distance-over-radius relationship core's browser side uses. So rather than measuring
    // bogey_wheel.obj's rim by eye, these are that method's own constants
    // (getWheelRadius() returns radius/16, so radius alone is already in the model's own
    // 0..16 space Spin expects): 6.5 for every bogey but a large one, 12.5 for large.
    private static final float WHEEL_RADIUS_SMALL = 6.5f;
    private static final float WHEEL_RADIUS_LARGE = 12.5f;

    // bogey_wheel.obj is authored with both of an axle's wheels already mirrored across
    // its own local origin, so one attachment is one whole axle: the axle line passes
    // through that origin along the model's own local X, which is what the renderer spins
    // it about (rotateXDegrees).
    private static final Vector3f WHEEL_PIVOT = new Vector3f(0f, 0f, 0f);
    private static final Vector3f WHEEL_AXIS = new Vector3f(1f, 0f, 0f);

    /**
     * Create's own bogey style, and the only one whose parts this addon can place.
     *
     * <p>A style's geometry is not data: it lives in a client-side renderer registered in
     * Java, so there is nothing on a server to read for any style but this one, whose
     * model names are hardcoded above. A bogey of any other style therefore gets no parts
     * at all rather than standard ones, because drawing Create's frame and wheels onto
     * another mod's bogey is worse than drawing nothing: it is confidently wrong, and it
     * hides the fact that the style is unsupported.
     *
     * <p>Supporting another style means adding its model names here, which needs that
     * mod on the classpath or its assets read by hand.
     */
    private static final String STANDARD_BOGEY_STYLE = "create:standard";

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
        List<ModelAttachment> attachments = new ArrayList<>();
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
            // The bogey style is folded in as well as the block state. It lives in the
            // block entity nbt rather than the blockstate, so unlike everything else about
            // an attachment it can change without the state changing - a wrenched bogey
            // keeps its block and swaps its style, and without this the carriage would
            // keep whatever geometry it was first baked with.
            version ^= mix(mix(FNV_OFFSET, pos.asLong()), bogeyStyleOf(entry.getValue()).hashCode());
            addBogeyAttachments(pos, entry.getValue(), attachments);
        }
        version = mix(version, source.size());

        // Create stores block positions already relative to the anchor, which is exactly
        // what BlockVolume.of wants. Nothing to rebase.
        BlockVolume volume = BlockVolume.of(blocks, PIVOT, attachments);
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

    /**
     * The bogey style at this block, or Create's standard style when it says nothing.
     *
     * <p>Create keeps a bogey's style in its block entity rather than its block state, as
     * a resource location under {@code BogeyData/BogeyStyle}, and a contraption carries
     * that nbt along with the block. Reading it is the only way to tell a standard bogey
     * from one belonging to another style or another mod.
     */
    private static String bogeyStyleOf(StructureTemplate.StructureBlockInfo info) {
        CompoundTag nbt = info.nbt();
        if (nbt == null) {
            return STANDARD_BOGEY_STYLE;
        }
        CompoundTag data = nbt.getCompound("BogeyData");
        String style = data.getString("BogeyStyle");
        return style.isEmpty() ? STANDARD_BOGEY_STYLE : style;
    }

    /**
     * Appends a bogey block's parts to {@code out}, or does nothing if {@code state} is not
     * a small or large bogey.
     *
     * <p>A small bogey gets its frame plus two spinning wheel attachments, one per axle. A
     * large bogey never gets a frame - Create's own {@code $Large} renderer does not draw
     * one either - and instead gets the gearbox housing and belt static, a reciprocating
     * piston, one spinning wheel pair, and an orbiting crank pin. See the constants block
     * above for where each of those numbers came from.
     */
    private static void addBogeyAttachments(BlockPos pos, StructureTemplate.StructureBlockInfo info,
                                            List<ModelAttachment> out) {
        BlockState state = info.state();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        boolean small = SMALL_BOGEY.equals(id);
        boolean large = LARGE_BOGEY.equals(id);
        if (!small && !large) {
            return;
        }
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            // Should not happen for either bogey block; guarded rather than trusted so a
            // future Create version that changes this property does not throw mid-collect.
            return;
        }
        Direction.Axis axis = state.getValue(BlockStateProperties.HORIZONTAL_AXIS);

        if (!STANDARD_BOGEY_STYLE.equals(bogeyStyleOf(info))) {
            // Another style, so its frame and wheels are geometry this addon has no way to
            // name. The block's own model still draws; it simply gets nothing added.
            return;
        }

        if (small) {
            out.add(new ModelAttachment(pos, BOGEY_FRAME_MODEL, Map.of(),
                    bogeyTransform(axis, BOGEY_DROP + FRAME_HEIGHT, 0f)));
            for (float axleOffset : new float[]{SMALL_AXLE_SPACING, -SMALL_AXLE_SPACING}) {
                Matrix4f transform = bogeyTransform(axis, BOGEY_DROP + SMALL_AXLE_HEIGHT, axleOffset);
                ModelAttachment.Spin spin =
                        new ModelAttachment.Spin(WHEEL_PIVOT, WHEEL_AXIS, WHEEL_RADIUS_SMALL);
                out.add(new ModelAttachment(pos, SMALL_BOGEY_WHEEL_MODEL, Map.of(), transform, spin));
            }
            return;
        }

        // Large: no frame. The gearbox housing and belt sit static at the same raw origin
        // (see BOGEY_DRIVE_HEIGHT above), the piston reciprocates, the wheel pair spins,
        // and the pin orbits - see the block comment above for how the piston's and pin's
        // periods were derived from the wheel radius, and why the pin's transform below
        // carries PIN_ORBIT_DZ where the piston's does not.
        Matrix4f driveTransform = bogeyTransform(axis, BOGEY_DROP + BOGEY_DRIVE_HEIGHT, 0f);
        out.add(new ModelAttachment(pos, BOGEY_DRIVE_MODEL, Map.of(), driveTransform));
        out.add(new ModelAttachment(pos, BOGEY_DRIVE_BELT_MODEL, Map.of(), driveTransform));

        ModelAttachment.Oscillate pistonMotion =
                new ModelAttachment.Oscillate(new Vector3f(0f, 0f, 1f), PISTON_STROKE * 16f, WHEEL_RADIUS_LARGE);
        out.add(new ModelAttachment(pos, BOGEY_PISTON_MODEL, Map.of(), driveTransform, pistonMotion));

        Matrix4f wheelTransform = bogeyTransform(axis, BOGEY_DROP + LARGE_AXLE_HEIGHT, 0f);
        ModelAttachment.Spin wheelSpin = new ModelAttachment.Spin(WHEEL_PIVOT, WHEEL_AXIS, WHEEL_RADIUS_LARGE);
        out.add(new ModelAttachment(pos, LARGE_BOGEY_WHEEL_MODEL, Map.of(), wheelTransform, wheelSpin));

        // Orbit's own pivot never affects where the browser draws the part - it is a pure
        // translation, no rotation, so the pivot the browser adds cancels exactly against
        // the pivot it subtracts (see bluemap3d.core.js's writeTransform, KIND_ORBIT). The
        // origin is used here for the same reason WHEEL_PIVOT is: it is the model's own
        // natural reference point, and nothing about Orbit's contract asks for another one.
        ModelAttachment.Orbit pinMotion = new ModelAttachment.Orbit(
                new Vector3f(0f, 0f, 0f), WHEEL_AXIS, PIN_ORBIT_RADIUS * 16f, WHEEL_RADIUS_LARGE);
        out.add(new ModelAttachment(pos, BOGEY_PIN_MODEL, Map.of(),
                bogeyTransform(axis, BOGEY_DROP + BOGEY_PIN_HEIGHT, PIN_ORBIT_DZ), pinMotion));
    }

    /**
     * The transform for a bogey attachment: {@code bogey_frame.obj} and
     * {@code bogey_wheel.obj} are both authored block-centre-relative rather than
     * corner-relative like an element model, so every attachment needs the {@code +0.5}
     * shift back onto {@link ModelAttachment}'s corner-relative convention - and one for
     * axis {@code x} additionally needs the same 90 degree turn about the block's centre
     * that {@code BogeyBlockEntityRenderer} gives the whole render for that axis, because
     * both obj models are authored assuming axis {@code z}.
     *
     * @param dy vertical offset from the bogey block's own position, in block units
     * @param dz offset along the model's un-rotated (axis=z) local depth, in block units -
     *           this is what actually separates a small bogey's two axles, since it is
     *           applied before the axis-x turn above would carry it onto world x instead
     */
    private static Matrix4f bogeyTransform(Direction.Axis axis, float dy, float dz) {
        Matrix4f matrix = new Matrix4f().translate(0.5f, 0.5f, 0.5f);
        if (axis == Direction.Axis.X) {
            matrix.rotateY((float) Math.toRadians(90));
        }
        if (dy != 0f || dz != 0f) {
            matrix.translate(0f, dy, dz);
        }
        return matrix;
    }
}
