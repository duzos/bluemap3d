package dev.duzo.bluemap3d.create;

import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

/**
 * Reports Create train carriages as {@link SceneObject}s.
 *
 * <h2>Status: not implemented</h2>
 * This module exists, builds, and registers cleanly; it reports nothing. Trains are the
 * hardest of the three addons and are deliberately last, per the agreed build order.
 *
 * <h2>What implementing it involves</h2>
 * A train is not one rigid body. It is several: an articulated set of carriages that bend
 * relative to each other on curved track, so the mapping is <b>one SceneObject per
 * carriage</b>, not per train. Each carriage is a contraption with an in-memory block map,
 * which is exactly what
 * {@link dev.duzo.bluemap3d.api.BlockVolume#of(java.util.Map, net.minecraft.world.phys.Vec3)}
 * takes.
 *
 * <p>The data access is solved prior art, twice over:
 * <ul>
 *   <li>{@code create_bluemap-1.1.1} ({@code dev.szedann.create_bluemap}; classes
 *       {@code Create_bluemap}, {@code Trains}, {@code Tracks}, {@code Watcher},
 *       {@code Config}) reads {@code com.simibubi.create.content.trains.entity.Train} off
 *       a {@code ScheduledExecutorService} timer. Reuse how it reaches the train list and
 *       replace its marker output with this provider.</li>
 *   <li>{@code E:/IdeaProjects/create-track-map} walks track graphs and reads trains,
 *       for cross-reference on topology - though it targets Fabric and 1.18/1.19, so the
 *       APIs differ.</li>
 * </ul>
 *
 * <p>The one thing needing care is {@link SceneObject#geometryVersion()}: bump it when the
 * consist changes - a carriage added, removed, or its contraption edited - and never on
 * movement. A train travelling should cost one transform per carriage per tick and no
 * meshing at all.
 */
public final class TrainProvider implements SceneObjectProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Create");
    private boolean warned;

    @Override
    public String id() {
        return "create_trains";
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        if (!warned) {
            warned = true;
            LOGGER.info("Create train rendering is not implemented yet; no trains will be shown");
        }
        return List.of();
    }
}
