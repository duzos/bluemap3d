package dev.duzo.bluemap3d.create;

import com.simibubi.create.content.contraptions.bearing.IBearingBlockEntity;
import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.ModelAttachment;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reports every mechanical, windmill and clockwork bearing's rotating top face as a
 * {@link SceneObject}.
 *
 * <h2>Why this is additive, not a replacement</h2>
 * Create's own {@code bearing/block.json} spans y 0..12; the cap is a separate model,
 * {@code bearing/top.json} (or {@code top_wooden.json}), drawn on top by
 * {@code BearingRenderer} and referenced by no blockstate. BlueMap's terrain scan already
 * draws the block model, so this provider only ever adds the cap as an attachment on a
 * zero-block {@link BlockVolume} - see {@link BlockVolume#attachments}. No
 * {@link #hiddenBlocks()} override, and there cannot be a double-draw: nothing here is a
 * block BlueMap's terrain pass would otherwise render.
 *
 * <h2>The driver: an angle delta, not a speed</h2>
 * The obvious source is {@code KineticBlockEntity.getSpeed()}, and it is wrong: a
 * mechanical bearing's {@code tick()} only advances its {@code angle} field while
 * {@code running} is true and its {@code movedContraption} is neither null nor stalled
 * (verified by decompiling {@code MechanicalBearingBlockEntity.tick()}), so a stalled or
 * idle bearing would spin on the map while sitting still in game.
 *
 * <p>{@link IBearingBlockEntity#getInterpolatedAngle(float)} already contains that exact
 * gate: when it is closed, the partial-tick argument is forced to zero on every call
 * regardless of what is passed in, so the render angle collapses to the bearing's resting
 * {@code angle} field. That makes
 * <pre>{@code
 * degreesPerTick = be.getInterpolatedAngle(1f) - be.getInterpolatedAngle(0f);
 * restAngleDegrees = be.getInterpolatedAngle(0f);
 * }</pre>
 * exactly zero, by construction, whenever the bearing is not really turning - no need to
 * reimplement the gate, and no risk of it drifting out of sync with a future Create
 * version that changes it. When the gate is open the delta is the true per-tick advance
 * including the {@code sequencedAngleLimit} clamp, a {@code protected} field with no
 * public getter that this method already applies internally.
 *
 * <p>This is also the one driver for all three bearing types.
 * {@code ClockworkBearingBlockEntity.getInterpolatedAngle} gates on its own {@code hourHand}
 * the identical way, and {@code WindmillBearingBlockEntity} does not override
 * {@code getInterpolatedAngle} at all - it inherits {@code MechanicalBearingBlockEntity}'s.
 * So this provider never needs to know which of the three it is looking at; it only ever
 * calls the public interface.
 *
 * <h2>The transform</h2>
 * {@code bearing/top.json} is authored assuming {@code FACING = UP}. Create's own
 * {@code BearingRenderer.renderSafe} reorients it for the real facing with three chained
 * {@code rotateCentered} calls, in this order: the live spin about
 * {@code Direction.get(POSITIVE, FACING.getAxis())}, then (only if {@code FACING} is
 * horizontal) an alignment about world {@code UP}, then a tilt about world {@code EAST}.
 * Each later call in a {@code PoseStack} composes closer to the vertex, so the spin -
 * called first - ends up applied last, outermost, about the true world axis; the other two
 * calls are the static reorientation that takes the model's authored-for-UP frame to the
 * real facing.
 *
 * <p>{@link #bearingTransform} replicates exactly that call order, which is what lets the
 * rest angle be baked in the same way the spin is: passing a non-null angle inserts the
 * same rotation as the renderer's own first call, in the same place in the chain, about the
 * same axis. Skip that call (the moving case, see below) and what is left is the static
 * reorientation alone; core's {@code VolumeMesher} then carries the model-space axis handed
 * to {@link ModelAttachment.Rate} through that reorientation to land on a world axis. The
 * reorientation maps the model's local up onto {@code FACING} itself, not onto
 * {@code Direction.get(POSITIVE, FACING.getAxis())} the way the live spin is always applied,
 * so the two agree only when {@code FACING} already is the positive direction of its axis
 * (UP, SOUTH, EAST); for the other three (DOWN, NORTH, WEST) {@link #rateOf} flips the
 * model-space axis to compensate - see its own javadoc.
 *
 * <h2>Zero rate means no {@link ModelAttachment.Rate}, not a zero one</h2>
 * {@link ModelAttachment.Rate} rejects a non-positive {@code radiansPerSecond}, and a
 * bearing's per-tick advance is frequently exactly zero. So: a zero (post-quantisation)
 * rate bakes {@code restAngleDegrees} straight into the attachment's transform and carries
 * no {@link ModelAttachment.Motion} at all; a non-zero rate carries a {@link
 * ModelAttachment.Rate} and bakes only the static reorientation, with both the rate's sign
 * and the facing's axis direction folded into the axis - see {@link #rateOf} - since
 * {@code Rate} itself must be positive.
 *
 * <h2>Discovery without force-loading a single chunk</h2>
 * A bearing is a block entity in a world chunk, and {@link SceneObjectProvider}'s own
 * contract forbids scanning every loaded chunk on every publish. Two things make that
 * affordable instead of a correctness problem:
 * <ul>
 *   <li><b>A bearing in an unloaded chunk is not moving.</b> Its block entity does not
 *   tick, so its angle cannot advance. This provider keeps a per-level, per-position cache
 *   of the last known pose and keeps publishing it, frozen, while the chunk is unloaded -
 *   which is exactly what the game would show if you flew there and loaded the chunk
 *   yourself. No pop-in, no pop-out, no re-bake churn from ordinary chunk traffic.</li>
 *   <li><b>Discovery is spread over {@link #SLICE_COUNT} publishes.</b> Each publish scans
 *   {@code loadedChunks / SLICE_COUNT} chunks, round-robin, so a full sweep of every loaded
 *   chunk completes roughly every {@code SLICE_COUNT} publishes rather than every one. A
 *   new bearing is found within a handful of seconds of its chunk loading, which for a
 *   cap nobody is watching arrive is not a latency worth spending more on. Every chunk read
 *   goes through {@link ServerLevel#getChunkSource()}{@code .getChunkNow}, which returns
 *   {@code null} rather than loading anything - never {@code Level#getBlockState} or
 *   {@code Level#getBlockEntity} on a position that might force one in. See
 *   {@code CurvedTrackProvider.collectTrackBlocks}'s javadoc for the same trap in more
 *   detail.</li>
 * </ul>
 * Once a bearing is in the cache, refreshing its pose on every publish (not just on the
 * slice cadence) costs a map lookup plus two virtual calls, so every already-known,
 * currently-loaded bearing stays current every interval regardless of the slice.
 *
 * <h2>Re-baking is throttled, separately from quantisation</h2>
 * A bearing's rate can flap on an overstressed network. Quantising the rate to whole RPM
 * (see {@link #RPM_PER_DEGREE_PER_TICK}) absorbs small jitter, but a genuine RPM change
 * still means a new mesh and a new atlas - one baked, cached blob per state, not a live
 * feed value - so {@link #MIN_REBAKE_INTERVAL_MILLIS} additionally refuses to accept a new
 * baked pose for one bearing more often than that, holding the previous bake until the
 * window has passed. This is independent of {@code publishIntervalTicks}: the live
 * position and rotation of every object are republished every interval regardless, only
 * the baked geometry (and therefore {@link SceneObject#geometryVersion()}) is throttled.
 *
 * <h2>Caps</h2>
 * Each bearing carries exactly one attachment and one motion node, so
 * {@code Config.MAX_SPIN_NODES_PER_OBJECT} (default 32) can never bind for this provider.
 * {@link CreateConfig#MAX_BEARING_CAPS} bounds how many bearings are drawn at once, and
 * {@link CreateConfig#MAX_BEARING_CACHE_ENTRIES} bounds how many poses this provider
 * remembers in total, including ones currently out of any published range. Both are hit
 * once and logged once, same pattern as {@link CurvedTrackProvider}.
 */
public final class BearingProvider implements SceneObjectProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Create");

    /** FNV-1a's 64 bit offset basis. Same mixer and basis the other Create providers use. */
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;

    private static final ResourceLocation TOP_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/bearing/top");
    private static final ResourceLocation TOP_WOODEN_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/bearing/top_wooden");

    /** The cap's spin pivot, in the model's own 0..16 space: dead centre of the block. */
    private static final Vector3f MOTION_PIVOT = new Vector3f(8f, 8f, 8f);

    /**
     * Create's own {@code KineticBlockEntity.convertToAngular}: {@code speed * 360 / 60 / 20}
     * degrees per tick, i.e. {@code 0.3 * speed}. Create's speed unit is RPM, so dividing a
     * degrees-per-tick value by this constant recovers the RPM that produced it.
     */
    private static final float RPM_PER_DEGREE_PER_TICK = 0.3f;

    /**
     * How many publishes a full discovery sweep is spread across. 20 seconds at the
     * default {@code publishIntervalTicks} of 10 - see the class header on why that
     * latency is not worth spending more per-publish cost to shrink.
     */
    private static final int SLICE_COUNT = 40;

    /** Minimum time between accepting a new baked pose for one bearing. See the class header. */
    private static final long MIN_REBAKE_INTERVAL_MILLIS = 5_000L;

    private final ChunkTracker chunks;
    private final Map<ServerLevel, AtomicInteger> sliceIndex = new ConcurrentHashMap<>();
    private final Map<ServerLevel, Map<BlockPos, BearingPose>> poseCache = new ConcurrentHashMap<>();

    /** Logged once, the same pattern {@link CurvedTrackProvider} uses for its own limits. */
    private final AtomicBoolean capLogged = new AtomicBoolean(false);

    public BearingProvider(ChunkTracker chunks) {
        this.chunks = chunks;
    }

    @Override
    public String id() {
        return "create_bearings";
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        if (!CreateConfig.BEARING_CAPS.get()) {
            return List.of();
        }

        Map<BlockPos, BearingPose> cache = poseCache.computeIfAbsent(level, l -> new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<BlockPos, BearingPose> eldest) {
                return size() > CreateConfig.MAX_BEARING_CACHE_ENTRIES.get();
            }
        });

        scanSlice(level, cache);
        refreshLoadedEntries(level, cache);

        if (cache.isEmpty()) {
            return List.of();
        }

        int maxObjects = CreateConfig.MAX_BEARING_CAPS.get();
        if (cache.size() > maxObjects && capLogged.compareAndSet(false, true)) {
            LOGGER.warn("{} bearings known in {}, over the {} object cap; the rest are not drawn. "
                            + "Raise bluemap3d_create.maxBearingCaps if this is expected.",
                    cache.size(), level.dimension().location(), maxObjects);
        }

        ResourceKey<Level> dimension = level.dimension();
        ResourceLocation dimId = dimension.location();
        List<SceneObject> out = new ArrayList<>(Math.min(cache.size(), maxObjects));
        for (Map.Entry<BlockPos, BearingPose> entry : new ArrayList<>(cache.entrySet())) {
            if (out.size() >= maxObjects) {
                break;
            }
            out.add(toSceneObject(dimension, dimId, entry.getKey(), entry.getValue()));
        }
        return out;
    }

    /**
     * Scans {@code 1/SLICE_COUNT} of this level's loaded chunks, round-robin, and records
     * every bearing found. Existing entries can be touched again here too; that is harmless
     * and cheaper to allow than to filter out.
     */
    private void scanSlice(ServerLevel level, Map<BlockPos, BearingPose> cache) {
        List<ChunkPos> loadedChunks = new ArrayList<>(chunks.loadedChunks(level));
        int total = loadedChunks.size();
        if (total == 0) {
            return;
        }
        int start = sliceIndex.computeIfAbsent(level, l -> new AtomicInteger())
                .getAndUpdate(i -> (i + 1) % SLICE_COUNT);
        for (int i = start; i < total; i += SLICE_COUNT) {
            ChunkPos chunkPos = loadedChunks.get(i);
            // getChunkNow: never force a chunk to load just to look in it. See the class
            // header and CurvedTrackProvider.collectTrackBlocks's javadoc on the same trap.
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            if (chunk == null) {
                continue;
            }
            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                if (entry.getValue() instanceof IBearingBlockEntity bearing) {
                    BearingPose pose = cache.computeIfAbsent(entry.getKey().immutable(), p -> new BearingPose());
                    recordPose(pose, bearing, entry.getValue());
                }
            }
        }
    }

    /**
     * Keeps every already-known bearing whose chunk is currently loaded current on every
     * publish, not just on the slice cadence - this is a map lookup plus two virtual calls
     * per bearing, not a chunk scan. A cached entry whose chunk is unloaded is left alone
     * entirely: no read is attempted, and its last known pose keeps publishing as-is. A
     * cached entry whose chunk is loaded but whose block entity is gone is dropped - the
     * bearing was broken while its chunk was loaded, the ordinary case.
     */
    private void refreshLoadedEntries(ServerLevel level, Map<BlockPos, BearingPose> cache) {
        for (BlockPos pos : new ArrayList<>(cache.keySet())) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                continue;
            }
            BlockEntity blockEntity = chunk.getBlockEntity(pos);
            if (!(blockEntity instanceof IBearingBlockEntity bearing)) {
                cache.remove(pos);
                continue;
            }
            recordPose(cache.get(pos), bearing, blockEntity);
        }
    }

    private static void recordPose(BearingPose pose, IBearingBlockEntity bearing, BlockEntity blockEntity) {
        BlockState state = blockEntity.getBlockState();
        pose.facing = state.hasProperty(BlockStateProperties.FACING)
                ? state.getValue(BlockStateProperties.FACING)
                : Direction.UP;
        pose.woodenTop = bearing.isWoodenTop();
        pose.restAngleDegrees = bearing.getInterpolatedAngle(0f);
        pose.degreesPerTick = bearing.getInterpolatedAngle(1f) - pose.restAngleDegrees;
    }

    /**
     * Builds one bearing's object, applying the minimum re-bake interval before touching
     * the geometry: a version change is only accepted here, never in {@link #recordPose}.
     */
    private SceneObject toSceneObject(ResourceKey<Level> dimension, ResourceLocation dimId,
                                      BlockPos pos, BearingPose pose) {
        long rpm = Math.round(pose.degreesPerTick / RPM_PER_DEGREE_PER_TICK);
        boolean stationary = rpm == 0;
        // Rounded to the nearest degree so a settled cap's baked angle cannot wobble on
        // floating-point noise between publishes - see the class header on why the version
        // and the baked transform have to agree on the same bucketed value.
        long restAngleBucket = Math.round((double) pose.restAngleDegrees);

        long freshVersion = FNV_OFFSET;
        freshVersion = mix(freshVersion, pose.facing.ordinal());
        freshVersion = mix(freshVersion, pose.woodenTop ? 1 : 0);
        freshVersion = mix(freshVersion, stationary ? 0 : 1);
        freshVersion = mix(freshVersion, stationary ? restAngleBucket : rpm);

        long now = System.currentTimeMillis();
        if (!pose.everAccepted || (freshVersion != pose.version
                && now - pose.acceptedAtMillis >= MIN_REBAKE_INTERVAL_MILLIS)) {
            pose.version = freshVersion;
            pose.bakedFacing = pose.facing;
            pose.bakedWoodenTop = pose.woodenTop;
            pose.bakedStationary = stationary;
            pose.bakedRestAngleBucket = restAngleBucket;
            pose.bakedRpm = rpm;
            pose.acceptedAtMillis = now;
            pose.everAccepted = true;
        }
        // Otherwise a change is pending but the minimum re-bake interval has not elapsed;
        // keep publishing the previously accepted bake untouched.

        ResourceLocation model = pose.bakedWoodenTop ? TOP_WOODEN_MODEL : TOP_MODEL;
        Float bakedRestAngle = pose.bakedStationary ? (float) pose.bakedRestAngleBucket : null;
        Matrix4f transform = bearingTransform(pose.bakedFacing, bakedRestAngle);
        ModelAttachment.Motion motion = pose.bakedStationary ? null : rateOf(pose.bakedFacing, pose.bakedRpm);
        ModelAttachment attachment = new ModelAttachment(BlockPos.ZERO, model, Map.of(), transform, motion);

        // Expanded by one block in every direction: the 16x16 cap sweeps about 0.21 blocks
        // outside the block cube as it turns, and the browser's bounding sphere would clip
        // it otherwise. See the class header.
        BlockVolume volume = BlockVolume.attachments(
                new BlockPos(-1, -1, -1), new BlockPos(1, 1, 1), new Vec3(0.5, 0.5, 0.5),
                List.of(attachment));

        // Same id scheme CurvedTrackProvider uses and documents: dimension in the id since
        // core tracks objects by provider and id with no regard to level, and "_" never
        // "/" since WebRootPublisher maps "/" to "_" when it turns an id into a filename.
        String objectId = dimId.getNamespace() + "_" + dimId.getPath().replace('/', '_')
                + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
        Vec3 position = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        long finalVersion = pose.version;

        if (CreateConfig.VERBOSE.get()) {
            LOGGER.info("Bearing {} facing {}: {} rpm -> {}", pos, pose.bakedFacing, pose.bakedRpm,
                    pose.bakedStationary ? "stationary at " + pose.bakedRestAngleBucket + " deg" : "spinning");
        }

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
                return new Quaternionf();
            }

            @Override
            public ResourceKey<Level> dimension() {
                return dimension;
            }
        };
    }

    /**
     * Replicates {@code BearingRenderer.renderSafe}'s own reorientation chain (verified by
     * decompiling it) so the cap sits exactly where Create draws it for any facing, plus -
     * only when {@code restAngleDegrees} is not {@code null} - the same rotation the
     * renderer's live spin applies, baked in at the same point in the chain.
     *
     * <p>Call order matters and is deliberately the same as the renderer's: the optional
     * spin first, then (only for a horizontal facing) an alignment about world up, then a
     * tilt about world east. Each successive {@code PoseStack} rotation composes closer to
     * the vertex than the last, so - exactly as in the renderer - the spin ends up applied
     * outermost, about the true world axis, while the other two calls are the static
     * reorientation that takes the model's authored-for-{@code FACING=UP} frame onto the
     * real facing.
     *
     * <p>All three rotations share one centre, the block's own middle, matching
     * {@code rotateCentered}.
     */
    private static Matrix4f bearingTransform(Direction facing, Float restAngleDegrees) {
        Matrix4f transform = new Matrix4f().translate(0.5f, 0.5f, 0.5f);
        if (restAngleDegrees != null) {
            Direction spinAxis = Direction.get(Direction.AxisDirection.POSITIVE, facing.getAxis());
            float spinRadians = AngleHelper.rad(restAngleDegrees);
            transform.rotate(spinRadians, spinAxis.getStepX(), spinAxis.getStepY(), spinAxis.getStepZ());
        }
        if (facing.getAxis().isHorizontal()) {
            float horizontalRadians = AngleHelper.rad(AngleHelper.horizontalAngle(facing.getOpposite()));
            transform.rotateY(horizontalRadians);
        }
        float verticalRadians = AngleHelper.rad(-90.0 - AngleHelper.verticalAngle(facing));
        transform.rotateX(verticalRadians);
        transform.translate(-0.5f, -0.5f, -0.5f);
        return transform;
    }

    /**
     * A {@link ModelAttachment.Rate} for a quantised, non-zero RPM. The sign folds into
     * the axis rather than the rate, since {@link ModelAttachment.Rate} rejects a
     * non-positive {@code radiansPerSecond} - see the class header.
     *
     * <p>The moving case bakes only the static reorientation (see {@link
     * #bearingTransform}), never the spin itself, so core carries this model-space axis
     * through that reorientation to reach the world-space spin axis. That reorientation
     * maps the model's authored-for-UP axis onto {@code facing}, not onto
     * {@code Direction.get(POSITIVE, facing.getAxis())} the way Create's own renderer
     * always spins - the two agree only when {@code facing} is itself the positive
     * direction of its axis (UP, SOUTH, EAST). For the other three (DOWN, NORTH, WEST) the
     * reorientation lands the model axis on the negative of Create's axis, so the model
     * axis is flipped here to compensate, independent of the RPM sign flip above.
     */
    private static ModelAttachment.Rate rateOf(Direction facing, long rpm) {
        float radiansPerSecond = Math.abs(rpm) * (float) (Math.PI / 30.0);
        boolean negativeAxisDirection = facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE;
        boolean flip = (rpm < 0) ^ negativeAxisDirection;
        Vector3f axis = new Vector3f(0f, flip ? -1f : 1f, 0f);
        return new ModelAttachment.Rate(MOTION_PIVOT, axis, radiansPerSecond);
    }

    /** FNV-1a's mixing step. */
    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    /** Drops all cached state. Called when the server stops so levels are not held alive. */
    public void clear() {
        poseCache.clear();
        sliceIndex.clear();
    }

    /**
     * One bearing's live pose (refreshed whenever its chunk is loaded, frozen otherwise)
     * plus the last baked pose actually accepted for geometry, which only ever changes
     * subject to {@link #MIN_REBAKE_INTERVAL_MILLIS}. Mutable and reused in place so the
     * cache's identity and the LRU eviction order stay tied to one object per bearing.
     */
    private static final class BearingPose {
        Direction facing = Direction.UP;
        boolean woodenTop;
        float restAngleDegrees;
        float degreesPerTick;

        boolean everAccepted;
        long version;
        Direction bakedFacing = Direction.UP;
        boolean bakedWoodenTop;
        boolean bakedStationary = true;
        long bakedRestAngleBucket;
        long bakedRpm;
        long acceptedAtMillis;
    }
}
