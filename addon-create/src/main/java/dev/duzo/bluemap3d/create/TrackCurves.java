package dev.duzo.bluemap3d.create;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Enumerates curved track by walking Create's railway graph, rather than by scanning
 * chunks for track block entities.
 *
 * <p>The graph is complete regardless of chunk loading: a curve stays known even while
 * both of its ends are unloaded, which a chunk scan would lose. Chunk scanning would
 * also violate {@link dev.duzo.bluemap3d.api.SceneObjectProvider}'s contract to return
 * already-tracked state rather than scanning chunks each publish.
 *
 * <p>Discovery only - {@link CurvedTrackProvider} is what turns what is found here into
 * geometry.
 */
public final class TrackCurves {

    private TrackCurves() {
    }

    /**
     * Every curved track segment in {@code level}, one {@link BezierConnection} per curve.
     *
     * <p>A curve's two endpoints each store the connection between them, so both ends of
     * one physical curve would otherwise be counted twice; {@link BezierConnection#isPrimary()}
     * exists precisely to pick one of the two copies, so only the primary copy is kept.
     */
    public static List<BezierConnection> find(ServerLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        List<BezierConnection> curves = new ArrayList<>();

        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (TrackNodeLocation location : graph.getNodes()) {
                if (location.dimension != dimension) {
                    continue;
                }
                TrackNode node = graph.locateNode(location);
                if (node == null) {
                    continue;
                }
                for (Map.Entry<TrackNode, TrackEdge> entry : graph.getConnectionsFrom(node).entrySet()) {
                    TrackEdge edge = entry.getValue();
                    if (!edge.isTurn()) {
                        continue;
                    }
                    BezierConnection turn = edge.getTurn();
                    if (turn.isPrimary()) {
                        curves.add(turn);
                    }
                }
            }
        }
        return curves;
    }
}
