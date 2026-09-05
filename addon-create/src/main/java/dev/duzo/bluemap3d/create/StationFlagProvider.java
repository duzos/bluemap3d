package dev.duzo.bluemap3d.create;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reports every train station's flag as a {@link SceneObject}, raised or lowered and
 * textured for whichever of three states it is actually in.
 *
 * <h2>Why this is additive, not a replacement</h2>
 * {@code track_station/block.json} is a bare plinth (two elements, y 0..13); the pole
 * and flag are separate models referenced by no blockstate, drawn on top by
 * {@code StationRenderer} and picked up here as an attachment on a zero-block
 * {@link BlockVolume} - see {@link BlockVolume#attachments}. BlueMap's terrain scan
 * already draws the plinth, so there is nothing to hide and nothing that could ever
 * double-draw: no {@link #hiddenBlocks()} override.
 *
 * <h2>The state: two public fields, no nbt</h2>
 * An earlier pass read a {@code TrainPresent} marker out of the block entity's saved
 * nbt. That marker is never written to disk: {@code StationBlockEntity.write} returns
 * immediately when its {@code clientPacket} argument is false (decompiled), and
 * {@code saveAdditional}/{@code saveWithoutMetadata} always pass false. Do not go
 * looking for it again.
 *
 * <p>The real state is two public members of {@link GlobalStation}, reachable through
 * {@link Create#RAILWAYS} with no block entity and no loaded chunk:
 * <ul>
 *   <li>{@link GlobalStation#getPresentTrain()} - null when no train is at the
 *   station. {@code getPresentTrain() != null} is exactly the value
 *   {@code StationBlockEntity.tick()}'s server branch feeds the client {@code flag}
 *   chase target with (decompiled), even though {@code StationRenderer} itself reads
 *   only that already-synced flag value and never calls {@code getPresentTrain()}
 *   directly - the two are provably identical, not merely observed to agree.</li>
 *   <li>{@link GlobalStation#assembling}, a public field mirroring the block's own
 *   {@code ASSEMBLING} property but readable without the chunk being loaded at all.</li>
 * </ul>
 * <pre>{@code
 * boolean present    = station.getPresentTrain() != null;
 * boolean assembling = station.assembling;
 * // assembling && present falls through to ON in game (see StationRenderer.renderSafe,
 * // decompiled): a train arriving mid-assembly does not revert the flag to the
 * // assembly texture.
 * }</pre>
 *
 * <h2>One model, two poses, three textures - not three models</h2>
 * {@code flag_on.json}, {@code flag_off.json} and {@code flag_assemble.json} share the
 * exact same two elements (a pole and a zero-thickness flag plane), differing only in
 * which texture is bound to {@code #1} and, for the assemble variant, that texture's
 * own UV rectangle. Because the UV rectangle differs for the assemble state, this
 * provider swaps the whole model resource location per state - {@link #FLAG_OFF},
 * {@link #FLAG_ON}, {@link #FLAG_ASSEMBLE} - rather than overriding {@code #1} via
 * {@link ModelAttachment#textures()} on one shared model: a texture override changes
 * which image is bound but not the authored UV rectangle, and using {@code flag_off}'s
 * rectangle against the assemble texture would crop the wrong region.
 *
 * <p>The pose - raised or lowered - is baked into {@link ModelAttachment#transform()}
 * rather than published as a live value, because it only has two settled positions.
 * {@code StationRenderer.transformFlag} (decompiled) computes a spring-overshoot term
 * while the flag is mid-transition, but that transition takes about a second and this
 * provider only ever bakes the settled end state, matching the "pose, not animation"
 * conclusion for an object that changes a handful of times an hour.
 *
 * <h2>The transform</h2>
 * Replicated call-for-call from {@code StationRenderer.transformFlag} and the tail of
 * {@code renderFlag} (both decompiled), in the same order so the same JOML
 * post-multiply convention lands on the same result - see {@link BearingProvider}'s
 * class header for why call order matters here the same way:
 * <pre>{@code
 * center()                                             -> translate(0.5, 0.5, 0.5)
 * rotateYDegrees(flagYRot)
 * translate(1/512, 0.59375, flipped ? 0.875-e : 0.125+e)
 * uncenter()                                           -> translate(-0.5, -0.5, -0.5)
 * rotateXDegrees(sign * (value * 90 + 270))            // value settled: 0 (down) or 1 (up)
 * translate(1/32, 0, 0)
 * rotateYDegrees(flipped ? 0 : 180)
 * translate(-1/32, 0, 0)
 * }</pre>
 * {@code sign} is {@code flagFlipped ? 1 : -1}. Raised (present or assembling) means
 * {@code value = 1}, the X rotation collapses to a multiple of 360; lowered means
 * {@code value = 0} and the rotation is {@code ±270}.
 *
 * <h2>The yaw: re-derived from public API, not reflected</h2>
 * {@code StationBlockEntity.resolveFlagAngle()} is public but its two outputs,
 * {@code flagYRot} and {@code flagFlipped}, are package-private fields with no getter.
 * Rather than reflect into them, {@link #resolveYaw} re-derives the same value from
 * entirely public API (decompiled from {@code resolveFlagAngle()}):
 * <pre>{@code
 * BlockPos trackPos = be.edgePoint.getGlobalPosition();
 * BlockState trackState = level.getBlockState(trackPos);   // guarded, see below
 * if (!(trackState.getBlock() instanceof ITrackBlock track)) return null; // no flag
 * Vec3 axis = null;
 * for (Vec3 v : track.getTrackAxes(level, trackPos, trackState))
 *     axis = v.scale(be.edgePoint.getTargetDirection().getStep()); // last one wins
 * Direction d = Direction.getNearest(axis.x, 0, axis.z);
 * int flagYRot = (int) (-d.toYRot() - 90f);
 * boolean flagFlipped = <off from station to track is zero> ? false
 *         : off.dot(atLowerCornerOf(d.getClockWise().getNormal())) > 0;
 * }</pre>
 * A transcription can go quietly stale across a Create update in a way reflection into
 * the same two fields cannot - reflection fails loudly instead - but reflecting into
 * package-private fields on a {@code compileOnly} dependency is its own fragility, and
 * the body above is short enough to keep in sync by inspection.
 *
 * <p><b>{@code renderFlag} returns without drawing anything when
 * {@code resolveFlagAngle()} fails</b> (verified: it is the method's first check). A
 * station whose edge point does not target an {@link ITrackBlock} therefore has no
 * flag at all in game, not a flag at some default yaw - {@link #objects} matches that
 * by skipping the station entirely rather than publishing a guessed orientation.
 *
 * <h2>The one unguarded trap on this path</h2>
 * {@link TrackTargetingBehaviour#getTrackBlockState()} is an unguarded
 * {@code getWorld().getBlockState(getGlobalPosition())} - calling it on an unloaded
 * position synchronously loads, and if necessary generates, the chunk, and never
 * releases it. This provider never calls it. The block state it would have returned is
 * read directly instead, guarded by {@link Level#hasChunkAt} first, exactly like
 * {@code CurvedTrackProvider.collectTrackBlocks} guards the equivalent read.
 *
 * <h2>Discovery: chunk-independent for state, chunk-gated for yaw only</h2>
 * Stations are enumerated from Create's own railway graph -
 * {@link Create#RAILWAYS}{@code .trackNetworks} -> {@link TrackGraph#getPoints} with
 * {@link EdgePointType#STATION} - the same route {@link TrackCurves} already uses for
 * curved track, and like that route it needs no chunk to be loaded: a station's
 * position, present train and assembling state are all graph state. Only the yaw needs
 * a loaded chunk, because it depends on the station's own block entity (for its
 * {@code edgePoint}) and on the target track block's state. {@link #resolveYaw}
 * attempts that read every publish and keeps the result cached per station
 * {@link UUID}; when the read cannot be attempted (either chunk unloaded) it falls
 * back to whatever was last resolved, and when nothing has ever resolved the station
 * is skipped outright - not drawn with a guessed yaw. This mirrors
 * {@link BearingProvider}'s pose cache: current when loaded, frozen when not, correct
 * either way, with no chunk scan on the discovery side at all.
 *
 * <h2>Caps</h2>
 * Each flag carries exactly one attachment, so {@code Config.MAX_SPIN_NODES_PER_OBJECT}
 * never applies here. {@link CreateConfig#MAX_STATION_FLAGS} bounds how many flags are
 * drawn at once, hit once and logged once like every other cap in this addon.
 */
public final class StationFlagProvider implements SceneObjectProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Create");

    /** FNV-1a's 64 bit offset basis. Same mixer and basis the other Create providers use. */
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;

    private static final ResourceLocation FLAG_OFF =
            ResourceLocation.fromNamespaceAndPath("create", "block/track_station/flag_off");
    private static final ResourceLocation FLAG_ON =
            ResourceLocation.fromNamespaceAndPath("create", "block/track_station/flag_on");
    private static final ResourceLocation FLAG_ASSEMBLE =
            ResourceLocation.fromNamespaceAndPath("create", "block/track_station/flag_assemble");

    /** {@code StationRenderer.transformFlag}'s own {@code p}: 1/512, in block units. */
    private static final float FLAG_P = 1f / 512f;

    /** The small in/out step {@code renderFlag} applies around its own yaw flip. */
    private static final float RENDER_FLAG_STEP = 1f / 32f;

    /** Expanded well past the one-block plinth: after {@code flagYRot} the pole and
     * flag reach out in an arbitrary horizontal direction, so this is generous rather
     * than tight - see the class header on the zero-thickness flag plane and the
     * pole/flag's own authored extents. */
    private static final BlockPos BOUNDS_MIN = new BlockPos(-7, -1, -7);
    private static final BlockPos BOUNDS_MAX = new BlockPos(8, 2, 8);

    private final Map<UUID, FlagYaw> yawCache = new ConcurrentHashMap<>();

    /** Logged once, the same pattern every other cap in this addon uses. */
    private final AtomicBoolean capLogged = new AtomicBoolean(false);

    @Override
    public String id() {
        return "create_station_flags";
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        if (!CreateConfig.STATION_FLAGS.get()) {
            return List.of();
        }

        ResourceKey<Level> dimension = level.dimension();
        List<GlobalStation> stations = new ArrayList<>();
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                if (dimension.equals(station.getBlockEntityDimension())) {
                    stations.add(station);
                }
            }
        }
        if (stations.isEmpty()) {
            return List.of();
        }

        int maxObjects = CreateConfig.MAX_STATION_FLAGS.get();
        if (stations.size() > maxObjects && capLogged.compareAndSet(false, true)) {
            LOGGER.warn("{} stations known in {}, over the {} object cap; the rest have no "
                            + "flag drawn. Raise bluemap3d_create.maxStationFlags if this is expected.",
                    stations.size(), dimension.location(), maxObjects);
        }

        ResourceLocation dimId = dimension.location();
        List<SceneObject> out = new ArrayList<>(Math.min(stations.size(), maxObjects));
        for (GlobalStation station : stations) {
            if (out.size() >= maxObjects) {
                break;
            }
            FlagYaw yaw = resolveYaw(level, station);
            if (yaw == null) {
                // Never resolved: this station has never had both its own chunk and its
                // target track's chunk loaded since the server started, or its edge
                // point does not target an ITrackBlock at all. Either way, drawing a
                // guessed yaw would be exactly the wrong-animation failure the spec
                // calls out - skip it, matching renderFlag's own early return.
                continue;
            }
            boolean present = station.getPresentTrain() != null;
            boolean assembling = station.assembling;
            if (CreateConfig.VERBOSE.get()) {
                LOGGER.info("Station {}: present={} assembling={} flagYRot={} flagFlipped={}",
                        station.getBlockEntityPos(), present, assembling, yaw.flagYRot, yaw.flagFlipped);
            }
            out.add(toSceneObject(dimension, dimId, station, yaw, present, assembling));
        }
        return out;
    }

    /**
     * Resolves (or refreshes) one station's flag yaw, and returns the best answer
     * available: freshly read when both the station's chunk and the target track's
     * chunk are loaded right now, otherwise whatever was last resolved, otherwise
     * {@code null} if nothing ever has been.
     *
     * <p>A fresh read is attempted every publish rather than once-and-never-again,
     * which is cheap - a station is already a known position, so this costs a chunk
     * lookup plus (when loaded) one block state read, not a scan - and it means a
     * station whose track is rebuilt while both chunks happen to be loaded picks up
     * the new orientation instead of staying stuck on a stale one. The cache is what
     * survives a chunk unloading afterwards, exactly like {@link BearingProvider}'s
     * pose cache.
     */
    private FlagYaw resolveYaw(ServerLevel level, GlobalStation station) {
        FlagYaw fresh = tryResolveYaw(level, station);
        if (fresh != null) {
            yawCache.put(station.getId(), fresh);
            return fresh;
        }
        return yawCache.get(station.getId());
    }

    /**
     * Attempts the read described in the class header, returning {@code null} whenever
     * it cannot be done without force-loading a chunk, or whenever it succeeds but
     * finds no target track - both cases mean "no new answer", not "no flag forever",
     * since {@link #resolveYaw} falls back to the cache in either case.
     */
    private static FlagYaw tryResolveYaw(ServerLevel level, GlobalStation station) {
        BlockPos stationPos = station.getBlockEntityPos();
        // getChunkNow, never getBlockEntity on a position that might force a chunk to
        // load - see the class header and CurvedTrackProvider.collectTrackBlocks's
        // javadoc for the same trap in more detail.
        LevelChunk stationChunk = level.getChunkSource().getChunkNow(stationPos.getX() >> 4, stationPos.getZ() >> 4);
        if (stationChunk == null) {
            return null;
        }
        if (!(stationChunk.getBlockEntity(stationPos) instanceof StationBlockEntity be)) {
            return null;
        }
        TrackTargetingBehaviour<?> edgePoint = be.edgePoint;
        if (edgePoint == null) {
            return null;
        }

        BlockPos trackPos = edgePoint.getGlobalPosition();
        if (!level.hasChunkAt(trackPos)) {
            // Do not force-load or generate terrain just to read the targeted track.
            // TrackTargetingBehaviour.getTrackBlockState() skips exactly this guard -
            // see the class header - which is why this reads the state directly
            // instead of calling it.
            return null;
        }
        BlockState trackState = level.getBlockState(trackPos);
        if (!(trackState.getBlock() instanceof ITrackBlock track)) {
            // renderFlag draws nothing when resolveFlagAngle() fails for this same
            // reason - see the class header. No flag at all, not a guessed one.
            return null;
        }

        Vec3 axis = null;
        // No break: the last axis wins, matching resolveFlagAngle's own loop exactly.
        for (Vec3 v : track.getTrackAxes(level, trackPos, trackState)) {
            axis = v.scale(edgePoint.getTargetDirection().getStep());
        }
        if (axis == null) {
            return null;
        }

        Direction d = Direction.getNearest(axis.x, 0, axis.z);
        if (!d.getAxis().isHorizontal()) {
            // Direction.getClockWise() throws for a vertical direction. Not reachable
            // for a real track axis in practice, but this is a re-derivation of
            // package-private logic rather than a call to it, so fail safe rather than
            // risk an exception on some future Create track shape.
            return null;
        }
        int flagYRot = (int) (-d.toYRot() - 90f);

        Vec3 off = Vec3.atLowerCornerOf(trackPos.subtract(stationPos)).multiply(1, 0, 1);
        boolean flagFlipped = off.lengthSqr() != 0
                && off.dot(Vec3.atLowerCornerOf(d.getClockWise().getNormal())) > 0;

        return new FlagYaw(flagYRot, flagFlipped);
    }

    private SceneObject toSceneObject(ResourceKey<Level> dimension, ResourceLocation dimId,
                                      GlobalStation station, FlagYaw yaw, boolean present, boolean assembling) {
        // assembling && present falls through to ON in game, not ASSEMBLE - see the
        // class header. Raised whenever the flag is not the OFF texture: present or
        // assembling both chase the flag to 1, only "neither" chases it to 0.
        boolean raised = present || assembling;
        ResourceLocation model = (assembling && !present) ? FLAG_ASSEMBLE : present ? FLAG_ON : FLAG_OFF;

        Matrix4f transform = flagTransform(yaw.flagYRot, yaw.flagFlipped, raised);
        ModelAttachment attachment = new ModelAttachment(BlockPos.ZERO, model, Map.of(), transform);
        BlockVolume volume = BlockVolume.attachments(BOUNDS_MIN, BOUNDS_MAX, new Vec3(0.5, 0.5, 0.5),
                List.of(attachment));

        long version = FNV_OFFSET;
        version = mix(version, model.toString().hashCode());
        version = mix(version, yaw.flagYRot);
        version = mix(version, yaw.flagFlipped ? 1 : 0);
        long finalVersion = version;

        BlockPos pos = station.getBlockEntityPos();
        // Same id scheme CurvedTrackProvider and BearingProvider both use and document:
        // dimension in the id since core tracks objects by provider and id with no
        // regard to level, and "_" throughout, never "/", since WebRootPublisher maps
        // "/" to "_" when it turns an id into a filename.
        String objectId = dimId.getNamespace() + "_" + dimId.getPath().replace('/', '_')
                + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
        Vec3 position = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

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
     * Replicates {@code StationRenderer.transformFlag} and the tail of
     * {@code renderFlag} call-for-call (both decompiled) - see the class header for the
     * call sequence and why {@code raised} collapses the spring-overshoot term to a
     * settled 0 or 1.
     */
    private static Matrix4f flagTransform(int flagYRot, boolean flagFlipped, boolean raised) {
        float value = raised ? 1f : 0f;
        float sign = flagFlipped ? 1f : -1f;
        return new Matrix4f()
                .translate(0.5f, 0.5f, 0.5f)
                .rotateY(AngleHelper.rad(flagYRot))
                .translate(FLAG_P, 0.59375f, flagFlipped ? 0.875f - FLAG_P : 0.125f + FLAG_P)
                .translate(-0.5f, -0.5f, -0.5f)
                .rotateX(AngleHelper.rad(sign * (value * 90f + 270f)))
                .translate(RENDER_FLAG_STEP, 0f, 0f)
                .rotateY(AngleHelper.rad(flagFlipped ? 0f : 180f))
                .translate(-RENDER_FLAG_STEP, 0f, 0f);
    }

    /** FNV-1a's mixing step. */
    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    /** Drops all cached yaws. Called when the server stops so levels are not held alive. */
    public void clear() {
        yawCache.clear();
    }

    /** One station's resolved flag orientation. Immutable: a fresh resolution replaces
     * the cache entry outright rather than mutating this in place. */
    private static final class FlagYaw {
        final int flagYRot;
        final boolean flagFlipped;

        FlagYaw(int flagYRot, boolean flagFlipped) {
            this.flagYRot = flagYRot;
            this.flagFlipped = flagFlipped;
        }
    }
}
