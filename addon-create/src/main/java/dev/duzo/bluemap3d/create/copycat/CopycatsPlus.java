package dev.duzo.bluemap3d.create.copycat;

import com.copycatsplus.copycats.content.copycat.cogwheel.CopycatCogWheelBlock;
import com.copycatsplus.copycats.content.copycat.fluid_pipe.CopycatFluidPipeBlock;
import com.copycatsplus.copycats.content.copycat.fluid_pipe.CopycatGlassFluidPipeBlock;
import com.copycatsplus.copycats.content.copycat.shaft.CopycatShaftBlock;
import com.copycatsplus.copycats.content.copycat.slope.CopycatSlopeBlock;
import com.copycatsplus.copycats.content.copycat.slope_layer.CopycatSlopeLayerBlock;
import com.copycatsplus.copycats.content.copycat.vertical_slope.CopycatVerticalSlopeBlock;
import com.copycatsplus.copycats.foundation.copycat.ICopycatBlock;
import com.copycatsplus.copycats.foundation.copycat.ICopycatBlockEntity;
import com.copycatsplus.copycats.foundation.copycat.multistate.IMultiStateCopycatBlock;
import com.copycatsplus.copycats.foundation.copycat.multistate.IMultiStateCopycatBlockEntity;
import com.copycatsplus.copycats.foundation.copycat.multistate.MaterialItemStorage;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import dev.duzo.bluemap3d.api.BlockAppearance;
import dev.duzo.bluemap3d.api.ModelAttachment;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything in this addon that touches a Copycats+ type, in one class.
 *
 * <p>Copycats+ is optional, so the rule is that no class that loads on a plain Create
 * server may link against it. This is the quarantine: {@link CopycatAppearances} calls in
 * here only after checking the mod is loaded, and passes and returns nothing but vanilla and
 * Create types, so a server without Copycats+ never resolves this class at all.
 *
 * <h2>Multi-material copycats</h2>
 * A Copycats+ slab can be oak on the bottom and diamond on top, a board can have a
 * different material on each of its six faces. Its block entity stores one material per
 * <em>part</em>, keyed by a part name, and the block's shape is the union of the parts that
 * exist in its current state. To draw it the shape has to be cut back into per-part pieces,
 * and the three families of Copycats+ block need three different cuts:
 *
 * <ul>
 *   <li><b>Parts in different cells</b> - slabs, bytes, byte panels, half layers. The block
 *       declares a grid ({@code vectorScale}) and a cell in it for each part, so a part's
 *       piece is the shape clipped to that cell.</li>
 *   <li><b>Boards</b> - every part is in the same cell, so the grid says nothing, but the
 *       parts are named for the six faces and each is a one-pixel plate on its face. A
 *       plate's piece is the shape clipped to that plate. Where two plates meet at an edge
 *       they overlap on a strip, which would be drawn twice and z-fight, so the faces are
 *       taken in a fixed order and each plate has the earlier plates subtracted from it: a
 *       shared strip belongs to exactly one part.</li>
 *   <li><b>Anything else</b> - a cogwheel, a shaft, where the parts share a cell and are not
 *       faces. The whole shape takes the material of the block's default part. Wrong for
 *       the odd part, right for the common case of one material throughout.</li>
 * </ul>
 */
final class CopycatsPlus {

    private CopycatsPlus() {
    }

    /** Whether the block is any Copycats+ copycat. */
    static boolean isCopycat(Block block) {
        return block instanceof ICopycatBlock;
    }

    /** Whether the block entity is any Copycats+ copycat's. */
    static boolean isCopycatEntity(BlockEntity blockEntity) {
        return blockEntity instanceof ICopycatBlockEntity;
    }

    /** Whether the block stores a material per part. */
    static boolean isMultiState(Block block) {
        return block instanceof IMultiStateCopycatBlock;
    }

    /**
     * The appearance of a copycat whose shape is not boxes - a cogwheel, a shaft, a pipe or a
     * slope - or {@code null} if the block is none of those.
     *
     * <p>Asked before the voxel-shape route and before the multi-material one, because every
     * block it answers for would otherwise come out as a staircase or a plain box. Reads the
     * block's own properties and hands {@link CopycatShapes} plain values, so that class never
     * sees a Copycats+ type.
     */
    @Nullable
    static BlockAppearance special(BlockState state, CompoundTag tag) {
        Block block = state.getBlock();
        if (block instanceof CopycatCogWheelBlock cog) {
            CompoundTag data = tag.getCompound("material_data");
            // The part names are what the block entity saves its materials under; lowercase,
            // as confirmed against a live save.
            return CopycatShapes.cog(state.getValue(RotatedPillarKineticBlock.AXIS), cog.isLargeCog(),
                    materialOfPart(data, "cogwheel"), materialOfPart(data, "shaft"));
        }
        if (block instanceof CopycatShaftBlock) {
            return CopycatShapes.shaft(state.getValue(RotatedPillarKineticBlock.AXIS),
                    CopycatAppearances.materialOf(tag.getCompound("Material")));
        }
        if (block instanceof CopycatFluidPipeBlock) {
            return CopycatShapes.fluidPipe(state, CopycatAppearances.materialOf(tag.getCompound("Material")));
        }
        if (block instanceof CopycatGlassFluidPipeBlock) {
            return CopycatShapes.glassPipe(state.getValue(BlockStateProperties.AXIS),
                    CopycatAppearances.materialOf(tag.getCompound("Material")));
        }
        if (block instanceof CopycatSlopeBlock) {
            return CopycatShapes.slope(state.getValue(CopycatSlopeBlock.FACING),
                    state.getValue(CopycatSlopeBlock.HALF) == Half.TOP, 0f, 16f,
                    CopycatAppearances.materialOf(tag.getCompound("Material")));
        }
        if (block instanceof CopycatVerticalSlopeBlock) {
            return CopycatShapes.verticalSlope(state.getValue(CopycatVerticalSlopeBlock.FACING),
                    CopycatAppearances.materialOf(tag.getCompound("Material")));
        }
        if (block instanceof CopycatSlopeLayerBlock) {
            // Up to four layers the ramp climbs from nothing to 4px a layer. Beyond that it is
            // a ramp standing on a slab: it starts at the slab's height and climbs to the top,
            // so eight layers is a full cube.
            int layers = state.getValue(CopycatSlopeLayerBlock.LAYERS);
            float start = layers <= 4 ? 0f : 4f * (layers - 4);
            float end = layers <= 4 ? 4f * layers : 16f;
            return CopycatShapes.slope(state.getValue(CopycatSlopeLayerBlock.FACING),
                    state.getValue(CopycatSlopeLayerBlock.HALF) == Half.TOP, start, end,
                    CopycatAppearances.materialOf(tag.getCompound("Material")));
        }
        return null;
    }

    /** Whether the block turns when its kinetic network does: a cogwheel or a shaft. */
    static boolean spins(Block block) {
        return block instanceof CopycatCogWheelBlock || block instanceof CopycatShaftBlock;
    }

    /** The rate a kinetic copycat spins at, or {@code null} if it is still. */
    @Nullable
    static ModelAttachment.Rate spin(BlockState state, float rpm) {
        return KineticSpin.rateFor(state.getValue(RotatedPillarKineticBlock.AXIS), rpm);
    }

    /**
     * The pieces of a multi-material copycat, or {@code null} if there is nothing to draw.
     *
     * @param shape the block's whole shape, in 0..16 model space
     */
    static List<BlockAppearance.Piece> pieces(BlockState state, CompoundTag tag, List<float[]> shape) {
        IMultiStateCopycatBlock block = (IMultiStateCopycatBlock) state.getBlock();
        CompoundTag data = tag.getCompound("material_data");

        // Sorted so the same state always yields the same piece order. The block hands back
        // a Set, and BlockAppearance equality is by list: an unstable order would be a cache
        // miss in the mesher for no reason.
        List<String> parts = new ArrayList<>();
        for (String part : block.storageProperties()) {
            if (block.partExists(state, part)) {
                parts.add(part);
            }
        }
        parts.sort(null);

        List<BlockAppearance.Piece> pieces = new ArrayList<>();
        if (!parts.isEmpty()) {
            if (distinctCells(block, state, parts)) {
                byCell(block, state, data, parts, shape, pieces);
            } else if (allFaces(parts)) {
                byFace(data, parts, shape, pieces);
            }
        }
        if (pieces.isEmpty()) {
            // The third family, and also the safety net for the first two coming up empty -
            // a block whose parts are real but whose shape does not line up with its own
            // grid still draws, in one material, rather than not at all.
            BlockState material = materialOfPart(data, block.defaultProperty());
            for (float[] box : shape) {
                pieces.add(CopycatAppearances.piece(box, material));
            }
        }
        return pieces;
    }

    /**
     * The typed fingerprint of a Copycats+ block entity: the materials, by part, hashed
     * through their block-state ids. Never serialises anything.
     */
    static long fingerprint(BlockEntity blockEntity) {
        long hash = materialsFingerprint(blockEntity);
        if (blockEntity instanceof KineticBlockEntity kinetic) {
            // The speed the resolver will spin the block at, quantised the same way, so the
            // version moves exactly when the mesh would: once per whole-RPM change, not on
            // every fractional wobble. The saved tag is deliberately not consulted for it -
            // see BlockAppearanceResolver.resolve(BlockState, BlockEntity).
            hash ^= it.unimi.dsi.fastutil.HashCommon.mix(0x5EEDL + Math.round(kinetic.getSpeed()));
        }
        return hash;
    }

    /** The material part of {@link #fingerprint}. */
    private static long materialsFingerprint(BlockEntity blockEntity) {
        // The multi-material interface extends the single-material one, so it has to be
        // asked first or every multi-material copycat would be read as having one material.
        if (blockEntity instanceof IMultiStateCopycatBlockEntity multi) {
            MaterialItemStorage storage = multi.getMaterialItemStorage();
            if (storage == null) {
                return 0L;
            }
            long hash = 0L;
            for (Map.Entry<String, BlockState> entry : storage.getMaterialMap().entrySet()) {
                // Mixed per pair and then summed, for the reason given in
                // CopycatAppearances.fingerprint: a linear sum cannot tell two parts swapping
                // materials from nothing having changed. The sum keeps it order-independent.
                hash += it.unimi.dsi.fastutil.HashCommon.mix(
                        ((long) entry.getKey().hashCode() << 32)
                                ^ (Block.getId(entry.getValue()) & 0xFFFFFFFFL));
            }
            return hash;
        }
        if (blockEntity instanceof ICopycatBlockEntity single) {
            return Block.getId(single.getMaterial());
        }
        return 0L;
    }

    /** Whether every existing part sits in a cell of its own. */
    private static boolean distinctCells(IMultiStateCopycatBlock block, BlockState state,
                                         List<String> parts) {
        Set<Vec3i> cells = new HashSet<>();
        for (String part : parts) {
            cells.add(block.getVectorFromProperty(state, part));
        }
        return cells.size() == parts.size();
    }

    /** Whether every part is named for one of the six faces - a board. */
    private static boolean allFaces(List<String> parts) {
        for (String part : parts) {
            if (Direction.byName(part) == null) {
                return false;
            }
        }
        return true;
    }

    /** One piece per part: the shape clipped to that part's cell of the block's grid. */
    private static void byCell(IMultiStateCopycatBlock block, BlockState state, CompoundTag data,
                               List<String> parts, List<float[]> shape,
                               List<BlockAppearance.Piece> out) {
        Vec3i scale = block.vectorScale(state);
        for (String part : parts) {
            Vec3i cell = block.getVectorFromProperty(state, part);
            float[] box = {
                    cell.getX() * 16f / scale.getX(),
                    cell.getY() * 16f / scale.getY(),
                    cell.getZ() * 16f / scale.getZ(),
                    (cell.getX() + 1) * 16f / scale.getX(),
                    (cell.getY() + 1) * 16f / scale.getY(),
                    (cell.getZ() + 1) * 16f / scale.getZ()};
            BlockState material = materialOfPart(data, part);
            for (float[] shapeBox : shape) {
                float[] clipped = CopycatBoxes.intersect(shapeBox, box);
                if (clipped != null) {
                    out.add(CopycatAppearances.piece(clipped, material));
                }
            }
        }
    }

    /**
     * One piece per face plate, clipped so no region is claimed twice.
     *
     * <p>Walked in {@link Direction#values()} order - down, up, north, south, west, east -
     * and not the order of {@code parts}, because which part wins a shared edge strip must
     * not depend on how a set happened to iterate.
     */
    private static void byFace(CompoundTag data, List<String> parts, List<float[]> shape,
                               List<BlockAppearance.Piece> out) {
        List<float[]> claimed = new ArrayList<>();
        for (Direction face : Direction.values()) {
            if (!parts.contains(face.getName())) {
                continue;
            }
            float[] plate = plateOf(face);
            BlockState material = materialOfPart(data, face.getName());

            List<float[]> boxes = new ArrayList<>();
            for (float[] shapeBox : shape) {
                float[] clipped = CopycatBoxes.intersect(shapeBox, plate);
                if (clipped != null) {
                    boxes.add(clipped);
                }
            }
            // Earlier plates are subtracted as whole plates, not as the boxes they ended up
            // with: the strip belongs to the earlier part whether or not the shape covered it.
            for (float[] earlier : claimed) {
                List<float[]> remaining = new ArrayList<>();
                for (float[] box : boxes) {
                    CopycatBoxes.subtract(box, earlier, remaining);
                }
                boxes = remaining;
            }
            for (float[] box : boxes) {
                out.add(CopycatAppearances.piece(box, material));
            }
            claimed.add(plate);
        }
    }

    /** The one-pixel plate against a face of the block, in 0..16 model space. */
    private static float[] plateOf(Direction face) {
        return switch (face) {
            case DOWN -> new float[]{0, 0, 0, 16, 1, 16};
            case UP -> new float[]{0, 15, 0, 16, 16, 16};
            case NORTH -> new float[]{0, 0, 0, 16, 16, 1};
            case SOUTH -> new float[]{0, 0, 15, 16, 16, 16};
            case WEST -> new float[]{0, 0, 0, 1, 16, 16};
            case EAST -> new float[]{15, 0, 0, 16, 16, 16};
        };
    }

    /** The material stored for a part, or the unskinned default if it has none. */
    private static BlockState materialOfPart(CompoundTag data, String part) {
        return CopycatAppearances.materialOf(data.getCompound(part).getCompound("material"));
    }
}
