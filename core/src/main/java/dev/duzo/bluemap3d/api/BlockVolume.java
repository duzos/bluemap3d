package dev.duzo.bluemap3d.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A finite set of blocks in an object's own local space, ready to be meshed.
 *
 * <p>This is deliberately the narrowest thing the mesher needs, so that one baker
 * serves every kind of object. The three shapes it has to cover are:
 *
 * <ul>
 *   <li>a <b>single block</b> - a turtle; {@link #single(BlockState)}</li>
 *   <li>a <b>region of some level</b> - a ship, whose blocks live in a sub-level that
 *       the overworld's chunks know nothing about; {@link #region}</li>
 *   <li>a <b>sparse map</b> of positions to states - a contraption or a train
 *       carriage, which exists only as an in-memory block map; {@link #of(Map, Vec3)}</li>
 * </ul>
 *
 * <p>Implementations expose two access patterns because the mesher needs both:
 * {@link #forEachBlock} to walk the blocks that actually exist (volumes are often
 * mostly empty), and {@link #stateAt} for random neighbour lookups when deciding
 * whether a face is hidden. Getting both right is what lets a sparse contraption and
 * a dense ship hull go through the same code path at the same cost.
 *
 * <h2>Coordinates</h2>
 * Local block coordinates, any origin. {@link #pivot()} names the point that
 * {@link SceneObject#position()} places in the world and
 * {@link SceneObject#rotation()} rotates about - a ship's centre of mass, a
 * carriage's coupling point, or just the middle of a turtle's block.
 */
public interface BlockVolume {

    /** An empty volume. Meshes to nothing. */
    BlockVolume EMPTY = new BlockVolume() {
        @Override public BlockPos min() {
            return BlockPos.ZERO;
        }
        @Override public BlockPos max() {
            return BlockPos.ZERO;
        }
        @Override public Vec3 pivot() {
            return Vec3.ZERO;
        }
        @Override public BlockState stateAt(int x, int y, int z) {
            return Blocks.AIR.defaultBlockState();
        }
        @Override public void forEachBlock(BlockConsumer consumer) {
        }
        @Override public int blockCount() {
            return 0;
        }
    };

    /** Inclusive lower corner of the volume's bounding box, in local coordinates. */
    BlockPos min();

    /** Inclusive upper corner of the volume's bounding box, in local coordinates. */
    BlockPos max();

    /**
     * The point this volume rotates about and is positioned by, in local coordinates.
     *
     * <p>For a single block this is the block's centre, so a turtle spins in place
     * rather than swinging around its corner.
     */
    Vec3 pivot();

    /**
     * The state at a local position, or air if outside the volume.
     *
     * <p>Called for neighbour lookups during face culling, so it must be cheap and
     * must tolerate coordinates outside {@link #min()}..{@link #max()}.
     */
    BlockState stateAt(int x, int y, int z);

    /**
     * Visits every non-air block in the volume, in any order.
     *
     * <p>This is what the mesher iterates, so a sparse volume costs its block count
     * rather than its bounding-box volume.
     */
    void forEachBlock(BlockConsumer consumer);

    /**
     * How many non-air blocks this volume holds.
     *
     * <p>Used to reject volumes that would produce an unreasonable mesh before any
     * work is done. The default counts by walking, so override it when the count is
     * already known.
     */
    default int blockCount() {
        int[] n = {0};
        forEachBlock((x, y, z, state) -> n[0]++);
        return n[0];
    }

    /**
     * Extra models to draw on top of the blocks, for things block states do not describe.
     *
     * <p>A turtle's modem, a sign's text, an item frame's contents: all drawn by a
     * block-entity renderer from data the block state knows nothing about. Empty by default,
     * because most volumes are just blocks.
     *
     * @see ModelAttachment
     */
    default Collection<ModelAttachment> attachments() {
        return java.util.List.of();
    }

    /** Receives blocks from {@link #forEachBlock}. */
    @FunctionalInterface
    interface BlockConsumer {
        void accept(int x, int y, int z, BlockState state);
    }

    // -----------------------------------------------------------------------------
    // Factories
    // -----------------------------------------------------------------------------

    /**
     * A volume of exactly one block at the local origin, pivoting about its centre.
     *
     * <p>The turtle case.
     */
    static BlockVolume single(BlockState state) {
        return single(state, java.util.List.of());
    }

    /**
     * A single block plus extra models hung off it, such as a turtle's upgrades.
     *
     * @see ModelAttachment
     */
    static BlockVolume single(BlockState state, Collection<ModelAttachment> attachments) {
        Objects.requireNonNull(state, "state");
        Collection<ModelAttachment> extras = java.util.List.copyOf(attachments);
        return new BlockVolume() {
            @Override public Collection<ModelAttachment> attachments() {
                return extras;
            }
            @Override public BlockPos min() {
                return BlockPos.ZERO;
            }
            @Override public BlockPos max() {
                return BlockPos.ZERO;
            }
            @Override public Vec3 pivot() {
                return new Vec3(0.5, 0.5, 0.5);
            }
            @Override public BlockState stateAt(int x, int y, int z) {
                return (x == 0 && y == 0 && z == 0) ? state : Blocks.AIR.defaultBlockState();
            }
            @Override public void forEachBlock(BlockConsumer consumer) {
                consumer.accept(0, 0, 0, state);
            }
            @Override public int blockCount() {
                return 1;
            }
        };
    }

    /**
     * A volume read out of a live {@link BlockGetter} over an inclusive box.
     *
     * <p>The ship case: pass the sub-level and the ship's bounds. Blocks are copied
     * eagerly, so the returned volume is safe to mesh off the server thread and is
     * unaffected by later changes to the source.
     *
     * @param source the level or other block source to read
     * @param min    inclusive lower corner, in {@code source}'s coordinates
     * @param max    inclusive upper corner, in {@code source}'s coordinates
     * @param pivot  rotation origin, in {@code source}'s coordinates
     */
    static BlockVolume region(BlockGetter source, BlockPos min, BlockPos max, Vec3 pivot) {
        Objects.requireNonNull(source, "source");
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        BlockPos lo = new BlockPos(
                Math.min(min.getX(), max.getX()),
                Math.min(min.getY(), max.getY()),
                Math.min(min.getZ(), max.getZ()));
        BlockPos hi = new BlockPos(
                Math.max(min.getX(), max.getX()),
                Math.max(min.getY(), max.getY()),
                Math.max(min.getZ(), max.getZ()));

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = lo.getX(); x <= hi.getX(); x++) {
            for (int y = lo.getY(); y <= hi.getY(); y++) {
                for (int z = lo.getZ(); z <= hi.getZ(); z++) {
                    BlockState state = source.getBlockState(cursor.set(x, y, z));
                    if (!state.isAir()) {
                        blocks.put(new BlockPos(x, y, z), state);
                    }
                }
            }
        }
        return of(blocks, pivot);
    }

    /**
     * A volume backed by a sparse map of local positions to states.
     *
     * <p>The contraption case: Create hands out its carriage blocks as a map, and this
     * takes it directly. Air entries are ignored. The map is copied.
     *
     * @param blocks local position to state; air and {@code null} states are dropped
     * @param pivot  rotation origin, in the same coordinates as the map's keys
     */
    static BlockVolume of(Map<BlockPos, BlockState> blocks, Vec3 pivot) {
        return of(blocks, pivot, java.util.List.of());
    }

    /**
     * A sparse volume with attachments. The contraption case: Create hands out a
     * carriage's blocks as a map, and its bogey wheels are attachments on top.
     *
     * <p>An empty block map returns {@link #EMPTY}, which discards the attachments with
     * it. A volume with nothing to hang an attachment off is not a volume.
     */
    static BlockVolume of(Map<BlockPos, BlockState> blocks, Vec3 pivot,
                          Collection<ModelAttachment> attachments) {
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(pivot, "pivot");
        Collection<ModelAttachment> extras = java.util.List.copyOf(attachments);

        Map<BlockPos, BlockState> copy = new HashMap<>(blocks.size());
        blocks.forEach((pos, state) -> {
            if (pos != null && state != null && !state.isAir()) {
                copy.put(pos.immutable(), state);
            }
        });

        if (copy.isEmpty()) {
            return EMPTY;
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : copy.keySet()) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        BlockPos lo = new BlockPos(minX, minY, minZ);
        BlockPos hi = new BlockPos(maxX, maxY, maxZ);
        BlockState air = Blocks.AIR.defaultBlockState();

        return new BlockVolume() {
            @Override public Collection<ModelAttachment> attachments() {
                return extras;
            }
            @Override public BlockPos min() {
                return lo;
            }
            @Override public BlockPos max() {
                return hi;
            }
            @Override public Vec3 pivot() {
                return pivot;
            }
            @Override public BlockState stateAt(int x, int y, int z) {
                // Allocates rather than reusing a MutableBlockPos: this is called
                // from face culling, and a shared cursor would make the volume
                // unsafe to hand to a baking thread pool. Bakes are rare, so the
                // garbage is cheaper than the concurrency bug.
                return copy.getOrDefault(new BlockPos(x, y, z), air);
            }
            @Override public void forEachBlock(BlockConsumer consumer) {
                copy.forEach((pos, state) ->
                        consumer.accept(pos.getX(), pos.getY(), pos.getZ(), state));
            }
            @Override public int blockCount() {
                return copy.size();
            }
        };
    }

    /**
     * A volume with no blocks at all, made entirely of attachments.
     *
     * <p>Some geometry has no block behind it - it is authored purely as model pieces
     * laid out in space, the way a piece of track is drawn along a curve. {@link #of}
     * cannot express that: an empty block map is indistinguishable from "nothing here"
     * and returns {@link #EMPTY}, discarding whatever attachments came with it. This is
     * the escape hatch for when there genuinely are no blocks and the attachments are
     * the whole object.
     *
     * <p>Bounds are taken as parameters rather than derived, because there are no block
     * positions to derive them from, and both the mesher's neighbour lookups and the
     * browser's bounding volume need an extent regardless.
     *
     * <p>{@link #blockCount()} is 0 here, same as {@link #EMPTY}. That means
     * {@code maxBlocksPerObject} cannot see this volume coming - a block count of zero
     * clears any block-based cap no matter how much attachment geometry rides along
     * with it. {@code maxAttachmentsPerObject} in {@link dev.duzo.bluemap3d.Config}
     * exists to cover exactly this gap; do not add an attachments-only volume without it.
     *
     * @param min         inclusive lower corner of the volume's bounding box
     * @param max         inclusive upper corner of the volume's bounding box
     * @param pivot       rotation origin, in the same coordinates as {@code min}/{@code max}
     * @param attachments the models that make up this volume; must not be empty, or there
     *                    would be nothing to draw at all
     */
    static BlockVolume attachments(BlockPos min, BlockPos max, Vec3 pivot,
                                   Collection<ModelAttachment> attachments) {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        Objects.requireNonNull(pivot, "pivot");
        Objects.requireNonNull(attachments, "attachments");
        BlockPos lo = min.immutable();
        BlockPos hi = max.immutable();
        Collection<ModelAttachment> extras = java.util.List.copyOf(attachments);
        BlockState air = Blocks.AIR.defaultBlockState();

        return new BlockVolume() {
            @Override public Collection<ModelAttachment> attachments() {
                return extras;
            }
            @Override public BlockPos min() {
                return lo;
            }
            @Override public BlockPos max() {
                return hi;
            }
            @Override public Vec3 pivot() {
                return pivot;
            }
            @Override public BlockState stateAt(int x, int y, int z) {
                return air;
            }
            @Override public void forEachBlock(BlockConsumer consumer) {
            }
            @Override public int blockCount() {
                return 0;
            }
        };
    }
}
