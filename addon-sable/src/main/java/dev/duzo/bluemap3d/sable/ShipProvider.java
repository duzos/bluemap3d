package dev.duzo.bluemap3d.sable;

import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

/**
 * Reports Sable ships as {@link SceneObject}s.
 *
 * <h2>Status: not implemented</h2>
 * This module exists, builds, and registers cleanly; it reports nothing. It is here so
 * that the module layout, dependency declarations and publishing config are settled while
 * the turtle addon proves the framework end to end, per the agreed build order.
 *
 * <h2>What implementing it involves</h2>
 * Sable 2.0.3 keeps a ship's blocks in a <em>sub-level</em> with its own storage
 * ({@code SubLevelStorage}, {@code SubLevelRegionFile}, {@code SubLevelHoldingChunkMap},
 * {@code SubLevelData}, {@code SubLevelSerializer}) and its own physics and tracking
 * systems ({@code SubLevelPhysicsSystem}, {@code SubLevelTrackingSystem}). Those blocks
 * are not in the overworld's chunk data at all, which is exactly why BlueMap cannot show
 * a ship today and why this is worth doing.
 *
 * <p>The work is therefore:
 * <ol>
 *   <li>enumerate live sub-levels for a {@link ServerLevel} via the tracking system;</li>
 *   <li>read each ship's bounds and hand them to
 *       {@link dev.duzo.bluemap3d.api.BlockVolume#region} with the sub-level as the block
 *       source and the ship's centre of mass as the pivot - the volume factory copies
 *       eagerly, so the resulting snapshot is safe to mesh off-thread;</li>
 *   <li>take position and orientation from the physics system each tick;</li>
 *   <li>bump {@link SceneObject#geometryVersion()} only when the ship's block set
 *       changes, so sailing costs a transform and not a re-mesh.</li>
 * </ol>
 *
 * <p>Everything in that list is already supported by core. Nothing here needs rendering
 * code, and none of it needs a new abstraction - which is the check that the seam is
 * right.
 */
public final class ShipProvider implements SceneObjectProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Sable");
    private boolean warned;

    @Override
    public String id() {
        return "sable_ships";
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        if (!warned) {
            warned = true;
            LOGGER.info("Sable ship rendering is not implemented yet; no ships will be shown");
        }
        return List.of();
    }
}
