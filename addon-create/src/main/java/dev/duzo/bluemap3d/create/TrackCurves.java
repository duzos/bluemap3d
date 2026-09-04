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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Create");

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
        int graphs = 0;
        int nodes = 0;
        int otherDimension = 0;
        int unresolved = 0;
        int turns = 0;
        int secondary = 0;

        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            graphs++;
            for (TrackNodeLocation location : graph.getNodes()) {
                nodes++;
                // equals, not ==: a ResourceKey compared by identity is a silent way to
                // drop every node in the level if one ever reaches us un-interned.
                if (!dimension.equals(location.dimension)) {
                    otherDimension++;
                    continue;
                }
                TrackNode node = graph.locateNode(location);
                if (node == null) {
                    unresolved++;
                    continue;
                }
                for (Map.Entry<TrackNode, TrackEdge> entry : graph.getConnectionsFrom(node).entrySet()) {
                    TrackEdge edge = entry.getValue();
                    if (!edge.isTurn()) {
                        continue;
                    }
                    turns++;
                    BezierConnection turn = edge.getTurn();
                    if (turn.isPrimary()) {
                        curves.add(turn);
                    } else {
                        secondary++;
                    }
                }
            }
        }
        if (CreateConfig.VERBOSE.get()) {
            for (BezierConnection curve : curves) {
                LOGGER.info("  curve {} -> {} (length {})", curve.bePositions.getFirst(),
                        curve.bePositions.getSecond(), String.format("%.2f", curve.getLength()));
            }
            LOGGER.info("Curve discovery in {}: {} graph(s), {} node(s), {} turn edge(s) -> "
                            + "{} curve(s) kept. Skipped: {} node(s) in another dimension, "
                            + "{} node(s) that would not resolve, {} secondary copies.",
                    dimension.location(), graphs, nodes, turns, curves.size(),
                    otherDimension, unresolved, secondary);
        }
        return curves;
    }

    /**
     * Every non-turn (straight) track edge in {@code level}, one {@link TrackEdge} per edge.
     *
     * <p>A straight edge, unlike a curve, has no {@code isPrimary()} of its own to pick one
     * copy - each end's node stores the same connection, so walking every node would count
     * one physical edge twice. {@link TrackEdge} carries no {@code equals}/{@code hashCode}
     * override, so a plain {@link HashSet} dedupes by reference identity, which is exactly
     * what is wanted: Create hands out the same {@link TrackEdge} instance from both ends.
     *
     * <p>Used by {@link CurvedTrackProvider} to find the diagonal and ascending track blocks
     * that need drawing as real geometry - see that class for why.
     */
    public static List<TrackEdge> findStraightEdges(ServerLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        List<TrackEdge> edges = new ArrayList<>();
        // Keyed on the unordered pair of endpoints, not on the edge itself. Create hands
        // out a distinct TrackEdge instance per direction and the class overrides neither
        // equals nor hashCode, so an identity set collapses nothing and every straight run
        // gets walked twice - measured as 36 edges for 18 physical runs.
        Set<String> seen = new HashSet<>();
        int duplicates = 0;

        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (TrackNodeLocation location : graph.getNodes()) {
                if (!dimension.equals(location.dimension)) {
                    continue;
                }
                TrackNode node = graph.locateNode(location);
                if (node == null) {
                    continue;
                }
                for (Map.Entry<TrackNode, TrackEdge> entry : graph.getConnectionsFrom(node).entrySet()) {
                    TrackEdge edge = entry.getValue();
                    if (edge.isTurn()) {
                        continue;
                    }
                    String a = edge.node1.getLocation().toString();
                    String b = edge.node2.getLocation().toString();
                    String pair = a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
                    if (seen.add(pair)) {
                        edges.add(edge);
                    } else {
                        duplicates++;
                    }
                }
            }
        }
        if (CreateConfig.VERBOSE.get()) {
            for (TrackEdge e : edges) {
                LOGGER.info("  straight {} -> {}", e.node1.getLocation().getLocation(),
                        e.node2.getLocation().getLocation());
            }
            LOGGER.info("Straight edge discovery in {}: {} edge(s) kept, {} reverse "
                    + "duplicate(s) collapsed.", dimension.location(), edges.size(), duplicates);
        }
        return edges;
    }
}
