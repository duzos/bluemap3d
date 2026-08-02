package dev.duzo.bluemap3d.sable;

import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.BlueMap3D;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.util.LevelAccelerator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reports every loaded Sable ship as a {@link SceneObject}.
 *
 * <p>Sable keeps a ship's blocks in a <em>sub-level</em>: a plot of real chunks parked far
 * out in the level, which the ship's {@linkplain Pose3dc pose} then places and turns
 * somewhere else entirely. BlueMap renders the chunks it can see, so today a ship is
 * either invisible or - if you know where to look - a copy of itself lying flat in the
 * middle of nowhere. That is the whole reason this addon exists.
 *
 * <h2>The one coordinate system that matters</h2>
 * Everything below is in <em>plot space</em>: the level's own coordinates, at wherever the
 * ship's plot happens to sit. {@link ServerLevelPlot#getBoundingBox()} is in it,
 * {@link Pose3dc#rotationPoint()} is in it, and so is anything read out of the level at
 * those positions. Sable maps it to the world as
 *
 * <pre>world = position + orientation * (local - rotationPoint)</pre>
 *
 * which is exactly the transform core applies, given a volume whose pivot is the rotation
 * point. So the pivot is handed straight to {@link BlockVolume#region}, and
 * {@link SceneObject#position()} is then simply {@code pose.position()} - the world
 * position of the pivot, by definition of that formula.
 *
 * <p>The rotation point is the ship's centre of mass; Sable writes it there whenever the
 * mass data is rebuilt. Taking it as the pivot is not just convenient, it is necessary:
 * the physics turns the hull about its centre of mass, so any other pivot would make a
 * turning ship swing.
 *
 * <h2>Why the container and not the tracking system</h2>
 * {@code SubLevelTrackingSystem} tracks which players are watching which ship, which is a
 * different question from which ships exist. The registry is
 * {@link SubLevelContainer#getContainer(ServerLevel)}, in Sable's {@code api} package,
 * and its {@code getAllSubLevels()} is the live list the tracking and physics systems are
 * themselves driven from.
 *
 * <h2>What is deliberately absent</h2>
 * No {@code hiddenBlocks()}. Turtles need it because their blocks really are in world
 * chunks and BlueMap bakes them into tiles; a ship's blocks are in a plot BlueMap never
 * renders, so there is nothing to hide.
 */
public final class ShipProvider implements SceneObjectProvider {

    @Override
    public String id() {
        return "sable_ships";
    }

    /**
     * The ground a ship covered when it was last seen, so a ship appearing or vanishing
     * can be spotted and the terrain it invalidated re-rendered.
     *
     * <p>Keyed by dimension and ship id: a provider is asked about one level at a time,
     * and two levels' ships must not be mistaken for each other.
     */
    private final Map<String, Footprint> lastSeen = new ConcurrentHashMap<>();

    /** A ship's world-space footprint, in blocks. */
    private record Footprint(int minX, int minZ, int maxX, int maxZ, int y) {
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            // No container on this level: Sable allocates one per level, and a level it
            // has not initialised yet simply has no ships.
            return List.of();
        }

        String dimension = level.dimension().location().toString();
        Set<String> present = new HashSet<>();
        List<SceneObject> out = new ArrayList<>();
        for (ServerSubLevel ship : container.getAllSubLevels()) {
            SceneObject object = toSceneObject(level, ship);
            if (object == null) {
                continue;
            }
            out.add(object);
            String key = dimension + "/" + object.id();
            present.add(key);
            if (lastSeen.put(key, footprintOf(ship)) == null) {
                // Newly assembled, or a ship this server has only just loaded. Its blocks
                // are not in the world any more, whatever the terrain tile still says.
                refreshFootprint(level, lastSeen.get(key));
            }
        }
        sweepDeparted(level, dimension, present);
        return out;
    }

    /** The ground the ship covers now, from the world-space bounds Sable keeps for it. */
    private static Footprint footprintOf(ServerSubLevel ship) {
        var box = ship.boundingBox();
        return new Footprint(
                (int) Math.floor(box.minX()), (int) Math.floor(box.minZ()),
                (int) Math.ceil(box.maxX()), (int) Math.ceil(box.maxZ()),
                (int) Math.floor(box.minY()));
    }

    /** Forgets ships that are no longer reported, re-rendering the ground they left. */
    private void sweepDeparted(ServerLevel level, String dimension, Set<String> present) {
        String prefix = dimension + "/";
        for (Map.Entry<String, Footprint> entry : Set.copyOf(lastSeen.entrySet())) {
            String key = entry.getKey();
            if (!key.startsWith(prefix) || present.contains(key)) {
                continue;
            }
            // Gone: shattered back into the world, unloaded, or removed. Its blocks may
            // be in the world now, so the ground it was over is stale again.
            lastSeen.remove(key);
            refreshFootprint(level, entry.getValue());
        }
    }

    /**
     * Re-renders the terrain under a ship that has just appeared or just gone.
     *
     * <p>Assembling a ship takes its blocks out of the world; disassembling it puts them
     * back. Either way BlueMap has a tile baked from the other state, and the result is a
     * ghost ship lying on the grass beside the live one - which looks the more convincing
     * of the two, because BlueMap's terrain shader lit it.
     *
     * <p>The whole footprint, not just the centre: a hull is longer than a tile, and
     * refreshing one point leaves the bow and stern behind as a half-ship. Core collapses
     * the positions into tile coordinates, so the cost is the tiles touched rather than
     * the samples taken - but the samples are capped anyway, because a ship large enough
     * to need more than {@value #MAX_REFRESH_SAMPLES} is better served by BlueMap
     * re-rendering in its own time than by filling its queue.
     *
     * <p>Only on appearance and disappearance, never on movement. A sailing ship changes
     * nothing in the world, and queueing the ground under it every interval would keep
     * BlueMap busy re-rendering grass for as long as the ship is under way.
     *
     * <p><b>This only fixes half of it on Sable 2.0.3.</b> Shattering a ship writes its
     * blocks back through the ordinary path, the chunk is saved, and the re-render picks
     * them up - measured. Assembling one does not: the live level reports air afterwards
     * ({@code /fill ... replace} finds nothing), but the chunk is never marked unsaved,
     * so what BlueMap reads still has the ship in it and re-rendering bakes it straight
     * back. Wiping the whole map and rendering it from scratch reproduces the ghost, so
     * it is the source data and not a stale tile. Nothing this addon can reach changes
     * that; the refresh is queued anyway, because it is correct and it costs a handful of
     * tile coordinates.
     */
    private static void refreshFootprint(ServerLevel level, Footprint footprint) {
        // Half a BlueMap hires tile at its default size, so no whole tile inside the
        // footprint can fall between two samples.
        int step = 16;
        int columns = (footprint.maxX() - footprint.minX()) / step + 1;
        int rows = (footprint.maxZ() - footprint.minZ()) / step + 1;
        if ((long) columns * rows > MAX_REFRESH_SAMPLES) {
            BlueMap3D.refreshArea(level, new BlockPos(footprint.minX(), footprint.y(), footprint.minZ()));
            BlueMap3D.refreshArea(level, new BlockPos(footprint.maxX(), footprint.y(), footprint.maxZ()));
            return;
        }
        for (int x = footprint.minX(); x <= footprint.maxX(); x += step) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z += step) {
                BlueMap3D.refreshArea(level, new BlockPos(x, footprint.y(), z));
            }
        }
        // The far corner, which the strides above only reach if the footprint happens to
        // be an exact multiple of the step.
        BlueMap3D.refreshArea(level, new BlockPos(footprint.maxX(), footprint.y(), footprint.maxZ()));
    }

    /** Ceiling on the positions one appearing or departing ship may queue. */
    private static final int MAX_REFRESH_SAMPLES = 64;

    /**
     * Snapshots one ship, or {@code null} if there is nothing to draw for it.
     *
     * <p>Every value is read now rather than through the live sub-level. The pose's
     * vectors are mutable and are written in place by the physics step, so a
     * {@link SceneObject} holding onto them would report whatever the ship was doing when
     * core got round to asking, which is not the same tick.
     */
    private static SceneObject toSceneObject(ServerLevel level, ServerSubLevel ship) {
        if (ship == null || ship.isRemoved()) {
            return null;
        }
        UUID uuid = ship.getUniqueId();
        if (uuid == null) {
            // Nothing stable to key a mesh against, and a ship without an id is one
            // mid-allocation. It will be here next tick.
            return null;
        }
        ServerLevelPlot plot = ship.getPlot();
        if (plot == null) {
            return null;
        }
        BoundingBox3ic bounds = plot.getBoundingBox();
        if (bounds == null) {
            // Null until the plot has a loaded chunk with a block in it.
            return null;
        }

        Pose3dc pose = ship.logicalPose();
        Vector3dc origin = pose.position();
        Vector3dc pivotPoint = pose.rotationPoint();
        Quaterniondc orientation = pose.orientation();

        String objectId = uuid.toString();
        String name = ship.getName();
        String label = (name == null || name.isBlank()) ? null : name;
        ResourceKey<Level> dimension = level.dimension();

        BlockPos min = new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ());
        BlockPos max = new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ());
        Vec3 pivot = new Vec3(pivotPoint.x(), pivotPoint.y(), pivotPoint.z());
        Vec3 position = new Vec3(origin.x(), origin.y(), origin.z());
        Quaternionf rotation = new Quaternionf(
                (float) orientation.x(), (float) orientation.y(),
                (float) orientation.z(), (float) orientation.w());
        long version = geometryVersion(ship, bounds);

        return new SceneObject() {
            @Override
            public String id() {
                // The sub-level's uuid, which survives it sailing, turning, being saved
                // and being loaded again. Anything derived from where the ship is would
                // make every publish a brand-new object: a re-mesh of the whole hull per
                // interval, and no interpolation between one position and the next.
                return objectId;
            }

            @Override
            public BlockVolume geometry() {
                // Only called when the version above has changed, so the cost of walking
                // the hull is paid on a structural change and not on movement.
                //
                // LevelAccelerator is a BlockGetter that holds onto the last chunk it
                // touched; Sable reads exactly these bounds through exactly this wrapper
                // when it rebuilds a ship's mass. region() copies eagerly, so what comes
                // back is a snapshot and is safe on the baker thread.
                return BlockVolume.region(new LevelAccelerator(level), min, max, pivot);
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
            public String label() {
                return label;
            }

            @Override
            public ResourceKey<Level> dimension() {
                return dimension;
            }
        };
    }

    /**
     * A number that changes when the ship's blocks change and not when it moves.
     *
     * <p>This is the one thing worth getting right. A ship is a hull, not a turtle:
     * re-meshing one because it sailed a metre costs a full block walk and a re-bake of
     * tens of thousands of quads, every publish interval, per ship. So nothing positional
     * goes in here - the pose is deliberately not consulted.
     *
     * <p>What does go in, cheapest first:
     * <ul>
     *   <li><b>the plot bounds</b>, which move whenever the hull grows or shrinks;</li>
     *   <li><b>the ship's own mass and centre of mass</b>, which Sable updates on every
     *       block change that has any physical effect, and which are in plot space and so
     *       are unmoved by sailing. The <em>self</em> tracker rather than the merged one:
     *       the merged figure also folds in whatever is constrained to the ship, and a
     *       ship should not be re-meshed because something moored to it moved;</li>
     *   <li><b>each chunk section's serialised size</b>, which catches a block being
     *       swapped for a different one that happens to weigh the same, because the new
     *       block has to enter that section's palette.</li>
     * </ul>
     *
     * <p>Sections are folded in commutatively. The plot's loaded-chunk list is in load
     * order, and an order-sensitive hash would re-mesh a ship whose chunks merely
     * reloaded in a different order.
     *
     * <p>What this misses: replacing a block with one of identical mass and inertia that
     * is <em>already</em> in that section's palette - a spruce plank for an oak one, in a
     * section that already has both. The alternative is hashing every block state every
     * interval, which is the whole cost this exists to avoid.
     */
    private static long geometryVersion(ServerSubLevel ship, BoundingBox3ic bounds) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, bounds.minX());
        hash = mix(hash, bounds.minY());
        hash = mix(hash, bounds.minZ());
        hash = mix(hash, bounds.maxX());
        hash = mix(hash, bounds.maxY());
        hash = mix(hash, bounds.maxZ());

        MassData mass = ship.getSelfMassTracker();
        if (mass != null) {
            hash = mix(hash, Double.doubleToLongBits(mass.getMass()));
            Vector3dc centre = mass.getCenterOfMass();
            hash = mix(hash, Double.doubleToLongBits(centre.x()));
            hash = mix(hash, Double.doubleToLongBits(centre.y()));
            hash = mix(hash, Double.doubleToLongBits(centre.z()));
        }

        long sections = 0L;
        for (PlotChunkHolder holder : ship.getPlot().getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) {
                continue;
            }
            long perChunk = mix(0xcbf29ce484222325L, holder.getPos().toLong());
            for (LevelChunkSection section : chunk.getSections()) {
                perChunk = mix(perChunk, section == null || section.hasOnlyAir()
                        ? 0 : section.getSerializedSize());
            }
            sections ^= perChunk;
        }
        return mix(hash, sections);
    }

    /** FNV-1a, sixty-four bits of it. */
    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }
}
