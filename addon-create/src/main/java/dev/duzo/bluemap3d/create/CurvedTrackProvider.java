package dev.duzo.bluemap3d.create;

import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackShape;
import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.ModelAttachment;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Reports curved Create track as {@link SceneObject}s, one per grid cell.
 *
 * <h2>Why this exists</h2>
 * Create maps a curve to {@code minecraft:block/air} and draws the bezier itself at render
 * time from {@link BezierConnection}. There is no block for BlueMap to read, so without this
 * provider a curve is a gap in the rendered network even though the straight track either
 * side of it is fine.
 *
 * <h2>Merged by grid cell, not one object per curve</h2>
 * Every published object costs its own mesh file, atlas image, HTTP fetch and draw call.
 * Track clusters, so publishing one object per curve would multiply that cost for no benefit;
 * see {@link CreateConfig#CURVE_GRID_SIZE}. A curve is assigned to a cell by its first block
 * entity ({@link BezierConnection#bePositions}'s first element) - curves are almost always
 * far smaller than a cell, so this only rarely disagrees with where the curve's geometry
 * actually sits, and never badly.
 *
 * <h2>Where the segment math comes from</h2>
 * {@link BezierConnection#getBakedSegments()} bakes {@code PoseStack.Pose}s using
 * {@code flywheel} and {@code com.mojang.blaze3d}, both client-only - a dedicated server
 * cannot load either class, so that path is off limits entirely, not just discouraged.
 * Iterating the connection is safe, though: {@link BezierConnection#iterator()} hands out a
 * {@link BezierConnection.Segment} per step with a full orientation frame already computed
 * server-side - {@code position}, {@code derivative} (unit tangent), {@code faceNormal} (the
 * track's up vector, banking included) and {@code normal} (already
 * {@code cross(faceNormal, derivative)}, i.e. the sideways rail-gauge direction). That frame
 * is everything this file uses; nothing here reads Create's client renderer, it was only
 * decompiled for reference to find the model offsets baked into the tie and rail meshes
 * (see the constants below) and the 0.965 half-gauge, which is Create's own and not a guess.
 *
 * <p><b>{@code segment.position} is not a world coordinate.</b> {@code javap -c} on
 * {@code BezierConnection$Bezierator} shows every control point it builds is offset by
 * {@code -bePositions.getFirst()} before the bezier math runs, because Create's own renderer
 * translates its pose stack to that block entity before drawing - the segments it hands out
 * are relative to that block, not to the world. {@link #addCurve} adds
 * {@code bePositions.getFirst()} back for exactly this reason. Do not reach for
 * {@link BezierConnection#getKey()} as a shortcut for that offset: {@code javap} shows it
 * returns {@code bePositions.getSecond()} instead, the far end of the connection, because
 * that is what the owning block entity uses to key its own connection map.
 *
 * <p>Two model pieces repeat along the curve: a tie (sleeper) at every step, and a pair of
 * rail segments - one left, one right - bridging each step to the next. The tie and rail
 * models are authored with their own length axis on local Z, height on Y and width on X,
 * which lines up with {@code derivative}, {@code faceNormal} and {@code normal} respectively,
 * so each piece's transform is just: translate to its anchor point, rotate so those three
 * local axes match that frame's three world vectors, then apply the small baked-in offset
 * the model itself needs (see {@link #TIE_OFFSET_X} and {@link #RAIL_OFFSET_X}) and, for
 * rails only, scale the model's native 0.5 block length to the step's actual length.
 *
 * <p>Rail orientation is taken from the frame at the step the rail piece originates from,
 * not from a delta between the two points the way Create's own renderer does it. That is a
 * simplification, not a correction of anything wrong with Create's approach - it just needs
 * far less trigonometry to reproduce and, at Create's own segment density
 * (round(length * 2) steps), the difference is not visible.
 *
 * <h2>Diagonal and ascending track blocks</h2>
 * Curves are not the only Create track Create itself draws from an obj mesh instead of a
 * block model - the diagonal and ascending straight pieces ({@code create:block/track/diag},
 * {@code diag_2}, {@code ascending} and {@code cross_diag}) do too, even though they are
 * ordinary blocks with an ordinary block state, unlike a curve's bezier. So rather than
 * hand-placing more attachments for them, {@link #collectTrackBlocks} finds them as real
 * blocks and folds them straight into the same cell's {@link BlockVolume} - the existing
 * pipeline (blockstate to variant to model, including the variant's own {@code x}/{@code y}
 * rotation) draws them without this file caring how. The orthogonal shapes
 * ({@code x_ortho}, {@code z_ortho}, {@code cross_ortho}) are deliberately excluded: their
 * models are ordinary {@code elements} JSON, BlueMap's regular terrain scan already draws
 * them, and adding them here would draw them a second time.
 *
 * <h2>Known limitations, accepted rather than fixed here</h2>
 * <ul>
 *   <li><b>Always andesite.</b> {@code obj_track.json} hardcodes
 *       {@code create:block/standard_track} as the texture for every track model. The real
 *       material lives on {@link com.simibubi.create.content.trains.track.TrackMaterial},
 *       whose fields are Registrate-typed and out of scope for this pass.</li>
 *   <li><b>Static.</b> These cells never move, so republishing their transform every publish
 *       interval - which core does for every object regardless - is pure overhead. Keeping
 *       the cell count low via {@link CreateConfig#CURVE_GRID_SIZE} and
 *       {@link CreateConfig#MAX_CURVE_OBJECTS} is the mitigation available at this layer;
 *       not resending an unchanging transform is a core concern.</li>
 * </ul>
 */
public final class CurvedTrackProvider implements SceneObjectProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Create");

    /** FNV-1a's 64 bit offset basis. Same mixer and basis {@link ContraptionProvider} uses. */
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;

    private static final ResourceLocation TIE_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/track/tie");
    private static final ResourceLocation RAIL_LEFT_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/track/segment_left");
    private static final ResourceLocation RAIL_RIGHT_MODEL =
            ResourceLocation.fromNamespaceAndPath("create", "block/track/segment_right");

    /**
     * Half the rail gauge, in blocks. Create's own constant - it is exactly what
     * {@code BezierConnection.SegmentAngles} scales {@code segment.normal} by to place each
     * rail relative to the centreline, decompiled from the client jar rather than guessed.
     */
    private static final float GAUGE_HALF_WIDTH = 0.965f;

    /** {@code segment_left.obj} / {@code segment_right.obj}'s own length along local Z. */
    private static final float RAIL_NATIVE_LENGTH = 0.5f;

    /**
     * A small deliberate over-scale on every rail piece's length, so consecutive pieces
     * overlap a hair rather than leaving a seam on curvature that shortens the chord versus
     * the arc. Create's own renderer does the same thing with its own 2.1/2.2 constants
     * (which are {@code RAIL_NATIVE_LENGTH}'s reciprocal times almost exactly this).
     */
    private static final float RAIL_OVERLAP = 1.05f;

    /**
     * {@code tie.obj} is authored off-centre - its local X span is roughly -0.87..1.87, not
     * symmetric about 0 - and this is the correction, decompiled from the same offset
     * Create's own tie placement applies after rotating. Y also carries a small correction
     * for the model's own baked vertical offset; Z needs none.
     */
    private static final float TIE_OFFSET_X = -0.5f;
    private static final float TIE_OFFSET_Y = -0.12890625f;
    private static final float TIE_OFFSET_Z = 0f;

    /** The equivalent small baked-in correction for the rail models. */
    private static final float RAIL_OFFSET_X = 0f;
    private static final float RAIL_OFFSET_Y = -0.12890625f;
    private static final float RAIL_OFFSET_Z = -0.03125f;

    /**
     * How far a tie or rail piece's mesh reaches beyond the anchor point used to place it,
     * padded generously since this only feeds a bounding box and is never used for culling
     * (attachments are drawn unconditionally - see {@link BlockVolume#attachments}).
     */
    private static final double BOUNDS_PAD = 3.0;

    /**
     * The four block model names Create's {@code track.json} blockstate loads with
     * {@code "loader": "neoforge:obj"} rather than plain {@code elements} JSON - see the
     * class header. Named here instead of the {@link TrackShape} values that happen to use
     * them today, so a future Create version that renames a shape but keeps its model is
     * still caught, and a shape that reuses one of these names for a different model is not
     * wrongly swept in.
     */
    /**
     * Every track model Create ships as an OBJ mesh rather than as element geometry.
     *
     * <p>Checked against the jar: {@code x_ortho}, {@code z_ortho}, {@code cross_ortho}
     * and {@code teleport} are element models, which BlueMap renders into its terrain
     * tiles on its own. Everything listed here is an OBJ mesh, which BlueMap cannot read,
     * so those blocks are invisible on the map unless this addon draws them.
     *
     * <p>Do not add the element-modelled shapes to this list. They would then be drawn
     * twice, once by BlueMap's terrain and once by us, which is the exact artefact this
     * project takes trouble to avoid elsewhere.
     */
    private static final Set<String> OBJ_MODEL_NAMES = Set.of(
            "diag", "diag_2", "ascending", "cross_diag",
            "cross_d1_xo", "cross_d1_zo", "cross_d2_xo", "cross_d2_zo");

    /** Every {@link TrackShape} whose model is one of {@link #OBJ_MODEL_NAMES}. */
    private static final Set<TrackShape> OBJ_MODELLED_SHAPES = Arrays.stream(TrackShape.values())
            .filter(shape -> OBJ_MODEL_NAMES.contains(shape.getModel()))
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(TrackShape.class)));

    /** Logged once, the same pattern {@link ContraptionProvider} uses for its own limits. */
    private final AtomicBoolean cellCapLogged = new AtomicBoolean(false);

    @Override
    public String id() {
        return "create_curved_track";
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        if (!CreateConfig.CURVED_TRACK.get()) {
            return List.of();
        }
        List<BezierConnection> curves = TrackCurves.find(level);
        List<TrackEdge> straightEdges = TrackCurves.findStraightEdges(level);
        if (curves.isEmpty() && straightEdges.isEmpty()) {
            return List.of();
        }

        int gridSize = CreateConfig.CURVE_GRID_SIZE.get();
        Map<Long, List<BezierConnection>> curvesByCell = new LinkedHashMap<>();
        for (BezierConnection curve : curves) {
            long key = cellKeyOf(curve, gridSize);
            curvesByCell.computeIfAbsent(key, k -> new ArrayList<>()).add(curve);
        }
        Map<Long, Map<BlockPos, BlockState>> trackBlocksByCell =
                collectTrackBlocks(level, straightEdges, gridSize);
        if (CreateConfig.VERBOSE.get()) {
            diagnoseTrackBlockCoverage(level, trackBlocksByCell);
        }

        // A cell needs an object once for its curves and once for its diagonal track
        // blocks, but a curve rarely lands in the same cell as one of these blocks, so
        // neither map alone is the full set of cells that need drawing - the union is.
        Set<Long> cellKeys = new LinkedHashSet<>(curvesByCell.keySet());
        cellKeys.addAll(trackBlocksByCell.keySet());
        if (cellKeys.isEmpty()) {
            return List.of();
        }

        int maxObjects = CreateConfig.MAX_CURVE_OBJECTS.get();
        if (cellKeys.size() > maxObjects && cellCapLogged.compareAndSet(false, true)) {
            LOGGER.warn("{} track grid cells in {}, over the {} object cap; the rest are not "
                            + "drawn. Raise bluemap3d_create.maxCurveObjects if this is expected.",
                    cellKeys.size(), level.dimension().location(), maxObjects);
        }

        ResourceKey<Level> dimension = level.dimension();
        ResourceLocation dimId = dimension.location();
        List<SceneObject> out = new ArrayList<>(Math.min(cellKeys.size(), maxObjects));
        for (Long key : cellKeys) {
            if (out.size() >= maxObjects) {
                break;
            }
            int cellX = (int) (key >> 32);
            int cellZ = (int) (long) key;
            List<BezierConnection> cellCurves = curvesByCell.getOrDefault(key, List.of());
            Map<BlockPos, BlockState> cellBlocks = trackBlocksByCell.getOrDefault(key, Map.of());
            SceneObject object = toSceneObject(dimension, dimId, gridSize, cellX, cellZ,
                    cellCurves, cellBlocks);
            if (CreateConfig.VERBOSE.get()) {
                LOGGER.info("Cell {},{}: {} curve(s), {} track block(s) -> {}",
                        cellX, cellZ, cellCurves.size(), cellBlocks.size(),
                        object == null ? "nothing published" : "published");
            }
            if (object != null) {
                out.add(object);
            }
        }
        return out;
    }

    /**
     * Which grid cell a curve belongs to, keyed by the connection's first block entity.
     *
     * <p>{@code bePositions.getFirst()} is the real endpoint to key on, now that
     * {@code Couple} is reachable (see {@code build.gradle}'s ponder dependency). Do not
     * key on {@link BezierConnection#getKey()} instead - it looks like the obvious
     * accessor but {@code javap} shows it returns {@code bePositions.getSecond()}, the
     * far end, because that is what the owning block entity uses to key its own
     * connection map. Using it here would file a curve under its wrong end.
     */
    private static long cellKeyOf(BezierConnection curve, int gridSize) {
        return cellKeyOf(curve.bePositions.getFirst(), gridSize);
    }

    /** Which grid cell a world position belongs to. Track blocks are filed by their own
     * position, unlike a curve - see {@link #collectTrackBlocks}. */
    private static long cellKeyOf(BlockPos pos, int gridSize) {
        int cellX = Math.floorDiv(pos.getX(), gridSize);
        int cellZ = Math.floorDiv(pos.getZ(), gridSize);
        return (((long) cellX) << 32) | (cellZ & 0xffffffffL);
    }

    /**
     * Walks every straight edge's two endpoints in one-block steps, keeping the blocks whose
     * shape is one of {@link #OBJ_MODELLED_SHAPES}.
     *
     * <p>This is the bounded alternative to scanning loaded chunks that
     * {@link SceneObjectProvider}'s javadoc calls for: the railway graph already names the
     * two ends of every straight run, so only the blocks on the line between them are ever
     * candidates for a read. Each candidate is still only read if its chunk is already
     * loaded - this runs on the server thread every {@code publishIntervalTicks}, and
     * {@link ServerLevel#getBlockState} on an unloaded position synchronously loads (and,
     * if necessary, generates) the chunk and never releases it. Without the guard, a
     * railway crossing unloaded terrain would force-load a chunk per block of every
     * straight run, repeatedly, for as long as the world runs. Skipping an unloaded
     * position loses nothing worth drawing: diagonal track sitting in a chunk nobody has
     * loaded is not visible on the map either way.
     *
     * <p>Ascending track climbs a block of Y for every block of horizontal travel, so all
     * three axes are interpolated together rather than assuming the endpoints share a Y
     * level the way a flat run would.
     *
     * <p>Blocks are filed under whichever cell actually contains them, not the cell their
     * edge's curve-equivalent would use - an edge can run for a while before it happens to
     * carry a diagonal or ascending shape, so keying on an edge-level anchor the way a curve
     * is keyed on {@code bePositions.getFirst()} could file a block under a cell far from
     * where it is drawn.
     *
     * <p>A sampled point can land exactly on a block corner rather than inside a block: a
     * node's world position is a block corner, not a block centre, so the endpoints (and any
     * step that lands back on an integral X/Z) sit where up to four blocks meet in the
     * horizontal plane. {@link BlockPos#containing} floors, which arbitrarily picks one of
     * those four, and for a diagonal run the floored block is consistently the one with no
     * track in it while the real piece is one of the other three. So every candidate touching
     * the sampled point is tried, not just the floored one - see {@link #candidateColumns}.
     * This is safe by construction: a candidate is only kept if it passes every filter already
     * applied to the floored guess (chunk loaded, is a {@link TrackBlock}, has {@code SHAPE},
     * shape is OBJ-modelled), so widening the search can only add blocks this mod must already
     * draw, never a false positive, and the {@link Map} keyed by {@link BlockPos} means finding
     * the same block from two edges (or two candidates of the same step) cannot double-count.
     *
     * <p>Y is not widened the same way. The doubled-then-halved X/Z encoding is what puts a
     * node on a shared corner between blocks; {@code yOffsetPixels} is a separate, independent
     * sub-block height carried alongside the block-space Y (see the comment below on
     * {@code getLocation()}), not a doubling scheme that can round a node onto the boundary
     * between the block it belongs to and the one below or above it. An ascending run's Y
     * still only ever picks out the one block layer the track piece actually occupies, so
     * there is no vertical corner-ambiguity for {@link BlockPos#containing} to get wrong here.
     */
    private static Map<Long, Map<BlockPos, BlockState>> collectTrackBlocks(
            ServerLevel level, List<TrackEdge> edges, int gridSize) {
        Map<Long, Map<BlockPos, BlockState>> byCell = new LinkedHashMap<>();
        for (TrackEdge edge : edges) {
            // getLocation() rather than the inherited getX/getY/getZ. TrackNodeLocation
            // extends Vec3i but is NOT in block space: its constructor multiplies x and z
            // by two before rounding, so a node can sit on a half block, and it carries
            // sub-block height separately in yOffsetPixels. Reading the raw components as
            // world coordinates walks a line twice as long as the real one, lands on air
            // the whole way, and finds nothing at all.
            Vec3 a = edge.node1.getLocation().getLocation();
            Vec3 b = edge.node2.getLocation().getLocation();
            int steps = (int) Math.ceil(Math.max(Math.abs(b.x - a.x),
                    Math.max(Math.abs(b.y - a.y), Math.abs(b.z - a.z))));
            for (int i = 0; i <= steps; i++) {
                double t = steps == 0 ? 0d : (double) i / steps;
                double x = a.x + (b.x - a.x) * t;
                double y = a.y + (b.y - a.y) * t;
                double z = a.z + (b.z - a.z) * t;
                int floorY = (int) Math.floor(y);
                for (int candidateX : candidateColumns(x)) {
                    for (int candidateZ : candidateColumns(z)) {
                        BlockPos pos = new BlockPos(candidateX, floorY, candidateZ);
                        if (!level.hasChunkAt(pos)) {
                            // Do not force-load or generate terrain just to check for track.
                            // See the loaded-chunk guard note in this method's javadoc.
                            continue;
                        }
                        BlockState state = level.getBlockState(pos);
                        if (!(state.getBlock() instanceof TrackBlock)
                                || !state.hasProperty(TrackBlock.SHAPE)) {
                            continue;
                        }
                        if (!OBJ_MODELLED_SHAPES.contains(state.getValue(TrackBlock.SHAPE))) {
                            continue;
                        }
                        long cellKey = cellKeyOf(pos, gridSize);
                        byCell.computeIfAbsent(cellKey, k -> new HashMap<>())
                                .put(pos.immutable(), state);
                    }
                }
            }
        }
        return byCell;
    }

    /**
     * Every block column (a single X or Z coordinate) that touches a sampled point on that
     * axis. Normally that is just the floored coordinate, the block the point falls inside of.
     * When the coordinate is itself an integer the point sits exactly on the boundary between
     * that block and the one before it, so both are candidates - see the corner note on
     * {@link #collectTrackBlocks}.
     */
    private static int[] candidateColumns(double coordinate) {
        int floor = (int) Math.floor(coordinate);
        if (coordinate == floor) {
            return new int[] {floor, floor - 1};
        }
        return new int[] {floor};
    }

    // ------------------------------------------------------------------------------------
    // TEMPORARY DIAGNOSTIC. Delete diagnoseTrackBlockCoverage, scanLoadedChunk and their one
    // call site in objects() once the missing-track-piece report is closed out. They exist
    // only to measure whether collectTrackBlocks' line-walk misses real track blocks, not to
    // fix anything themselves.
    // ------------------------------------------------------------------------------------

    /**
     * Ground-truth scan of every loaded chunk for OBJ-modelled track blocks, logged against
     * what {@link #collectTrackBlocks} actually found. Gated behind {@link CreateConfig#VERBOSE}
     * like every other diagnostic in this class - costs nothing with it off.
     *
     * <p>This deliberately does not use the block-entity map as a shortcut, even though
     * {@link TrackBlockEntity} exists. {@code javap} on {@code TrackBlock.newBlockEntity} shows
     * it returns {@code null} whenever the block's {@code HAS_BE} property is false, which
     * Create sets on most of the pieces in a connected run so only one of them owns the real
     * block entity. Scanning block entities would therefore silently skip every
     * {@code HAS_BE=false} piece - exactly the kind of gap this diagnostic exists to rule out
     * rather than reproduce - so it reads block states directly instead.
     *
     * <p>Loaded chunks are found via the package-protected {@link ChunkMap#getChunks()}
     * (reached reflectively) rather than probing {@link ServerLevel#hasChunkAt} over some
     * guessed area or scanning a radius around every player: it hands back exactly the chunk
     * holders the server already has live, force-loaded ones included. Each holder is then
     * read with {@link net.minecraft.server.level.GenerationChunkHolder#getChunkIfPresent}
     * (decompiled: it only inspects a future that has already completed, and returns
     * {@code null} rather than starting one) - so nothing here can trigger a load or a
     * generation the way {@link ServerLevel#getChunkState} or {@code getBlockState} on an
     * unloaded position can.
     */
    private static void diagnoseTrackBlockCoverage(
            ServerLevel level, Map<Long, Map<BlockPos, BlockState>> walkResultsByCell) {
        Iterable<ChunkHolder> holders;
        try {
            Method getChunks = ChunkMap.class.getDeclaredMethod("getChunks");
            getChunks.setAccessible(true);
            @SuppressWarnings("unchecked")
            Iterable<ChunkHolder> cast =
                    (Iterable<ChunkHolder>) getChunks.invoke(level.getChunkSource().chunkMap);
            holders = cast;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Track block coverage diagnostic could not enumerate loaded chunks", e);
            return;
        }

        Map<BlockPos, TrackShape> groundTruth = new HashMap<>();
        for (ChunkHolder holder : holders) {
            // ChunkStatus.FULL, not some lower status - a chunk short of FULL has not run
            // block placement yet and reading it would just report false negatives, not a
            // narrower-but-still-correct answer.
            ChunkAccess access = holder.getChunkIfPresent(ChunkStatus.FULL);
            if (access instanceof LevelChunk chunk) {
                scanLoadedChunk(chunk, groundTruth);
            }
        }

        Map<BlockPos, TrackShape> walked = new HashMap<>();
        for (Map<BlockPos, BlockState> cell : walkResultsByCell.values()) {
            for (Map.Entry<BlockPos, BlockState> entry : cell.entrySet()) {
                walked.put(entry.getKey(), entry.getValue().getValue(TrackBlock.SHAPE));
            }
        }

        int missed = 0;
        for (Map.Entry<BlockPos, TrackShape> entry : groundTruth.entrySet()) {
            if (!walked.containsKey(entry.getKey())) {
                missed++;
                if (missed <= 60) {
                    LOGGER.info("MISSED {} shape={}", entry.getKey(), entry.getValue());
                }
            }
        }
        if (missed > 60) {
            LOGGER.info("MISSED ... {} more not printed", missed - 60);
        }

        int extra = 0;
        for (BlockPos pos : walked.keySet()) {
            if (!groundTruth.containsKey(pos)) {
                extra++;
                LOGGER.info("EXTRA {} - the walk found this but the ground-truth scan did not", pos);
            }
        }

        LOGGER.info("Track block coverage: scan found {}, walk found {}, {} missed by the walk, "
                        + "{} found by the walk but not the scan",
                groundTruth.size(), walked.size(), missed, extra);
    }

    /**
     * Scans one already-loaded chunk's block states for OBJ-modelled track blocks.
     *
     * <p>Y range: whatever {@link ChunkAccess#getSections()} actually returns for this chunk,
     * i.e. the level's own build height. That is not a narrowing - no block, track or
     * otherwise, can exist outside it - so it cannot hide a track block; it is called out here
     * only so a future reader does not mistake "iterate the sections the chunk actually has"
     * for an arbitrary cutoff someone picked.
     *
     * <p>{@link LevelChunkSection#maybeHas} runs before the 4096-position inner loop: it is a
     * palette-only check, so a section with no track block at all - the overwhelming majority
     * of every loaded chunk - costs a handful of comparisons instead of 4096 block reads.
     */
    private static void scanLoadedChunk(LevelChunk chunk, Map<BlockPos, TrackShape> out) {
        ChunkPos chunkPos = chunk.getPos();
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            if (!section.maybeHas(state -> state.getBlock() instanceof TrackBlock)) {
                continue;
            }
            int sectionMinY = chunk.getSectionYFromSectionIndex(i) * 16;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!(state.getBlock() instanceof TrackBlock) || !state.hasProperty(TrackBlock.SHAPE)) {
                            continue;
                        }
                        TrackShape shape = state.getValue(TrackBlock.SHAPE);
                        if (!OBJ_MODELLED_SHAPES.contains(shape)) {
                            continue;
                        }
                        BlockPos pos = new BlockPos(
                                chunkPos.getMinBlockX() + x, sectionMinY + y, chunkPos.getMinBlockZ() + z);
                        out.put(pos, shape);
                    }
                }
            }
        }
    }

    /**
     * Builds one cell's object, or {@code null} if somehow nothing was drawable - it never
     * is in practice, since a cell only exists because at least one curve was assigned to it
     * and every curve draws at least one tie.
     */
    private SceneObject toSceneObject(ResourceKey<Level> dimension, ResourceLocation dimId,
                                      int gridSize, int cellX, int cellZ, List<BezierConnection> curves,
                                      Map<BlockPos, BlockState> trackBlocks) {
        Vec3 cellOrigin = new Vec3(cellX * (double) gridSize, 0, cellZ * (double) gridSize);
        List<ModelAttachment> attachments = new ArrayList<>();
        Bounds bounds = new Bounds();
        long version = FNV_OFFSET;

        for (BezierConnection curve : curves) {
            // Segment.position, below, comes out relative to this block entity, not
            // world-absolute - see the class header. Adding it back is what turns the
            // connection's own coordinates into real world coordinates.
            Vec3 curveOrigin = Vec3.atLowerCornerOf(curve.bePositions.getFirst());
            addCurve(curve, curveOrigin, cellOrigin, attachments, bounds);
            // Seeded with a position first, same reasoning as ContraptionProvider's own
            // per-block seeding: two curves of the same shape and length - a mirrored pair
            // of standard bends is the obvious case - write identical nbt, and an
            // unseeded commutative fold would let one curve's hash cancel the other's.
            CompoundTag nbt = curve.write(BlockPos.ZERO);
            long seed = curve.bePositions.getFirst().asLong();
            version ^= mix(mix(FNV_OFFSET, seed), nbt.hashCode());
        }

        // Track blocks are added as real blocks, not attachments - see the class header on
        // why they need none of the tie/rail modelling a curve does. World positions are
        // rebased into the cell's own local space (Y is untouched, since the grid only
        // splits the world on X/Z) the same way ContraptionProvider rebases contraption
        // blocks onto their anchor.
        Map<BlockPos, BlockState> localBlocks = new HashMap<>(trackBlocks.size());
        for (Map.Entry<BlockPos, BlockState> entry : trackBlocks.entrySet()) {
            BlockPos world = entry.getKey();
            BlockState state = entry.getValue();
            BlockPos local = new BlockPos(world.getX() - cellX * gridSize, world.getY(), world.getZ() - cellZ * gridSize);
            localBlocks.put(local, state);
            expandBoundsForBlock(bounds, local);
            // Same commutative-fold seeding as the curve loop above, and for the same
            // reason: two identical track pieces are common (straight runs repeat the same
            // state for blocks at a time), so an unseeded fold would let them cancel out.
            version ^= mix(mix(FNV_OFFSET, world.asLong()), Block.getId(state));
        }
        if (attachments.isEmpty() && localBlocks.isEmpty()) {
            return null;
        }
        version = mix(version, curves.size());
        version = mix(version, localBlocks.size());

        BlockPos min = new BlockPos(
                (int) Math.floor(bounds.minX), (int) Math.floor(bounds.minY), (int) Math.floor(bounds.minZ));
        BlockPos max = new BlockPos(
                (int) Math.ceil(bounds.maxX), (int) Math.ceil(bounds.maxY), (int) Math.ceil(bounds.maxZ));
        // BlockVolume.of derives its own min/max purely from the block map, which would
        // clip the browser's culling box to just the track blocks and miss a curve's tie
        // and rail attachments reaching out past them - so an empty block map keeps using
        // the attachments-only factory (of would silently discard the attachments outright,
        // per its own javadoc, since an empty block map alone means EMPTY), and a non-empty
        // one goes through boundedVolume to keep the bounds this method already computed.
        BlockVolume volume = localBlocks.isEmpty()
                ? BlockVolume.attachments(min, max, Vec3.ZERO, attachments)
                : boundedVolume(min, max, Vec3.ZERO, localBlocks, attachments);

        // The dimension goes in the id for the same reason ContraptionProvider puts it in
        // its own: core keys tracked objects on provider and id together with no regard to
        // level, so two dimensions sharing a cell coordinate - (0,0) exists in every
        // dimension - would otherwise collide. "_" throughout, never "/": WebRootPublisher
        // maps "/" to "_" when it turns an id into a filename, so mixing the two separators
        // would make two different cells collide on disk.
        String objectId = dimId.getNamespace() + "_" + dimId.getPath().replace('/', '_')
                + "_" + cellX + "_" + cellZ;
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
                return cellOrigin;
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
     * Walks one curve's steps, adding a tie at every step and a pair of rail pieces bridging
     * each step to the next.
     *
     * <p>{@link BezierConnection.Segment} is mutated and handed back the same instance on
     * every call to {@code next()} - a field read into a local variable within one loop body
     * is safe, but holding a {@code Segment} across iterations is not, so every field this
     * needs is copied out (or derived) before moving on.
     */
    private static void addCurve(BezierConnection curve, Vec3 curveOrigin, Vec3 cellOrigin,
                                 List<ModelAttachment> attachments, Bounds bounds) {
        Vec3 prevPos = null;
        Vec3 prevRight = null;
        Vec3 prevUp = null;
        Vec3 prevFwd = null;

        Vec3 firstPos = null;
        Vec3 lastPos = null;
        int segmentCount = 0;
        for (BezierConnection.Segment segment : curve) {
            segmentCount++;
            // segment.position is relative to curveOrigin (bePositions.getFirst()), not
            // world-absolute - see the class header - so every use of it below needs
            // curveOrigin added back in before it means anything as a world position.
            Vec3 pos = segment.position.add(curveOrigin);
            Vec3 fwd = segment.derivative.normalize();
            // Already cross(faceNormal, derivative) and already unit length - see the class
            // header - but re-derived rather than trusted outright, since faceNormal is
            // slerped between the two endpoints and is not guaranteed exactly perpendicular
            // to derivative once banking differs at the two ends.
            Vec3 right = segment.normal.normalize();
            Vec3 up = fwd.cross(right).normalize();

            Matrix4f tie = frameMatrix(pos, cellOrigin, right, up, fwd)
                    .translate(TIE_OFFSET_X, TIE_OFFSET_Y, TIE_OFFSET_Z);
            attachments.add(new ModelAttachment(BlockPos.ZERO, TIE_MODEL, Map.of(), tie));
            expandBounds(bounds, pos, cellOrigin);

            if (prevPos != null) {
                Vec3 delta = pos.subtract(prevPos);
                float stepLength = (float) delta.length();
                if (stepLength > 1.0e-5f) {
                    float scaleZ = stepLength / RAIL_NATIVE_LENGTH * RAIL_OVERLAP;
                    Vec3 railLeft = prevPos.add(prevRight.scale(GAUGE_HALF_WIDTH));
                    Vec3 railRight = prevPos.subtract(prevRight.scale(GAUGE_HALF_WIDTH));
                    addRail(RAIL_LEFT_MODEL, railLeft, cellOrigin, prevRight, prevUp, prevFwd,
                            scaleZ, attachments, bounds);
                    addRail(RAIL_RIGHT_MODEL, railRight, cellOrigin, prevRight, prevUp, prevFwd,
                            scaleZ, attachments, bounds);
                }
            }
            if (firstPos == null) {
                firstPos = pos;
            }
            lastPos = pos;
            prevPos = pos;
            prevRight = right;
            prevUp = up;
            prevFwd = fwd;
        }
        if (CreateConfig.VERBOSE.get()) {
            LOGGER.info("  curve {} -> {}: {} segment(s), geometry spans {} .. {}",
                    curve.bePositions.getFirst(), curve.bePositions.getSecond(),
                    segmentCount, firstPos, lastPos);
        }
    }

    /** One rail piece, anchored at {@code anchor} and stretched to cover one step. */
    private static void addRail(ResourceLocation model, Vec3 anchor, Vec3 cellOrigin,
                                Vec3 right, Vec3 up, Vec3 fwd, float scaleZ,
                                List<ModelAttachment> attachments, Bounds bounds) {
        Matrix4f transform = frameMatrix(anchor, cellOrigin, right, up, fwd)
                .translate(RAIL_OFFSET_X, RAIL_OFFSET_Y, RAIL_OFFSET_Z)
                .scale(1f, 1f, scaleZ);
        attachments.add(new ModelAttachment(BlockPos.ZERO, model, Map.of(), transform));
        expandBounds(bounds, anchor, cellOrigin);
    }

    /**
     * Translate to {@code worldPoint} (in the cell's own local coordinates) and rotate so
     * the model's local X, Y and Z axes land on {@code right}, {@code up} and {@code fwd}.
     *
     * <p>JOML's nine-float {@link Matrix3f} constructor reads column by column - same fact
     * {@link ContraptionProvider#rotationOf} relies on - so hands the three basis vectors in
     * as its three columns rather than building a quaternion from angles.
     */
    private static Matrix4f frameMatrix(Vec3 worldPoint, Vec3 cellOrigin, Vec3 right, Vec3 up, Vec3 fwd) {
        float lx = (float) (worldPoint.x - cellOrigin.x);
        float ly = (float) (worldPoint.y - cellOrigin.y);
        float lz = (float) (worldPoint.z - cellOrigin.z);
        Matrix3f rot = new Matrix3f(
                (float) right.x, (float) right.y, (float) right.z,
                (float) up.x, (float) up.y, (float) up.z,
                (float) fwd.x, (float) fwd.y, (float) fwd.z);
        return new Matrix4f()
                .translate(lx, ly, lz)
                .rotate(new Quaternionf().setFromNormalized(rot));
    }

    private static void expandBounds(Bounds bounds, Vec3 worldPoint, Vec3 cellOrigin) {
        double lx = worldPoint.x - cellOrigin.x;
        double ly = worldPoint.y - cellOrigin.y;
        double lz = worldPoint.z - cellOrigin.z;
        bounds.expand(lx - BOUNDS_PAD, ly - BOUNDS_PAD, lz - BOUNDS_PAD);
        bounds.expand(lx + BOUNDS_PAD, ly + BOUNDS_PAD, lz + BOUNDS_PAD);
    }

    /**
     * Expands {@code bounds} to cover one whole block, unlike {@link #expandBounds} above -
     * a track block's extent is exactly known, so it gets no {@link #BOUNDS_PAD}, unlike an
     * attachment's mesh whose true reach past its anchor point is not.
     */
    private static void expandBoundsForBlock(Bounds bounds, BlockPos local) {
        bounds.expand(local.getX(), local.getY(), local.getZ());
        bounds.expand(local.getX() + 1, local.getY() + 1, local.getZ() + 1);
    }

    /**
     * {@link BlockVolume#of} with the given bounds instead of ones derived from the block
     * map alone.
     *
     * <p>{@code of}'s own {@code min()}/{@code max()} only ever look at the block positions
     * it was handed, because a contraption or a ship - the only callers before this one -
     * never carries attachment geometry that reaches further than its blocks do. A cell that
     * mixes track blocks with a curve's tie and rail attachments breaks that assumption, and
     * the browser uses these bounds for culling, so they need to cover both.
     */
    private static BlockVolume boundedVolume(BlockPos min, BlockPos max, Vec3 pivot,
                                             Map<BlockPos, BlockState> blocks,
                                             Collection<ModelAttachment> attachments) {
        BlockVolume base = BlockVolume.of(blocks, pivot, attachments);
        BlockPos lo = min.immutable();
        BlockPos hi = max.immutable();
        return new BlockVolume() {
            @Override public Collection<ModelAttachment> attachments() {
                return base.attachments();
            }
            @Override public BlockPos min() {
                return lo;
            }
            @Override public BlockPos max() {
                return hi;
            }
            @Override public Vec3 pivot() {
                return base.pivot();
            }
            @Override public BlockState stateAt(int x, int y, int z) {
                return base.stateAt(x, y, z);
            }
            @Override public void forEachBlock(BlockConsumer consumer) {
                base.forEachBlock(consumer);
            }
            @Override public int blockCount() {
                return base.blockCount();
            }
        };
    }

    /** FNV-1a's mixing step. */
    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    /** A running bounding box in the cell's local coordinates. */
    private static final class Bounds {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        void expand(double x, double y, double z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
    }
}
