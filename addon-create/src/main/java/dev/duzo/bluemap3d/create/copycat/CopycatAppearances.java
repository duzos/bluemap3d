package dev.duzo.bluemap3d.create.copycat;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.copycat.CopycatBlock;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import dev.duzo.bluemap3d.api.BlockAppearance;
import dev.duzo.bluemap3d.api.BlockAppearanceResolver;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws Create's copycats - and Copycats+'s, when it is installed - in the material they
 * are skinned in.
 *
 * <h2>Why this exists</h2>
 * A copycat is one block state whose look is a block entity field. Create's
 * {@code copycat_panel} and {@code copycat_step}, and every Copycats+ copycat, map to an
 * empty model in their blockstate JSON: the shape and the skin are assembled at render time
 * by client-only baked-model code, so on a server the model lookup finds nothing and the
 * carriage draws a hole or, for Copycats+, a grey map-colour cube. This resolver supplies
 * what the lookup cannot: the block's voxel shape, which is computed server-side from its
 * own code, dressed in the material read out of its block entity.
 *
 * <h2>Where the material is</h2>
 * <ul>
 *   <li><b>Single material</b> - Create's copycats and the simpler Copycats+ ones. A
 *       {@code Material} compound, in the format {@link NbtUtils#writeBlockState} writes. An
 *       unskinned copycat has {@code create:copycat_base} there, or nothing, and
 *       {@code copycat_base} is a real block with a real texture - the frame the client
 *       draws on a bare copycat - so it is the default rather than a special case.</li>
 *   <li><b>Several materials</b> - Copycats+'s slabs, boards, bytes and the like. A
 *       {@code material_data} compound with one entry per part, each holding a
 *       {@code material}. See {@link CopycatsPlus}, which owns every Copycats+ type.</li>
 * </ul>
 * Which of the two applies is decided by the <em>block</em>, never by which keys happen to
 * be in the tag: a copycat that was migrated from single to multi material can carry a stale
 * {@code Material} next to its real {@code material_data}.
 *
 * <h2>What it does not do</h2>
 * Connected textures are a client-side effect and are ignored. A copycat cogwheel or shaft
 * is drawn as its static shape; the kinetic part is not animated. A sloped copycat is its
 * stepped voxel shape, which is what the server has.
 *
 * <h2>Class loading</h2>
 * Copycats+ is an optional mod, and this class loads on every server that has Create. It
 * therefore never names a Copycats+ type in a signature, field or local, and every route
 * into {@link CopycatsPlus} sits behind {@link #copycatsPlusLoaded()}, so the JVM never
 * tries to link a class that is not there.
 */
public final class CopycatAppearances implements BlockAppearanceResolver {

    /** More boxes than this and the shape is detail nobody sees at map zoom. */
    private static final int MAX_BOXES = 64;

    /** Resolved on first use, not in a constructor: it reads the mod list. */
    private volatile Boolean copycatsPlus;

    @Override
    public boolean handles(BlockState state) {
        Block block = state.getBlock();
        // Create's own check first: it is a plain instanceof, and on a server with no
        // Copycats+ it is the only one that ever runs. create:copycat_bars is deliberately
        // not a CopycatBlock - it has real models - so it is not claimed.
        return block instanceof CopycatBlock
                || (copycatsPlusLoaded() && CopycatsPlus.isCopycat(block));
    }

    @Override
    @Nullable
    public BlockAppearance resolve(BlockState state, CompoundTag blockEntityTag) {
        List<float[]> shape = CopycatBoxes.shapeOf(state, MAX_BOXES);
        if (shape == null) {
            return null;
        }

        List<BlockAppearance.Piece> pieces;
        if (copycatsPlusLoaded() && CopycatsPlus.isMultiState(state.getBlock())) {
            pieces = CopycatsPlus.pieces(state, blockEntityTag, shape);
        } else {
            BlockState material = materialOf(blockEntityTag.getCompound("Material"));
            pieces = new ArrayList<>(shape.size());
            for (float[] box : shape) {
                pieces.add(piece(box, material));
            }
        }
        return pieces == null || pieces.isEmpty() ? null : new BlockAppearance(pieces);
    }

    @Override
    public long fingerprint(CompoundTag blockEntityTag) {
        // Both shapes of tag are hashed, because this is not told which block it is looking
        // at and need not be: the block itself is already folded into every provider's
        // version, so only the data that varies for a given block has to be here. A stale
        // Material on a multi-material copycat adds a constant, which changes nothing.
        //
        // Only the material compounds are hashed, not their siblings. consumedItem and
        // enableCT sit next to each material in material_data and neither is drawn, so
        // hashing the whole entry would re-mesh a carriage whenever somebody toggled
        // connected textures. CompoundTag.hashCode is content-based, so none of this
        // depends on the process.
        long hash = 0x9E3779B97F4A7C15L * (blockEntityTag.contains("Material")
                ? blockEntityTag.getCompound("Material").hashCode() : 0);
        CompoundTag parts = blockEntityTag.getCompound("material_data");
        for (String part : parts.getAllKeys()) {
            // Each (part, material) pair goes through a non-linear mixer before the sum. The
            // sum is what keeps the hash independent of key order, but a plain linear
            // combination of part and material is blind to the swap that matters most: two
            // parts trading materials - the top and bottom of a double slab - adds the same
            // terms in a different pairing and lands on the same number, so the object would
            // never re-mesh.
            long material = parts.getCompound(part).getCompound("material").hashCode();
            hash += it.unimi.dsi.fastutil.HashCommon.mix(((long) part.hashCode() << 32) ^ (material & 0xFFFFFFFFL));
        }
        return hash;
    }

    @Override
    public long fingerprint(BlockEntity blockEntity) {
        // Typed reads, no serialisation: on a ship this runs every publish interval for
        // every copycat. Create's own copycat first because it is a class check, then
        // Copycats+ - whose multi-material interface extends its single-material one, so
        // CopycatsPlus asks about that first itself. Anything else falls back to the
        // serialising default, which is correct if slow.
        if (blockEntity instanceof CopycatBlockEntity copycat) {
            return Block.getId(copycat.getMaterial());
        }
        if (copycatsPlusLoaded() && CopycatsPlus.isCopycatEntity(blockEntity)) {
            return CopycatsPlus.fingerprint(blockEntity);
        }
        return BlockAppearanceResolver.super.fingerprint(blockEntity);
    }

    /**
     * A material compound as a block state, or the unskinned default.
     *
     * <p>{@code NbtUtils.readBlockState} yields air for a compound with no name, which is
     * what a copycat that never had a material saves as; air is not a thing to dress a box
     * in, so it becomes {@code copycat_base} like every other unskinned copycat.
     */
    static BlockState materialOf(CompoundTag tag) {
        BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag);
        return state.isAir() ? AllBlocks.COPYCAT_BASE.getDefaultState() : state;
    }

    /** A box as a piece, in the given material. */
    static BlockAppearance.Piece piece(float[] box, BlockState material) {
        return new BlockAppearance.Piece(box[0], box[1], box[2], box[3], box[4], box[5], material);
    }

    private boolean copycatsPlusLoaded() {
        Boolean loaded = copycatsPlus;
        if (loaded == null) {
            loaded = ModList.get().isLoaded("copycats");
            copycatsPlus = loaded;
        }
        return loaded;
    }
}
