package dev.duzo.bluemap3d.create;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * This addon's own configuration, separate from core's.
 */
public final class CreateConfig {

    public static final ModConfigSpec SPEC;

    /** Whether to draw curved track. See {@link CurvedTrackProvider}. */
    public static final ModConfigSpec.BooleanValue CURVED_TRACK;

    /** Side length, in blocks, of the grid curves are merged into before publishing. */
    public static final ModConfigSpec.IntValue CURVE_GRID_SIZE;

    /** Hard ceiling on the number of curve grid cells published per level. */
    public static final ModConfigSpec.IntValue MAX_CURVE_OBJECTS;

    /** Log what track discovery and cell building actually saw. See CurvedTrackProvider. */
    public static final ModConfigSpec.BooleanValue VERBOSE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Curved Create track has no blocks of its own - Create draws it from a",
                        "bezier at render time - so without this it is simply invisible on the map.")
                .push("curvedTrack");

        CURVED_TRACK = builder
                .comment("Draw curved track as real geometry.",
                        "",
                        "On by default. Nobody has measured what this costs on a server with a",
                        "large rail network yet, so turn it off if track publishing shows up as a",
                        "cost worth avoiding.")
                .define("curvedTrack", true);

        CURVE_GRID_SIZE = builder
                .comment("Curves are merged into square grid cells this many blocks on a side",
                        "before publishing, one BlueMap3D object per cell rather than one per",
                        "curve. Every published object costs its own mesh file, atlas image,",
                        "HTTP fetch and draw call, and a chunk-sized cell would mean hundreds of",
                        "them for a modest rail yard. A track cluster is rarely wider than this,",
                        "so a cell usually ends up holding a handful of curves.")
                .defineInRange("gridSize", 128, 16, 4096);

        MAX_CURVE_OBJECTS = builder
                .comment("Refuse to publish more than this many curve grid cells for one level.",
                        "",
                        "Hit once and logged once, rather than degrading silently: the excess",
                        "cells are simply left off the map until the count drops back down or",
                        "this is raised.")
                .defineInRange("maxCurveObjects", 128, 1, 100_000);

        VERBOSE = builder
                .comment("Log, once per level, exactly what track discovery found and what each",
                        "grid cell was built from: nodes walked, curves kept and skipped, blocks",
                        "collected, attachments emitted.",
                        "",
                        "Track that renders with holes in it is almost always something being",
                        "dropped quietly upstream of the geometry, and without this there is no",
                        "way to tell which stage dropped it.")
                .define("verboseTrackLogging", false);

        builder.pop();

        SPEC = builder.build();
    }

    private CreateConfig() {
    }
}
