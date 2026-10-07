package dev.duzo.bluemap3d.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Teaches core how to draw a family of blocks whose look lives in their block entity.
 *
 * <p>Register one with {@link BlueMap3D#registerAppearanceResolver}. Core consults it
 * wherever a {@link BlockVolume} is built from block entity data it can reach: a
 * {@link BlockVolume#region} read out of a level, and any provider that passes its own
 * {@link BlockVolume#of(java.util.Map, net.minecraft.world.phys.Vec3,
 * java.util.Collection, java.util.Map) appearances} - a Create contraption carries its
 * block entities as plain tags, with no level to ask, which is why the tag-based methods
 * exist alongside the live ones.
 *
 * <p>The first registered resolver whose {@link #handles} is true for a state wins, so a
 * resolver should claim only blocks it can fully describe. Anything it throws is caught,
 * logged once per resolver class, and treated as "no appearance", which falls back to
 * whatever the block's own model gave.
 *
 * <h2>Version fingerprints</h2>
 * An appearance changes the geometry without changing the block state, so a provider that
 * caches meshes against {@link SceneObject#geometryVersion()} has to fold it in or a
 * re-skinned block will never be re-meshed. {@link #fingerprint} is that number: a stable,
 * content-based hash of exactly the data that affects the appearance. It is called every
 * publish interval for every handled block, so it must be far cheaper than
 * {@link #resolve}. A typed override of {@link #fingerprint(BlockEntity)} should not serialise.
 */
public interface BlockAppearanceResolver {

    /** Whether this resolver draws the given block. Called for every block meshed; keep it cheap. */
    boolean handles(BlockState state);

    /**
     * The appearance of a handled block.
     *
     * @param state          the block state, for which {@link #handles} returned true
     * @param blockEntityTag the block entity's saved data, or an empty tag if it has none
     * @return the appearance, or {@code null} to fall back to the block's own model
     */
    @Nullable
    BlockAppearance resolve(BlockState state, CompoundTag blockEntityTag);

    /**
     * {@link #resolve(BlockState, CompoundTag)} for a live block entity.
     *
     * <p>The default saves the block entity and resolves that, which is right for any
     * appearance that is entirely in saved data. Override it for one that is not: a spinning
     * copycat cogwheel's speed is live state a saved tag does not carry reliably, and a
     * resolver is the one place that knows to read it from the block entity directly. Returns
     * {@code null}, falling back to the block's own model, if the block entity has no level to
     * save against.
     *
     * <p>This is what {@link BlockVolume#region} calls; a provider that holds its block
     * entities as tags - a contraption - can only reach the tag form, and so never gets a
     * {@link BlockAppearance#spin() spin}. That is deliberate: a contraption's kinetics are
     * frozen, so there is nothing to turn.
     */
    @Nullable
    default BlockAppearance resolve(BlockState state, BlockEntity blockEntity) {
        if (blockEntity.getLevel() == null) {
            return null;
        }
        return resolve(state, blockEntity.saveWithoutMetadata(blockEntity.getLevel().registryAccess()));
    }

    /**
     * A stable hash of the parts of a block entity tag that affect {@link #resolve}.
     *
     * <p>Content-based, and only needed to be stable for the life of the process: the same
     * data must give the same number on every call, or a mesh is re-baked every interval.
     * Nothing persists it, so a typed override may use registry ids that differ between runs.
     */
    long fingerprint(CompoundTag blockEntityTag);

    /**
     * {@link #fingerprint(CompoundTag)} for a live block entity.
     *
     * <p>The default serialises the block entity and hashes that, which is correct for
     * any resolver but costs a full save. It is run every publish interval for every
     * handled block entity on a ship, so a resolver that can read its data through a typed
     * accessor should override this and skip the serialisation.
     */
    default long fingerprint(BlockEntity blockEntity) {
        if (blockEntity.getLevel() == null) {
            return 0L;
        }
        return fingerprint(blockEntity.saveWithoutMetadata(blockEntity.getLevel().registryAccess()));
    }
}
