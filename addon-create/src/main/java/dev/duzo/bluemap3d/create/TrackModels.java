package dev.duzo.bluemap3d.create;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tie and rail models a curve of a given {@link TrackMaterial} should be drawn with.
 *
 * <h2>Why this is derived rather than listed</h2>
 * {@link CurvedTrackProvider} used to name Create's three andesite models outright, so
 * every curve on the map was andesite no matter what it was built from. Steam 'n' Rails
 * alone adds over a hundred and fifty materials (one per wood, times standard, narrow and
 * wide gauge, plus other mods' woods), and more mods add more, so a hand-written table
 * would be both enormous and permanently out of date.
 *
 * <p>There is no need for one. Every track material already ships a per-material
 * {@code tie}, {@code segment_left} and {@code segment_right} model next to the block
 * models its blockstate points at - that is how the material's own renderer draws its
 * curves in game - so the material's blockstate is enough to find them: take the model
 * the {@code shape=zo} (straight, north-south) variant names, drop the file name, and the
 * three curve models sit in the directory that is left. Create's own
 * {@code create:block/track/z_ortho} yields {@code create:block/track}, which is exactly
 * the three models that used to be hardcoded; Steam 'n' Rails' {@code track_acacia_wide}
 * yields {@code railways:block/track/acacia_wide}, and so on for every material any mod
 * registers, with nothing here to keep in step.
 *
 * <p>{@link TrackMaterial#getModelHolder()} looks like the direct answer and is not: it
 * holds flywheel {@code PartialModel}s, which are client-only, so a dedicated server
 * cannot load the class at all.
 *
 * <h2>No dependency on Steam 'n' Rails</h2>
 * Nothing here names a Steam 'n' Rails class. The material comes from Create's own
 * {@link TrackMaterial}, and everything else is a resource path read as a string, so this
 * behaves identically for a material from any other mod and does nothing at all when no
 * such mod is installed.
 *
 * <h2>Reading the blockstate</h2>
 * Blockstates and models live in mod jars, which are the same jar on client and server,
 * so the mod class loader can read them - the same route
 * {@code dev.duzo.bluemap3d.bake.AssetIndex} takes for the models this addon then asks
 * core to draw. It is deliberately only the class loader and not core's full index: a
 * resource pack may override a blockstate for BlueMap's terrain, but a track material's
 * curve models are its mod's own and a pack that moved them would be replacing the mod's
 * geometry outright.
 *
 * @param tie       the sleeper drawn at every step along a curve
 * @param leftRail  the rail piece bridging one step to the next, gauge-width to the left
 * @param rightRail the same on the right
 */
public record TrackModels(ResourceLocation tie, ResourceLocation leftRail, ResourceLocation rightRail) {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Create");
    private static final Gson GSON = new Gson();

    /**
     * Create's andesite curve models, used for andesite and as the fallback whenever a
     * material's own three cannot be found. Also what the whole feature falls back to when
     * {@link CreateConfig#TRACK_MATERIALS} is off.
     */
    public static final TrackModels ANDESITE = new TrackModels(
            ResourceLocation.fromNamespaceAndPath("create", "block/track/tie"),
            ResourceLocation.fromNamespaceAndPath("create", "block/track/segment_left"),
            ResourceLocation.fromNamespaceAndPath("create", "block/track/segment_right"));

    /**
     * Model directories whose {@code tie}/{@code segment_*} models are not curve-step
     * pieces, and so must not be used even though the derivation above finds them.
     *
     * <p>Steam 'n' Rails' monorail is the one case: it is a single overhead girder rather
     * than a pair of rails on sleepers, its curves are drawn from girder segments, and the
     * {@code tie} sitting in that directory is a three-block-long straight-track piece.
     * Repeating that every half block along a curve would draw a solid ribbon three blocks
     * wide - visibly worse than the andesite fallback, which at least has the right shape.
     * Drawing a real monorail curve needs girder geometry this file does not model.
     */
    private static final java.util.Set<String> NOT_CURVE_MODELS = java.util.Set.of(
            "railways:block/monorail/monorail/static_blocks");

    /** Keyed on the material id, which is stable and cheap; values are never null. */
    private static final Map<ResourceLocation, TrackModels> CACHE = new ConcurrentHashMap<>();

    /** Logged once per material that falls back, so a missing set is diagnosable but not spammy. */
    private static final java.util.Set<ResourceLocation> FALLBACK_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The three models to draw a curve of {@code material} with, never null - a material
     * whose own models cannot be found falls back to {@link #ANDESITE}, which is what this
     * addon drew before it could tell materials apart.
     */
    public static TrackModels forMaterial(TrackMaterial material) {
        if (material == null || !CreateConfig.TRACK_MATERIALS.get()) {
            return ANDESITE;
        }
        return CACHE.computeIfAbsent(material.id, id -> resolve(material, id));
    }

    private static TrackModels resolve(TrackMaterial material, ResourceLocation id) {
        String directory = modelDirectoryOf(material);
        if (directory == null || NOT_CURVE_MODELS.contains(directory)) {
            return fallback(id, directory == null ? "no straight-track model" : "not curve geometry");
        }
        ResourceLocation tie = parse(directory + "/tie");
        ResourceLocation left = parse(directory + "/segment_left");
        ResourceLocation right = parse(directory + "/segment_right");
        if (tie == null || left == null || right == null
                || !exists(tie) || !exists(left) || !exists(right)) {
            return fallback(id, "incomplete model set in " + directory);
        }
        if (CreateConfig.VERBOSE.get()) {
            LOGGER.info("Track material {} draws curves from {}", id, directory);
        }
        return new TrackModels(tie, left, right);
    }

    private static TrackModels fallback(ResourceLocation id, String why) {
        if (FALLBACK_LOGGED.add(id)) {
            LOGGER.info("Track material {} has no curve models of its own ({}); its curves are "
                    + "drawn as andesite.", id, why);
        }
        return ANDESITE;
    }

    /**
     * The resource directory holding a material's models, e.g. {@code railways:block/track/acacia},
     * or {@code null} if it cannot be worked out.
     *
     * <p>Found through the material's own block, because a material id does not predict its
     * block id: Create's {@code create:andesite} is the block {@code create:track} while
     * Steam 'n' Rails' {@code railways:acacia} is {@code railways:track_acacia}.
     *
     * <p>{@code shape=zo} is the variant to read rather than any other because it is the
     * plain straight piece every material has, it is drawn from a model in the material's
     * own directory, and - unlike {@code shape=none} - it is never mapped to
     * {@code minecraft:block/air}.
     */
    private static String modelDirectoryOf(TrackMaterial material) {
        Block block;
        try {
            block = material.getBlock();
        } catch (RuntimeException e) {
            // A material registered for a mod that is not installed keeps a supplier that
            // never resolves. Nothing to draw for it either, so this is not worth a warning.
            return null;
        }
        if (block == null) {
            return null;
        }
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
        JsonObject blockstate = readJson(
                "assets/" + blockId.getNamespace() + "/blockstates/" + blockId.getPath() + ".json");
        if (blockstate == null || !blockstate.has("variants")) {
            return null;
        }
        String model = null;
        for (Map.Entry<String, JsonElement> entry : blockstate.getAsJsonObject("variants").entrySet()) {
            // Substring rather than an exact key match: the key also carries turn and
            // waterlogged, whose order and presence are the blockstate author's business.
            if (!entry.getKey().contains("shape=zo")) {
                continue;
            }
            JsonElement value = entry.getValue();
            JsonObject variant = value.isJsonArray()
                    ? (value.getAsJsonArray().isEmpty() ? null : value.getAsJsonArray().get(0).getAsJsonObject())
                    : value.getAsJsonObject();
            if (variant != null && variant.has("model")) {
                model = variant.get("model").getAsString();
                break;
            }
        }
        if (model == null) {
            return null;
        }
        int slash = model.lastIndexOf('/');
        return slash < 0 ? null : model.substring(0, slash);
    }

    private static boolean exists(ResourceLocation model) {
        return TrackModels.class.getClassLoader().getResource(
                "assets/" + model.getNamespace() + "/models/" + model.getPath() + ".json") != null;
    }

    private static JsonObject readJson(String path) {
        try (InputStream in = TrackModels.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            LOGGER.debug("Could not read {}: {}", path, e.toString());
            return null;
        }
    }

    private static ResourceLocation parse(String id) {
        return ResourceLocation.tryParse(id);
    }
}
