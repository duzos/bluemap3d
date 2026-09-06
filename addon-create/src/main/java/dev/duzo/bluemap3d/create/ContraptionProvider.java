package dev.duzo.bluemap3d.create;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DimensionPalette;
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
import net.minecraft.util.Mth;
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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reports every moving Create contraption as a {@link SceneObject}.
 *
 * <p>Train carriages, minecart contraptions, gantry carriages, piston and pulley
 * assemblies, and rotating bearings - windmills, mechanical and clockwork bearings,
 * elevator pulleys. All of them, from two enumerations.
 *
 * <h2>Why entities and not the railway registry - except for carriages</h2>
 * Create's four contraption entity types all extend
 * {@link AbstractContraptionEntity}: {@code ControlledContraptionEntity} for bearings and
 * pulleys, {@code GantryContraptionEntity}, {@code OrientedContraptionEntity} for minecart
 * contraptions, and {@code CarriageContraptionEntity} - a subclass of that last one - for
 * train carriages. Enumerating the base class gets all of them at once, and that is still
 * what happens here for every kind but the last.
 *
 * <p>A carriage is different: it is a {@code CarriageContraptionEntity}, but Create only
 * keeps that entity alive while the chunk under it is <em>ticking</em>, a narrower and more
 * volatile condition than loaded - see {@link Carriage#manageEntities}. A train's carriages
 * keep travelling with no entity at all whenever that is not true, because
 * {@code Create.RAILWAYS.trains} ticks every carriage's position independent of any entity
 * (see {@link com.simibubi.create.content.trains.GlobalRailwayManager#tickTrains}). Reading
 * only entities therefore made a moving train blink on and off the map at the publish rate
 * every time it crossed that boundary - this is the bug this split fixes.
 *
 * <p>So carriages are walked from the train registry instead, in {@link #trainCarriages},
 * and excluded from the entity sweep below so nothing is ever drawn twice. Everything else
 * - bearings, gantries, pistons, minecart contraptions - stays on the entity path, because
 * none of those have an equivalent registry to read; an entity really is the only place
 * their position, rotation and blocks exist.
 *
 * <p>The obvious alternative for a bearing or a gantry - reading {@code Create.RAILWAYS}
 * for the train list, which is what the prior-art mods do for markers - is still the wrong
 * layer for them: those mods draw a dot on a 2D map, which the registry alone can do; this
 * wants blocks and a pose, which only the entity has for a contraption with no registry.
 * (The railway graph earns its keep elsewhere in this addon too, for curved track discovery
 * - see {@link TrackCurves} and {@link CurvedTrackProvider} - a different problem again with
 * no entity to read a pose from.)
 *
 * <h2>The transform, entity path</h2>
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
 * <h2>The transform, train path</h2>
 * A carriage's position and rotation are entirely derivable from two public fields
 * {@link Carriage.DimensionalCarriageEntity} keeps current every tick regardless of any
 * entity - {@code positionAnchor} and {@code rotationAnchors} - because
 * {@code Carriage.updateContraptionAnchors()} computes both from the carriage's bogeys and
 * their {@code TravellingPoint}s alone, with no entity read anywhere in it. See
 * {@link #trainRotationOf} for exactly how that lines up with {@code applyRotation}.
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

    // Create's own two bogey BLOCKS. These are block ids, not block entity type ids, and
    // the difference has already cost one debugging session: Create registers a single
    // block entity type "create:bogey" shared by both blocks, and Steam 'n' Rails does the
    // same with "railways:bogey" across all twenty-two of its bogey blocks
    // (CRBlockEntities, validBlocks). A contraption's saved block entity tag carries that
    // type id in its "id" field, so an nbt dump of an assembled train reads "create:bogey"
    // or "railways:bogey" and looks like the block id while being nothing of the sort.
    // create:small_bogey and create:large_bogey are the real block ids - confirmed against
    // Create 6.0.10-280's own assets/create/blockstates/{small,large}_bogey.json and its
    // block.create.small_bogey lang key. Do not "correct" these to create:bogey.
    private static final ResourceLocation SMALL_BOGEY =
            ResourceLocation.fromNamespaceAndPath("create", "small_bogey");
    private static final ResourceLocation LARGE_BOGEY =
            ResourceLocation.fromNamespaceAndPath("create", "large_bogey");

    private static final ResourceLocation BOGEY_FRAME_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/track/bogey/bogey_frame");
    static final ResourceLocation SMALL_BOGEY_WHEEL_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/track/bogey/bogey_wheel");
    static final ResourceLocation LARGE_BOGEY_WHEEL_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/track/bogey/bogey_drive_wheel");
    private static final ResourceLocation BOGEY_DRIVE_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/track/bogey/bogey_drive");
    private static final ResourceLocation BOGEY_DRIVE_BELT_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/track/bogey/bogey_drive_belt");
    private static final ResourceLocation BOGEY_PISTON_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/track/bogey/bogey_drive_piston");
    static final ResourceLocation BOGEY_PIN_MODEL =
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
    //                                           centre without spinning itself, the trailing
    //                                           rotateX(-angle) being what cancels the
    //                                           self-rotation and leaves a pure translation
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
    // SuperByteBuffer.translate uses. ModelAttachment.Oscillate's amplitude and Orbit's
    // pivot are documented in the model's own 0..16 space instead, the same space
    // WHEEL_RADIUS_LARGE is already in - so both get multiplied by 16 below, exactly the
    // inverse of VolumeMesher's nodeFor() dividing a Motion's figures back down by 16
    // when it bakes the node.
    //
    // Orbit is the subtle one, and it was got wrong once. The pin's circle is centred on
    // the wheel's own axle, which sits PIN_ORBIT_RADIUS below the pin's rest position -
    // that is what Orbit's pivot has to say, and saying it is not optional. An orbit that
    // states only a radius leaves the browser to guess which way round the axis the part
    // rests, and the only thing it can guess from is the axis; a guess made from the axis
    // is right for one of the four directions a bogey can be laid and wrong for the other
    // three, so the same pin orbited its axle on a bogey laid one way and a point beside
    // the axle on a bogey laid the other. With the pivot given, the browser turns the
    // pivot-to-pin vector itself and the phase matches the wheel's own crank for every
    // orientation, by construction.
    //
    // Do not "fix" the trailing rotateX(-angle) in Create's own pin transform - it belongs
    // there. translate(0,1,0) rotateX(angle) translate(0,0.25,0) rotateX(-angle) rotates
    // the crank arm to swing the pin's centre around a circle, then un-rotates by the same
    // angle so the pin itself never turns, only orbits - which is exactly Orbit's contract
    // and exactly why this is not a Spin. That cancellation is not an artefact of which
    // way the calls compose, either: written order and reversed order give the same pure
    // translation, differing only in which way round the circle it runs, and the wheel's
    // own translate(0,1,0) rotateX(angle) settles that - only the reversed reading spins
    // the wheel in place rather than swinging it around the block origin.
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
    // Package-private rather than private: BogeyStyles reuses BOGEY_DROP, WHEEL_AXIS,
    // WHEEL_RADIUS_SMALL and bogeyTransform for the railways medium family, which shares
    // this same coordinate convention and this same small wheel radius (see that class).
    static final float BOGEY_DROP = -0.75f;
    private static final float FRAME_HEIGHT = -0.5f;
    static final float SMALL_AXLE_HEIGHT = 0f;
    static final float SMALL_AXLE_SPACING = 1.0f;
    // 1.0 (Large's raw wheel translate) minus 0.75 (Small's, folded into BOGEY_DROP above).
    static final float LARGE_AXLE_HEIGHT = 0.25f;
    // BOGEY_DRIVE, BOGEY_DRIVE_BELT and BOGEY_PISTON's rest position all read raw
    // translate 0 - 0.75, the same arithmetic as LARGE_AXLE_HEIGHT above, and they share
    // one constant because they also share that raw origin.
    static final float BOGEY_DRIVE_HEIGHT = -0.75f;
    // BOGEY_PIN's angle-0 rest translate is 1.25 (1 + 0.25) - 0.75.
    static final float BOGEY_PIN_HEIGHT = 0.5f;

    // Create's own amplitude and crank throw, both in block units, straight off the
    // decompiled render(): translate(0, 0, 0.25 * sin(rad(angle))) for the piston, and
    // the 0.25 in translate(0, 0.25, 0) between the pin's two rotateX calls. Multiplied
    // by 16 below wherever ModelAttachment wants model-space (0..16) units instead.
    static final float PISTON_STROKE = 0.25f;
    static final float PIN_ORBIT_RADIUS = 0.25f;

    // Spin radius, likewise not readable from the client renderer - but AbstractBogeyBlock
    // itself (a normal, both-sides Block class, not the renderer) exposes
    // getWheelRadius(), and AbstractBogeyBlockEntity.animate() turns the wheel by exactly
    // 360 * distance / (2*pi*getWheelRadius()) degrees, which is the same
    // distance-over-radius relationship core's browser side uses. So rather than measuring
    // bogey_wheel.obj's rim by eye, these are that method's own constants
    // (getWheelRadius() returns radius/16, so radius alone is already in the model's own
    // 0..16 space Spin expects): 6.5 for every bogey but a large one, 12.5 for large.
    static final float WHEEL_RADIUS_SMALL = 6.5f;
    static final float WHEEL_RADIUS_LARGE = 12.5f;

    // bogey_wheel.obj is authored with both of an axle's wheels already mirrored across
    // its own local origin, so one attachment is one whole axle: the axle line passes
    // through that origin along the model's own local X, which is what the renderer spins
    // it about (rotateXDegrees).
    static final Vector3f WHEEL_PIVOT = new Vector3f(0f, 0f, 0f);
    static final Vector3f WHEEL_AXIS = new Vector3f(1f, 0f, 0f);

    /**
     * Create's own bogey style. Handled inline below rather than through
     * {@link BogeyStyles}, since its model names were already hardcoded here before that
     * table existed and there is nothing to gain by moving them.
     *
     * <p>A style's geometry is not data: it lives in a client-side renderer registered in
     * Java, so there is nothing on a server to read for any style but this one, whose
     * model names are hardcoded above, and whatever {@link BogeyStyles} knows about.
     * Anything neither of those recognises gets no parts at all rather than a guess,
     * because drawing one mod's frame and wheels onto another mod's bogey is worse than
     * drawing nothing: it is confidently wrong, and it hides the fact that the style is
     * unsupported.
     *
     * <p>Supporting another style means adding its model names to {@link BogeyStyles} (or
     * here, for Create's own), which needs that mod's assets read by hand - see that
     * class for why nothing here can be read off the mod itself at runtime.
     */
    private static final String STANDARD_BOGEY_STYLE = "create:standard";

    /**
     * {@code Contraption.updateTags}, reached by reflection once at class load.
     *
     * <p>A contraption keeps a block entity's data in two places, and only one of them can
     * be relied on. {@code Contraption.addBlock} stores the full saved tag on the
     * {@code StructureBlockInfo} <em>and</em> {@code BlockEntity.getUpdateTag()} in this
     * separate map - but a carriage's anchor bogey, the one at local {@code (0,0,0)}, comes
     * out with a null {@code nbt()} and an update tag all the same, confirmed by reading a
     * live carriage's own serialised {@code Blocks.BlockList} over rcon. So reading only
     * {@code nbt()} meant one of every carriage's two bogeys could never report a style at
     * all, and fell back to {@link #STANDARD_BOGEY_STYLE}.
     *
     * <p>Reflection because the field is {@code protected} with no accessor, and neither an
     * access transformer nor a subclass reaches it: Create is a {@code compileOnly}
     * dependency resolved from a published jar, and Java's {@code protected} only grants
     * access through a reference of the accessing subclass's own type. A one-time lookup
     * costs nothing per publish, and a failure degrades to the old behaviour rather than
     * throwing mid-collect.
     */
    private static final Field UPDATE_TAGS_FIELD = updateTagsField();

    /**
     * Entity classes whose rotation could not be sampled, so it is reported once each
     * rather than every interval for as long as the contraption exists.
     */
    private final Set<String> unrotatable = ConcurrentHashMap.newKeySet();

    /**
     * Per-carriage cached geometry, one map per level. Keyed on {@code train.id + carriage
     * index} rather than any entity id - see {@link #trainCarriages} - because that is the
     * only identity that survives the entity going away and coming back, and a server
     * restart besides.
     */
    private final Map<ServerLevel, Map<String, CarriageCache>> carriageCaches = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "create_contraptions";
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        int maxBlocks = Config.MAX_BLOCKS_PER_OBJECT.get();
        List<SceneObject> out = new ArrayList<>();

        // CarriageContraptionEntity is excluded here, not just handled elsewhere: it is a
        // subclass of AbstractContraptionEntity, so without this exclusion a loaded carriage
        // would be reported twice - once here, once by trainCarriages below.
        List<? extends AbstractContraptionEntity> entities = level.getEntities(
                EntityTypeTest.forClass(AbstractContraptionEntity.class),
                e -> !(e instanceof CarriageContraptionEntity));
        for (AbstractContraptionEntity entity : entities) {
            SceneObject object = toSceneObject(level, entity, maxBlocks);
            if (object != null) {
                out.add(object);
            }
        }

        out.addAll(trainCarriages(level, maxBlocks));
        return out;
    }

    /**
     * Snapshots one non-carriage contraption, or {@code null} if there is nothing to draw
     * for it.
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
            // Create discards a contraption entity when it disassembles. Nothing left to read.
            return null;
        }
        Contraption contraption = entity.getContraption();
        if (contraption == null) {
            // The window between the entity existing and its contraption being attached.
            return null;
        }
        CarriageGeometry geometry = buildGeometry(contraption, maxBlocks, entity.blockPosition());
        if (geometry == null) {
            return null;
        }

        // Create stores block positions already relative to the anchor, which is exactly
        // what BlockVolume.of wants. Nothing to rebase.
        Vec3 position = entity.getAnchorVec().add(PIVOT);
        Quaternionf rotation = rotationOf(entity);
        // The dimension goes in the id because a carriage spanning a portal exists as one
        // entity per dimension, and those two entities are restored from a single shared
        // serialised tag - so they carry the same uuid. Core keys objects on provider and
        // id alone, so without this they would collide: one mesh, and the carriage
        // flickering between two dimensions at the publish rate. Namespace included:
        // two mods can both call a dimension "the_nether".
        ResourceLocation dim = level.dimension().location();
        String objectId = dim.getNamespace() + "/" + dim.getPath() + "/" + entity.getUUID();
        ResourceKey<Level> dimension = level.dimension();

        return sceneObjectOf(objectId, geometry.volume(), geometry.version(), position, rotation, dimension);
    }

    /**
     * Walks {@code Create.RAILWAYS.trains} rather than any entity list, so a train carries
     * on being drawn while every one of its carriages is between ticking chunks - see the
     * class header.
     *
     * <p>Position and rotation are read straight off {@link Carriage.DimensionalCarriageEntity}'s
     * public fields, which Create itself keeps current every tick with no entity involved
     * (see {@code Carriage.updateContraptionAnchors}). Only the block geometry needs an
     * entity at all, and even that has a fallback - see {@link #refreshCarriage}.
     */
    private Collection<SceneObject> trainCarriages(ServerLevel level, int maxBlocks) {
        Map<String, CarriageCache> cache = carriageCaches.computeIfAbsent(level, l -> new ConcurrentHashMap<>());
        Set<String> stillPresent = new HashSet<>();
        List<SceneObject> out = new ArrayList<>();

        // Copied rather than iterated live: tickTrains can add or remove trains from this
        // same map, and while that never happens concurrently with a publish (both run on
        // the server thread), a snapshot is one comparison cheaper than reasoning about it.
        for (Train train : new ArrayList<>(Create.RAILWAYS.trains.values())) {
            List<Carriage> carriages = train.carriages;
            for (int index = 0; index < carriages.size(); index++) {
                Carriage carriage = carriages.get(index);
                Carriage.DimensionalCarriageEntity dimensional = carriage.getDimensionalIfPresent(level.dimension());
                if (dimensional == null) {
                    // This carriage has never travelled through this dimension. The normal
                    // case: most carriages only ever exist in one.
                    continue;
                }
                Vec3 positionAnchor = dimensional.positionAnchor;
                if (positionAnchor == null || dimensional.rotationAnchors == null) {
                    // Not yet initialised - the window between a carriage entering a
                    // dimension and its first travel() call computing an anchor.
                    continue;
                }
                Vec3 leading = dimensional.rotationAnchors.getFirst();
                Vec3 trailing = dimensional.rotationAnchors.getSecond();
                if (leading == null || trailing == null) {
                    continue;
                }

                String key = train.id + "#" + index;
                stillPresent.add(key);
                CarriageCache entry = cache.computeIfAbsent(key, k -> new CarriageCache());
                refreshCarriage(level, carriage, dimensional, entry, maxBlocks, train.id, index);
                if (entry.volume == null) {
                    // Never successfully seeded - an oversized contraption, or a carriage
                    // whose entity nbt could not be read. Logged inside refreshCarriage.
                    continue;
                }

                Vec3 position = positionAnchor.add(0, 0.5, 0);
                Quaternionf rotation = trainRotationOf(leading, trailing, entry.initialYawDegrees);
                ResourceLocation dim = level.dimension().location();
                String objectId = dim.getNamespace() + "/" + dim.getPath() + "/" + train.id + "/" + index;
                out.add(sceneObjectOf(objectId, entry.volume, entry.version, position, rotation, level.dimension()));
            }
        }

        // A carriage's cache entry outlives the carriage itself only as long as the train
        // is still reporting it - drop anything that was not touched this pass, whether
        // because its train disbanded or because the train got shorter.
        cache.keySet().retainAll(stillPresent);
        return out;
    }

    /**
     * Brings one carriage's cached geometry up to date, either from its live entity or -
     * only on a cache miss - from the persisted contraption nbt {@link Carriage#write}
     * exposes.
     *
     * <p>A live entity is always trusted over the cache: it is definitionally current, and
     * re-reading it is cheap (a map already held by the contraption). The nbt fallback is
     * the opposite - a deep tag copy plus a full {@link Contraption#fromNBT} - so it runs at
     * most once per carriage per cold start, gated by {@link CarriageCache#seeded}. Blocks
     * cannot go stale while a carriage has no live entity: they are not in world chunks, and
     * nothing but a live {@code CarriageContraptionEntity} can mutate them (a door opened, a
     * bogey wrenched). So once seeded, a cold carriage's cache is simply left alone.
     */
    private void refreshCarriage(ServerLevel level, Carriage carriage, Carriage.DimensionalCarriageEntity dimensional,
                                  CarriageCache entry, int maxBlocks, UUID trainId, int index) {
        CarriageContraptionEntity live = dimensional.entity == null ? null : dimensional.entity.get();
        if (live != null && !live.isRemoved() && live.getContraption() != null) {
            CarriageGeometry geometry = buildGeometry(live.getContraption(), maxBlocks,
                    "train " + trainId + " carriage " + index);
            if (geometry != null) {
                entry.volume = geometry.volume();
                entry.version = geometry.version();
                entry.seeded = true;
            }
            entry.initialYawDegrees = live.getInitialYaw();
            return;
        }

        if (entry.seeded) {
            return;
        }
        // Cold miss: no live entity has ever been read for this carriage this session, and
        // there may never be one - a train parked outside any ticking chunk since the
        // server started. Carriage.write() re-serialises from a live entity when one
        // exists, or hands back whatever was last persisted otherwise; either way the
        // "Entity" tag it produces is the same one Create's own respawn path reads.
        entry.seeded = true;
        CompoundTag entityTag = carriage.write(new DimensionPalette(), level.registryAccess()).getCompound("Entity");
        if (entityTag.isEmpty()) {
            return;
        }
        CompoundTag contraptionTag = entityTag.getCompound("Contraption");
        Contraption contraption = Contraption.fromNBT(level, contraptionTag, false);
        CarriageGeometry geometry = buildGeometry(contraption, maxBlocks,
                "train " + trainId + " carriage " + index);
        if (geometry != null) {
            entry.volume = geometry.volume();
            entry.version = geometry.version();
        }

        // "InitialOrientation" is the one field of an OrientedContraptionEntity's pose that
        // getInitialYaw() cannot recompute without an entity - it is synched data, not
        // derived from anything else. It is however set once at assembly and never again,
        // so reading it cold, here, is exactly as accurate as reading it live. Falls back to
        // SOUTH, the same default OrientedContraptionEntity.getInitialYaw() itself uses when
        // there is no value at all.
        Direction initialOrientation = Direction.SOUTH;
        String orientationName = entityTag.getString("InitialOrientation");
        if (!orientationName.isEmpty()) {
            try {
                initialOrientation = Direction.valueOf(orientationName);
            } catch (IllegalArgumentException ignored) {
                // Unrecognised value; keep the SOUTH default.
            }
        }
        entry.initialYawDegrees = initialOrientation.toYRot();
    }

    /** Builds the {@link SceneObject} both contraption paths return, differing only in id. */
    private static SceneObject sceneObjectOf(String objectId, BlockVolume volume, long version,
                                              Vec3 position, Quaternionf rotation, ResourceKey<Level> dimension) {
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
                return version;
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
     * Builds one contraption's block volume, version and bogey attachments, or returns
     * {@code null} if there is nothing to draw or too much of it.
     *
     * <p>Takes the contraption rather than just its block map because a bogey's style is not
     * in the block map at all for every bogey - see {@link #UPDATE_TAGS_FIELD}.
     *
     * <p>Shared by the live entity path and both branches of the train path, so a carriage
     * meshes identically regardless of which one supplied its blocks. Both paths reach the
     * same two nbt sources: {@code Contraption.readNBT} refills {@code updateTags} from the
     * persisted {@code UpdateTag} exactly as assembly filled it from the live block entity.
     */
    private static CarriageGeometry buildGeometry(Contraption contraption, int maxBlocks, Object logLabel) {
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
            // publish interval for a live contraption, so a warn would be the same spam
            // the guard exists to stop, and the obvious fix - remembering which ones were
            // reported - is a set that grows for the life of the server. A cold-start
            // carriage only ever reaches here once regardless, since its cache is not
            // retried after the first attempt - see CarriageCache.seeded.
            LOGGER.debug("Skipping a {} block contraption at {}, over the {} block limit",
                    source.size(), logLabel, maxBlocks);
            return null;
        }

        Map<BlockPos, BlockState> blocks = new HashMap<>(source.size());
        List<ModelAttachment> attachments = new ArrayList<>();
        Map<BlockPos, CompoundTag> updateTags = updateTagsOf(contraption);
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
            String style = bogeyStyleOf(updateTags.get(pos), entry.getValue());
            version ^= mix(mix(FNV_OFFSET, pos.asLong()), style.hashCode());
            addBogeyAttachments(pos, entry.getValue(), style, attachments);
        }
        version = mix(version, source.size());

        BlockVolume volume = BlockVolume.of(blocks, PIVOT, attachments);
        return new CarriageGeometry(volume, version);
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

    /**
     * The train-path equivalent of {@link #rotationOf}: a carriage's rotation, rebuilt from
     * {@code rotationAnchors} with no entity read at all.
     *
     * <p>{@code Carriage$DimensionalCarriageEntity.alignEntity} sets the entity's own
     * {@code yaw}/{@code pitch} fields from the two rotation anchors:
     * <pre>{@code
     * yaw   = atan2(dz, dx) * 180 / pi + 180
     * pitch = atan2(dy, sqrt(dx*dx + dz*dz)) * 180 / pi * -1
     * }</pre>
     * with {@code dx/dy/dz = leading - trailing}, using {@code Mth.atan2} for yaw and plain
     * {@code Math.atan2} for pitch - matched here term for term so the two never drift apart.
     *
     * <p>{@code OrientedContraptionEntity.applyRotation} then composes, in this order
     * (confirmed by decompiling it with {@code javap -c}):
     * <pre>{@code
     * applyRotation(v) = rotate(v, initialYaw, Y)      // innermost, applied first
     *                    -> rotate(_, getViewXRot(1), Z)
     *                    -> rotate(_, getViewYRot(1), Y) // outermost, applied last
     * }</pre>
     * {@code getViewXRot(1)} returns the {@code pitch} field unchanged, but
     * {@code getViewYRot(1)} returns {@code -yaw} - it negates in both of its branches,
     * verified the same way. So the true composition is
     * {@code Ry(-yaw) . Rz(pitch) . Ry(initialYaw)}, not {@code Ry(yaw) . Rz(pitch) . Ry(initialYaw)}
     * - the sign on the outer yaw term is the one thing that was not obvious from Create's
     * own field names, and this was cross-checked against {@link #rotationOf}'s basis
     * sampling on a live carriage before shipping, per the task's own instruction to verify
     * rather than assume it.
     *
     * <p>JOML's {@code rotateY}/{@code rotateZ} each append their rotation as applied first
     * (innermost) relative to whatever the quaternion already held, which is the reverse of
     * the order the rotations are wanted in - so they are called here in the reverse of
     * {@code applyRotation}'s own listed order: outermost first, innermost last. {@code
     * VecHelper.rotate}'s own matrix for a given axis (decompiled the same way) is the
     * standard right-handed rotation matrix, the same convention JOML's {@code rotateX/Y/Z}
     * use, so no extra sign flip is needed to line the two up.
     */
    private static Quaternionf trainRotationOf(Vec3 leading, Vec3 trailing, float initialYawDegrees) {
        double dx = leading.x - trailing.x;
        double dy = leading.y - trailing.y;
        double dz = leading.z - trailing.z;
        float yawDegrees = (float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) + 180f;
        float pitchDegrees = (float) (Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI) * -1f;

        return new Quaternionf()
                .rotateY((float) Math.toRadians(-yawDegrees))
                .rotateZ((float) Math.toRadians(pitchDegrees))
                .rotateY((float) Math.toRadians(initialYawDegrees));
    }

    /** FNV-1a's mixing step. */
    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    /**
     * The bogey style at this block, or Create's standard style when neither source says.
     *
     * <p>Create keeps a bogey's style in its block entity rather than its block state, as
     * a resource location under {@code BogeyData/BogeyStyle}, and a contraption carries
     * that data along with the block in two separate places - see {@link #UPDATE_TAGS_FIELD}
     * for why the update tag is asked first and the block info's own nbt only second. Both
     * are snapshots taken at assembly from the same block entity, so on a contraption whose
     * nbt has not been hand-edited they agree; where they cannot agree is that the anchor
     * bogey has no block info nbt at all.
     */
    private static String bogeyStyleOf(CompoundTag updateTag, StructureTemplate.StructureBlockInfo info) {
        String style = bogeyStyleIn(updateTag);
        if (style == null) {
            style = bogeyStyleIn(info.nbt());
        }
        return style == null ? STANDARD_BOGEY_STYLE : style;
    }

    /** The style named by one block entity tag, or {@code null} if it names none. */
    private static String bogeyStyleIn(CompoundTag nbt) {
        if (nbt == null) {
            return null;
        }
        String style = nbt.getCompound("BogeyData").getString("BogeyStyle");
        return style.isEmpty() ? null : style;
    }

    /** Looks up {@link #UPDATE_TAGS_FIELD} once, or {@code null} if Create has moved it. */
    private static Field updateTagsField() {
        try {
            Field field = Contraption.class.getDeclaredField("updateTags");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not reach Contraption.updateTags; a carriage's anchor bogey will "
                    + "draw as {} whatever style it really carries. {}", STANDARD_BOGEY_STYLE, e.toString());
            return null;
        }
    }

    /** One contraption's block entity update tags, or an empty map if they cannot be read. */
    @SuppressWarnings("unchecked")
    private static Map<BlockPos, CompoundTag> updateTagsOf(Contraption contraption) {
        if (UPDATE_TAGS_FIELD == null) {
            return Map.of();
        }
        try {
            Map<BlockPos, CompoundTag> tags =
                    (Map<BlockPos, CompoundTag>) UPDATE_TAGS_FIELD.get(contraption);
            return tags == null ? Map.of() : tags;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Reported once at lookup time already; a per-publish warn here would spam.
            return Map.of();
        }
    }

    /**
     * Appends a bogey block's parts to {@code out}, or does nothing if {@code state} is not
     * a bogey block this addon recognises.
     *
     * <p>Create's two bogey blocks only ever carry {@link #STANDARD_BOGEY_STYLE} in
     * practice, so their geometry stays hardcoded right here exactly as before: a small
     * bogey gets its frame plus two spinning wheel attachments, one per axle; a large
     * bogey never gets a frame - Create's own {@code $Large} renderer does not draw one
     * either - and instead gets the gearbox housing and belt static, a reciprocating
     * piston, one spinning wheel pair, and an orbiting crank pin. See the constants block
     * above for where each of those numbers came from.
     *
     * <p>Every other style is handed to {@link BogeyStyles} keyed on its style id, which
     * returns no parts at all for a style it does not know either.
     *
     * <h2>Why there is no list of addon bogey block ids here</h2>
     * There used to be one - the five Steam 'n' Rails block sets the medium, single-axle,
     * double-axle, triple-axle and large-Create-styled families sit on - as a gate to stop
     * an unrecognised style falling through to Create's hardcoded branches below. It has
     * been removed, because the style id already carries that guarantee and the list did
     * not: it had to be kept in step by hand with {@code CRBogeyStyles}' own size-to-block
     * registration, which this addon cannot read at runtime, so every new style family (and
     * every mod that is not Steam 'n' Rails) silently drew nothing until somebody
     * remembered to widen it.
     *
     * <p>The invariant that actually matters is the one the ordering below enforces:
     * Create's hardcoded geometry is reachable <em>only</em> when the style really is
     * {@link #STANDARD_BOGEY_STYLE} and the block really is one of Create's own two, so a
     * style that cannot be read - which degrades to {@code create:standard} - can never
     * draw Create's gearbox and drive wheels onto somebody else's bogey. Everything else
     * dispatches on the style id alone, and an id {@link BogeyStyles} does not know draws
     * nothing rather than a guess.
     */
    private static void addBogeyAttachments(BlockPos pos, StructureTemplate.StructureBlockInfo info,
                                            String style, List<ModelAttachment> out) {
        BlockState state = info.state();
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            // Every bogey block has this property - Create's AbstractBogeyBlock declares it
            // and Steam 'n' Rails inherits it. Guarded rather than trusted, both so a future
            // version that changes it does not throw mid-collect and because this method now
            // sees every block in the contraption, not just a shortlist of bogeys.
            return;
        }
        Direction.Axis axis = state.getValue(BlockStateProperties.HORIZONTAL_AXIS);

        if (!STANDARD_BOGEY_STYLE.equals(style)) {
            // Somebody else's style. The block's own model still draws either way; this only
            // ever adds to it, never replaces it.
            out.addAll(BogeyStyles.attachmentsFor(style, pos, axis));
            return;
        }

        // create:standard, so Create's own geometry - but only on Create's own blocks. Any
        // other block reaching here is either an addon bogey whose style tag could not be
        // read, or an ordinary block that happens to have a horizontal axis; both draw
        // nothing, which is the right answer for both.
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        boolean small = SMALL_BOGEY.equals(id);
        if (!small && !LARGE_BOGEY.equals(id)) {
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
        // periods were derived from the wheel radius.
        Matrix4f driveTransform = bogeyTransform(axis, BOGEY_DROP + BOGEY_DRIVE_HEIGHT, 0f);
        out.add(new ModelAttachment(pos, BOGEY_DRIVE_MODEL, Map.of(), driveTransform));
        out.add(new ModelAttachment(pos, BOGEY_DRIVE_BELT_MODEL, Map.of(), driveTransform));

        ModelAttachment.Oscillate pistonMotion =
                new ModelAttachment.Oscillate(new Vector3f(0f, 0f, 1f), PISTON_STROKE * 16f, WHEEL_RADIUS_LARGE);
        out.add(new ModelAttachment(pos, BOGEY_PISTON_MODEL, Map.of(), driveTransform, pistonMotion));

        Matrix4f wheelTransform = bogeyTransform(axis, BOGEY_DROP + LARGE_AXLE_HEIGHT, 0f);
        ModelAttachment.Spin wheelSpin = new ModelAttachment.Spin(WHEEL_PIVOT, WHEEL_AXIS, WHEEL_RADIUS_LARGE);
        out.add(new ModelAttachment(pos, LARGE_BOGEY_WHEEL_MODEL, Map.of(), wheelTransform, wheelSpin));

        // The pin orbits the wheel's axle, and the pin model's own origin is the pin, so
        // the axle is PIN_ORBIT_RADIUS straight down from it - Create's own
        // translate(0, 0.25, 0) between the two rotateX calls, read backwards. That single
        // vector is the whole orbit: its length is the crank throw and its direction is
        // where the pin rests at angle zero, which is what keeps the pin in the wheel's
        // crank hole whichever way the bogey is laid.
        ModelAttachment.Orbit pinMotion = new ModelAttachment.Orbit(
                new Vector3f(0f, -PIN_ORBIT_RADIUS * 16f, 0f), WHEEL_AXIS, WHEEL_RADIUS_LARGE);
        out.add(new ModelAttachment(pos, BOGEY_PIN_MODEL, Map.of(),
                bogeyTransform(axis, BOGEY_DROP + BOGEY_PIN_HEIGHT, 0f), pinMotion));
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
    static Matrix4f bogeyTransform(Direction.Axis axis, float dy, float dz) {
        Matrix4f matrix = new Matrix4f().translate(0.5f, 0.5f, 0.5f);
        if (axis == Direction.Axis.X) {
            matrix.rotateY((float) Math.toRadians(90));
        }
        if (dy != 0f || dz != 0f) {
            matrix.translate(0f, dy, dz);
        }
        return matrix;
    }

    /** Drops all cached carriage geometry. Called when the server stops so levels are not held alive. */
    public void clear() {
        carriageCaches.clear();
    }

    /** One carriage's baked geometry, in the {@link #carriageCaches} map. Mutable and reused in place. */
    private static final class CarriageCache {
        boolean seeded;
        BlockVolume volume;
        long version;
        float initialYawDegrees;
    }

    /** A built volume plus the version it was built at. */
    private record CarriageGeometry(BlockVolume volume, long version) {
    }
}
