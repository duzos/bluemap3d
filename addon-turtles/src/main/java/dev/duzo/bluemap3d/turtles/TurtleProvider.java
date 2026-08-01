package dev.duzo.bluemap3d.turtles;

import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.BlueMap3D;
import dev.duzo.bluemap3d.api.ModelAttachment;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports every loaded ComputerCraft turtle as a one-block {@link SceneObject}.
 *
 * <p>Turtles are the cheapest possible end-to-end test of the framework and the harshest
 * test of the live path: one block of geometry that never changes, attached to something
 * that is constantly moving and turning. If turtles look right, the transform stream and
 * the browser-side interpolation are both correct.
 *
 * <h2>Why this touches no ComputerCraft classes</h2>
 * Turtles are found through the vanilla block-entity registry by id rather than by
 * importing {@code TurtleBlockEntity}. CC's API does not expose a registry of live
 * turtles, and the class that does ({@code dan200.computercraft.shared.turtle.blocks})
 * is implementation, not API - binding to it would break on any internal refactor. Facing
 * comes from the vanilla {@code facing} property in the turtle's own blockstate file, and
 * the label from the block entity's saved data. So this addon needs CC to be installed,
 * but not to be stable.
 *
 * <p>Movement smoothness is left to core: CC animates a turtle between blocks, and rather
 * than reading its render offset, the browser interpolates between published samples.
 * That is the same code path a ship or a train uses, which is the point.
 */
public final class TurtleProvider implements SceneObjectProvider {

    /** CC's two turtle block entities. Advanced turtles are a separate registration. */
    private static final List<ResourceLocation> TURTLE_TYPES = List.of(
            ResourceLocation.fromNamespaceAndPath("computercraft", "turtle_normal"),
            ResourceLocation.fromNamespaceAndPath("computercraft", "turtle_advanced"));

    private final ChunkTracker chunks;
    private List<BlockEntityType<?>> types;
    /** Last published position per turtle, for spotting movement. */
    private final Map<String, BlockPos> lastSeen = new java.util.concurrent.ConcurrentHashMap<>();

    public TurtleProvider(ChunkTracker chunks) {
        this.chunks = chunks;
    }

    @Override
    public String id() {
        return "computercraft_turtles";
    }

    /**
     * Turtles are real blocks in real chunks, so BlueMap bakes them into terrain tiles.
     * Without hiding them you see each turtle twice: the live one, and a frozen copy
     * wherever it happened to be when that tile was last rendered.
     */
    @Override
    public Collection<ResourceLocation> hiddenBlocks() {
        return List.of(
                ResourceLocation.fromNamespaceAndPath("computercraft", "turtle_normal"),
                ResourceLocation.fromNamespaceAndPath("computercraft", "turtle_advanced"));
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        List<BlockEntityType<?>> turtleTypes = resolveTypes();
        if (turtleTypes.isEmpty()) {
            return List.of();
        }

        Set<ChunkPos> loaded = chunks.loadedChunks(level);
        if (loaded.isEmpty()) {
            return List.of();
        }

        List<SceneObject> out = new ArrayList<>();
        for (ChunkPos chunkPos : loaded) {
            // getChunkNow: never force a chunk to load just to look in it.
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            if (chunk == null) {
                continue;
            }
            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                BlockEntity blockEntity = entry.getValue();
                if (!turtleTypes.contains(blockEntity.getType())) {
                    continue;
                }
                BlockPos pos = entry.getKey();
                Identity identity = identityOf(blockEntity, level, pos);
                refreshIfMoved(level, identity.id(), pos);
                out.add(toSceneObject(level, pos, blockEntity, identity));
            }
        }
        return out;
    }

    /**
     * Asks BlueMap to re-render around a turtle that has moved since it was last seen.
     *
     * <p>A working turtle mines and places, which changes terrain BlueMap has already baked
     * into a tile. Nothing observable says when that happened - CC fires no event for it, and
     * the turtle's own state does not record it - so movement is used as the proxy: a turtle
     * that has moved may have changed the world on the way, and both the tile it left and the
     * one it arrived in get queued.
     *
     * <p>This is a heuristic, and it is worth being clear that it is one. It refreshes tiles
     * for turtles that only walked, and it would miss a stationary turtle mining straight
     * down were it not for the fact that mining down moves it down. Core coalesces the
     * positions into tiles and batches them, so the cost is bounded by tiles touched rather
     * than blocks changed, which is what makes an over-eager heuristic affordable.
     */
    private void refreshIfMoved(ServerLevel level, String id, BlockPos pos) {
        BlockPos previous = lastSeen.put(id, pos.immutable());
        if (previous == null || previous.equals(pos)) {
            return;
        }
        BlueMap3D.refreshArea(level, previous);
        BlueMap3D.refreshArea(level, pos);
    }

    private SceneObject toSceneObject(ServerLevel level, BlockPos pos, BlockEntity blockEntity,
                                      Identity identity) {
        BlockState state = blockEntity.getBlockState();
        // Turtles are one block, so the whole volume is the block itself and the pivot
        // is its centre. Rotation is baked out of the model and applied as a quaternion
        // instead, so the mesh is shared between every turtle facing any direction.
        BlockState upright = uprightState(state);

        ResourceKey<Level> dimension = level.dimension();
        Vec3 centre = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        float yaw = yawOf(state);
        String objectId = identity.id();
        String label = identity.label();
        List<ModelAttachment> upgrades = identity.upgrades();
        // The shape only changes when the upgrades do, so this is what decides a re-bake.
        long version = 1L + identity.upgradeSig().hashCode();

        return new SceneObject() {
            @Override
            public String id() {
                return objectId;
            }

            @Override
            public BlockVolume geometry() {
                return BlockVolume.single(upright, upgrades);
            }

            @Override
            public long geometryVersion() {
                // Derived from the equipped upgrades and nothing else. A turtle's body never
                // changes shape, so a turtle that keeps its upgrades is never re-baked no
                // matter how far it travels - and one that gains a modem is.
                return version;
            }

            @Override
            public Vec3 position() {
                return centre;
            }

            @Override
            public Quaternionf rotation() {
                return new Quaternionf().rotateY(yaw);
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
     * The turtle's state facing north, so the baked mesh is orientation-independent.
     *
     * <p>Baking one mesh per facing would work but would quadruple the meshes for no
     * gain: the rotation is streamed anyway, and it is what makes a turning turtle turn
     * smoothly rather than snapping through four discrete models.
     */
    private static BlockState uprightState(BlockState state) {
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        }
        return state;
    }

    /** Yaw in radians that takes a north-facing model to the turtle's actual facing. */
    private static float yawOf(BlockState state) {
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return 0f;
        }
        return switch (state.getValue(BlockStateProperties.HORIZONTAL_FACING)) {
            case SOUTH -> (float) Math.PI;
            case WEST -> (float) (Math.PI / 2);
            case EAST -> (float) (-Math.PI / 2);
            default -> 0f;
        };
    }

    /**
     * A turtle's stable identity, label and equipped upgrades.
     *
     * @param upgrades    models for whatever is bolted to its sides
     * @param upgradeSig  a summary of those, folded into the geometry version so fitting or
     *                    removing an upgrade re-bakes the mesh
     */
    private record Identity(String id, String label, List<ModelAttachment> upgrades,
                            String upgradeSig) {
    }

    /**
     * The turtle's identity and label, from its saved data.
     *
     * <p>Identity is {@code ComputerId}, which is the only thing about a turtle that
     * survives it moving. That matters more than it looks: the id is what core uses to
     * decide whether an object is the same object as last tick, so a position-derived id
     * would make every step a brand-new object - no interpolation between the old and new
     * position, a re-bake and a new mesh file per block travelled, and a one-interval gap
     * where the turtle is not in the feed at all. Which is exactly what happened before
     * this was measured against a real moving turtle.
     *
     * <p>A turtle that has never been switched on has no {@code ComputerId} yet, because CC
     * creates the computer on first boot. Those fall back to their position, which is
     * stable for precisely as long as they cannot move.
     *
     * <p>Both values are read from the block entity's saved data rather than through a CC
     * class: nothing in CC's API exposes them, and the class that does is implementation.
     * Keys are matched case-insensitively rather than assumed, because CC has moved the
     * label between an NBT key and a data component across versions.
     */
    private static Identity identityOf(BlockEntity blockEntity, ServerLevel level, BlockPos pos) {
        String label = null;
        String id = null;
        List<ModelAttachment> upgrades = List.of();
        String upgradeSig = "";
        try {
            CompoundTag tag = blockEntity.saveWithoutMetadata(level.registryAccess());
            upgrades = TurtleUpgrades.of(tag);
            upgradeSig = TurtleUpgrades.signature(tag);
            for (String key : tag.getAllKeys()) {
                if (key.equalsIgnoreCase("label") && tag.getTagType(key) == Tag.TAG_STRING) {
                    String value = tag.getString(key);
                    label = value.isBlank() ? null : value;
                } else if (key.equalsIgnoreCase("computerid") && tag.getTagType(key) == Tag.TAG_INT) {
                    id = "computer" + tag.getInt(key);
                }
            }
        } catch (RuntimeException e) {
            // Serialising a block entity should not be able to fail, but a broken upgrade
            // could make it throw, and neither value is worth an error.
        }
        if (id == null) {
            id = "at" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
        }
        return new Identity(id, label, upgrades, upgradeSig);
    }

    /**
     * Looks the turtle block-entity types up once CC's registry is populated.
     *
     * <p>Resolved lazily rather than in the constructor: registries are not filled at mod
     * construction time.
     */
    private List<BlockEntityType<?>> resolveTypes() {
        List<BlockEntityType<?>> resolved = types;
        if (resolved != null) {
            return resolved;
        }
        List<BlockEntityType<?>> found = new ArrayList<>(TURTLE_TYPES.size());
        for (ResourceLocation id : TURTLE_TYPES) {
            BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id);
            if (type != null) {
                found.add(type);
            }
        }
        types = List.copyOf(found);
        return types;
    }
}
