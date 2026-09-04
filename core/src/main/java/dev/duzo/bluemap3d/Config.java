package dev.duzo.bluemap3d;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Core's configuration. Everything here is server-side.
 */
public final class Config {

    public static final ModConfigSpec SPEC;

    /** How often the live transform feed is republished, in ticks. */
    public static final ModConfigSpec.IntValue PUBLISH_INTERVAL_TICKS;

    /** Hard ceiling on the blocks in one object, so one absurd ship cannot stall a tick. */
    public static final ModConfigSpec.IntValue MAX_BLOCKS_PER_OBJECT;

    /** Ceiling on the independently turning parts one object may have. */
    public static final ModConfigSpec.IntValue MAX_SPIN_NODES_PER_OBJECT;

    /** Hard ceiling on the attachments in one object, for the volumes maxBlocksPerObject cannot see. */
    public static final ModConfigSpec.IntValue MAX_ATTACHMENTS_PER_OBJECT;

    /** Minimum seconds between forcing a viewer's browser to re-download terrain tiles. */
    public static final ModConfigSpec.IntValue TILE_RELOAD_MIN_SECONDS;

    /** Whether to read real block models and textures rather than only map colours. */
    public static final ModConfigSpec.BooleanValue USE_RESOURCE_PACKS;

    /** Best-effort attempt to hide live-drawn blocks from BlueMap's tiles. See the comment. */
    public static final ModConfigSpec.BooleanValue HIDE_LIVE_BLOCKS;

    /** Draw unmodelled blocks from their voxel shape rather than as a coloured cube. */
    public static final ModConfigSpec.BooleanValue SHAPE_FALLBACK;

    /**
     * Extra jars, zips or directories to search for block models and textures, in
     * priority order.
     */
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> ASSET_SOURCES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("BlueMap3D renders real 3D geometry inside BlueMap's three.js scene.",
                        "With no addons installed this mod does nothing at all.")
                .push("general");

        PUBLISH_INTERVAL_TICKS = builder
                .comment("How often to republish object positions, in ticks (20 = 1s).",
                        "The browser interpolates between samples, so a larger value costs",
                        "responsiveness but not smoothness. Below ~5 the bandwidth stops",
                        "buying you anything the interpolation was not already giving you.",
                        "",
                        "Do not set this slower than the things you are tracking turn. Two 90",
                        "degree turns inside one sample look like a single 180 degree turn,",
                        "and a 180 degree rotation has no shorter way round - so it resolves",
                        "the same arbitrary direction every time and reads as the object",
                        "spinning the long way. Sampling faster than the object turns is the",
                        "only real fix; interpolation cannot recover what was not sampled.")
                .defineInRange("publishIntervalTicks", 10, 1, 600);

        MAX_BLOCKS_PER_OBJECT = builder
                .comment("Refuse to mesh any single object larger than this many blocks.")
                .defineInRange("maxBlocksPerObject", 20000, 1, 1_000_000);

        MAX_SPIN_NODES_PER_OBJECT = builder
                .comment("Ceiling on the number of independently turning parts one object",
                        "may have. Each one is an extra draw call in every viewer's browser.",
                        "",
                        "Going over the limit does not lose geometry: the extra parts are",
                        "drawn in place like any other attachment, they simply do not turn.")
                .defineInRange("maxSpinNodesPerObject", 32, 0, 1024);

        MAX_ATTACHMENTS_PER_OBJECT = builder
                .comment("Refuse to mesh any single object with more than this many",
                        "attachments. maxBlocksPerObject cannot catch an attachments-only",
                        "volume - a block count of zero clears it no matter how much",
                        "attachment geometry rides along - so this is the only ceiling an",
                        "object made purely of model pieces ever has to pass.")
                .defineInRange("maxAttachmentsPerObject", 4096, 1, 100_000);

        TILE_RELOAD_MIN_SECONDS = builder
                .comment("Minimum seconds between telling a viewer's browser to re-download",
                        "terrain tiles that changed. Negative disables it entirely.",
                        "",
                        "DISABLED by default (-1): opt-in only. It makes viewers re-download",
                        "tiles without asking, so it should be a decision rather than something",
                        "a server ends up doing because it was the default. Set it to 5 or so",
                        "to turn it on.",
                        "",
                        "What it buys: BlueMap's webapp never revalidates tiles by itself - its",
                        "update loop only follows the player marker, and tile urls carry a cache",
                        "hash fixed for the session. So without this, a viewer who leaves the",
                        "page open sees terrain frozen as it was when they opened it, however",
                        "often the server re-renders. A turtle's tunnel never appears.",
                        "",
                        "Only the tiles the server says changed are replaced, and only hires",
                        "ones, so the cost is a couple of tiles rather than the whole view.")
                .defineInRange("tileReloadMinSeconds", -1, -1, 3600);

        builder.pop();

        builder.comment("Where block models and textures come from.",
                        "A dedicated server has no client assets, so without a source here",
                        "every block falls back to a solid cube in its map colour. That still",
                        "gives correct 3D shapes, just untextured.")
                .push("assets");

        USE_RESOURCE_PACKS = builder
                .comment("Read real block models and textures where they can be found.")
                .define("useResourcePacks", true);

        ASSET_SOURCES = builder
                .comment("Extra jars, zips or directories to search, highest priority first.",
                        "Paths are relative to the server directory. Mod jars are searched",
                        "automatically; this is for the vanilla client jar and resource packs.",
                        "BlueMap's own resourcepacks directory is picked up automatically too.",
                        "Example: [\"resourcepacks/Faithful.zip\", \"client-1.21.1.jar\"]")
                .defineList("sources", java.util.List.of(), () -> "", o -> o instanceof String);

            SHAPE_FALLBACK = builder
                .comment("EXPERIMENTAL. Before giving up on a block and drawing it as a",
                        "map-colour cube, build it out of its voxel outline shape textured",
                        "with its particle sprite.",
                        "",
                        "Only ever applies to blocks whose model has no geometry, which is",
                        "the handful a mod draws entirely in code - Create's belt is the",
                        "known one. Those become the right silhouette in the right texture",
                        "instead of a coloured lump: a belt reads as a slab, a modded chest",
                        "as a 14x14x14 box in its own wood.",
                        "",
                        "It is an approximation and says so - the blocks it draws are still",
                        "named in the 'no model found' line at the end of a bake, because a",
                        "real model supplied through 'sources' is still better.")
                .define("shapeFallback", true);

        HIDE_LIVE_BLOCKS = builder
                .comment("Hide blocks that a provider draws live from BlueMap's own terrain",
                        "tiles, by writing a resource pack into BlueMap's packs folder that",
                        "maps them to an empty model.",
                        "",
                        "Without this you see those objects twice: the live one that moves, and",
                        "a copy baked into the terrain wherever it was when that tile was last",
                        "rendered - and the baked one looks more convincing, because BlueMap's",
                        "terrain shader lit it.",
                        "",
                        "Only matters for objects whose blocks really exist in world chunks - a",
                        "turtle. Sable ships and Create carriages keep their blocks outside",
                        "world chunks, which is why BlueMap cannot draw them at all and why",
                        "they never double up.",
                        "",
                        "Turning this off after it has been on leaves the pack behind until the",
                        "next start, and already-rendered tiles keep whatever they were",
                        "rendered with until they are re-rendered ('/bluemap purge').")
                .define("hideLiveBlocksFromTiles", true);

        builder.pop();

        SPEC = builder.build();
    }

    private Config() {
    }
}
