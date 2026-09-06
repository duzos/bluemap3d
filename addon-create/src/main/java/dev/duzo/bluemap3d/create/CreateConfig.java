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

    /** Whether a curve is drawn in its own track material. See {@link TrackModels}. */
    public static final ModConfigSpec.BooleanValue TRACK_MATERIALS;

    /** Log what track discovery and cell building actually saw. See CurvedTrackProvider. */
    public static final ModConfigSpec.BooleanValue VERBOSE;

    /** Whether to draw the rotating bearing cap. See {@link BearingProvider}. */
    public static final ModConfigSpec.BooleanValue BEARING_CAPS;

    /** Hard ceiling on the number of bearing caps published per level. */
    public static final ModConfigSpec.IntValue MAX_BEARING_CAPS;

    /** Ceiling on how many bearing poses {@link BearingProvider} remembers per level. */
    public static final ModConfigSpec.IntValue MAX_BEARING_CACHE_ENTRIES;

    /** Whether to draw the train station flag. See {@link StationFlagProvider}. */
    public static final ModConfigSpec.BooleanValue STATION_FLAGS;

    /** Hard ceiling on the number of station flags published per level. */
    public static final ModConfigSpec.IntValue MAX_STATION_FLAGS;

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

        TRACK_MATERIALS = builder
                .comment("Draw a curve with the tie and rail models of the material it is",
                        "actually built from, rather than Create's andesite for everything.",
                        "",
                        "On by default. Matters most with a mod like Steam 'n' Rails installed,",
                        "where a rail network can be built from any of a hundred and fifty",
                        "materials and every curve on the map would otherwise come out andesite.",
                        "Costs one blockstate read per material, once, and nothing after that.",
                        "Turn it off to go back to drawing every curve as andesite.")
                .define("trackMaterials", true);

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

        builder.comment("A mechanical, windmill or clockwork bearing's rotating top face is",
                        "drawn entirely by Create's own renderer - the block model BlueMap reads",
                        "stops at the twelve pixel base - so without this every bearing on the map",
                        "is missing its cap.")
                .push("bearings");

        BEARING_CAPS = builder
                .comment("Draw the bearing cap, turning at the rate it actually turns in game.",
                        "",
                        "On by default. Purely additive - the cap is geometry BlueMap does not",
                        "draw today, so there is nothing to hide and nothing to double-draw.",
                        "A stationary bearing (unpowered, unassembled, or stalled) draws its cap",
                        "stationary too; this reads the same interpolated-angle gate Create's own",
                        "renderer does rather than a speed value, so it cannot get that wrong.")
                .define("bearingCaps", true);

        MAX_BEARING_CAPS = builder
                .comment("Refuse to publish more than this many bearing caps for one level.",
                        "",
                        "Hit once and logged once, rather than degrading silently: the excess",
                        "bearings are simply left without a cap until the count drops back down",
                        "or this is raised.")
                .defineInRange("maxBearingCaps", 256, 1, 100_000);

        MAX_BEARING_CACHE_ENTRIES = builder
                .comment("A bearing in an unloaded chunk does not tick, so it cannot be scanned",
                        "for there - this addon instead remembers its last known pose and keeps",
                        "publishing that, unchanged, until its chunk loads again. This bounds how",
                        "many of those remembered poses are kept at once; the least recently seen",
                        "ones are dropped first once the ceiling is hit. A restart rebuilds the",
                        "cache from whatever actually loads, so this is a memory ceiling, not a",
                        "correctness knob.")
                .defineInRange("maxBearingCacheEntries", 4096, 1, 1_000_000);

        builder.pop();

        builder.comment("A train station's flag - the pole and its raised or lowered",
                        "indicator - is drawn entirely by Create's own renderer and referenced",
                        "by no blockstate, so without this every station on the map is a bare",
                        "plinth.")
                .push("stationFlags");

        STATION_FLAGS = builder
                .comment("Draw the station flag, raised or lowered and textured for whether a",
                        "train is present, absent, or the station is mid-assembly.",
                        "",
                        "On by default. Purely additive, same reasoning as bearingCaps above.",
                        "The flag's yaw needs a loaded chunk to resolve once; until that has",
                        "happened for a given station (since the server started) it is drawn",
                        "with no flag at all rather than one at a guessed orientation.")
                .define("stationFlags", true);

        MAX_STATION_FLAGS = builder
                .comment("Refuse to publish more than this many station flags for one level.",
                        "",
                        "Hit once and logged once, rather than degrading silently: the excess",
                        "stations are simply left without a flag until the count drops back",
                        "down or this is raised.")
                .defineInRange("maxStationFlags", 256, 1, 100_000);

        builder.pop();

        SPEC = builder.build();
    }

    private CreateConfig() {
    }
}
