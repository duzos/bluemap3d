package dev.duzo.bluemap3d.publish;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Generates a BlueMap resource pack that renders chosen blocks as nothing.
 *
 * <p>Solves double-drawing. A turtle is a real block in a real chunk, so BlueMap bakes it
 * into a terrain tile like anything else. BlueMap3D then draws the same turtle live. You end
 * up with two: one that moves, and one frozen wherever the turtle was when that tile was
 * last rendered - and the frozen one is the more convincing of the pair, because it is
 * lit and shaded by BlueMap's own terrain shader.
 *
 * <p>BlueMap has no "hide this block" setting, but it does apply resource packs over vanilla
 * and mod assets. So the fix is a pack that overrides those blocks' blockstates to point at a
 * model with no geometry. A {@code multipart} with a single unconditional part is used rather
 * than {@code variants}, because it matches every block state without having to enumerate
 * the block's properties.
 *
 * <h2>Which directory</h2>
 * BlueMap has two roots and they are easy to confuse. The <b>data</b> directory holds
 * {@code web/}, the downloaded client jar and {@code resourceExtensions.zip}, and is what
 * {@code getWebApp().getWebRoot().getParent()} gives you. The <b>config</b> directory holds
 * {@code maps/}, {@code storages/}, {@code plugin.conf} - and {@code packs/}, which is what
 * {@code getPacksFolder()} returns.
 *
 * <p>The pack has to go in the config one. Writing it to the data directory produces a
 * folder BlueMap never reads, and the symptom is silence: no error, no warning, the pack
 * simply has no effect. That cost a long detour, including a wrong conclusion that BlueMap
 * ignores pack overrides altogether.
 *
 * <h2>Timing, and already-rendered tiles</h2>
 * BlueMap reads its packs when it starts, and it starts before it hands out its API - so
 * this is written from {@code ServerAboutToStartEvent} rather than from the API callback,
 * which would always be one BlueMap start too late. The pack is only rewritten when its
 * content changes, so the reload warning fires once rather than every boot.
 *
 * <p>Tiles already rendered keep the old geometry until something re-renders them.
 * {@code /bluemap purge} then a fresh render clears it; otherwise
 * {@link dev.duzo.bluemap3d.api.BlueMap3D#refreshArea} handles it as the world changes.
 */
public final class HiddenBlockPack {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/HiddenBlocks");

    /**
     * Directory name of the generated pack.
     *
     * <p>Core's own {@link dev.duzo.bluemap3d.bake.AssetIndex} must skip it: it reads the
     * same packs to find block models, and would otherwise pick up these empty models and
     * mesh the very objects the pack exists to hide.
     */
    public static final String PACK_NAME = "bluemap3d-hidden";

    /**
     * BlueMap 5 keeps packs in {@code packs} (its {@code getPacksFolder()}); earlier
     * layouts used {@code resourcepacks}.
     */
    private static final List<String> PACK_DIRS = List.of("packs", "resourcepacks");

    /**
     * Written as a zip, not a directory. A directory of the same content is silently
     * ignored - BlueMap reloads without complaint and without applying it, and it does not
     * even report a deliberately malformed json inside one. The zip is picked up: the
     * difference is visible in BlueMap's own log, which only re-runs its "Loading
     * textures..." step when the resource set actually changed.
     */
    private static final String PACK_FILE = PACK_NAME + ".zip";

    private static final String EMPTY_MODEL = "{\"textures\":{},\"elements\":[]}\n";

    private HiddenBlockPack() {
    }

    /**
     * Writes or removes the pack.
     *
     * @param candidateRoots BlueMap directories to try, most likely first. The config
     *                       directory is the one that matters; the others are covered in
     *                       case a platform lays things out differently, but only if they
     *                       already contain a packs folder, so no stray directories are
     *                       created next to a layout we guessed wrong about.
     * @param blocks         block ids to hide; empty removes the pack
     * @return whether anything changed, meaning BlueMap needs a reload to pick it up
     */
    public static boolean write(Collection<Path> candidateRoots, Collection<ResourceLocation> blocks) {
        boolean changed = false;
        boolean first = true;
        for (Path root : candidateRoots) {
            for (String dirName : PACK_DIRS) {
                Path parent = root.resolve(dirName);
                boolean primary = first && dirName.equals(PACK_DIRS.get(0));
                if (!Files.isDirectory(parent) && !primary) {
                    continue;
                }
                try {
                    Files.createDirectories(parent);
                    // Clean up the directory form written by earlier versions, which
                    // BlueMap never read.
                    Path legacy = parent.resolve(PACK_NAME);
                    if (Files.isDirectory(legacy)) {
                        deleteRecursively(legacy);
                    }
                    changed |= writeZip(parent.resolve(PACK_FILE), blocks);
                } catch (IOException e) {
                    LOGGER.warn("Could not write {}: {}", parent.resolve(PACK_FILE), e.toString());
                }
            }
            first = false;
        }

        if (changed && !blocks.isEmpty()) {
            // Deliberately not "run /bluemap reload": this is written before BlueMap starts,
            // so it is already in effect. Only tiles rendered earlier are stale, and saying
            // otherwise sends people chasing a reload that changes nothing.
            LOGGER.info("Hiding {} block type(s) from BlueMap's tiles, so they are not drawn "
                            + "twice: {}. Tiles rendered before now keep the old geometry until "
                            + "they are re-rendered ('/bluemap purge' clears them all).",
                    blocks.size(), blocks);
        }
        return changed;
    }

    private static boolean writeZip(Path packFile, Collection<ResourceLocation> blocks) throws IOException {
        if (blocks.isEmpty()) {
            return Files.deleteIfExists(packFile);
        }

        // The pack is fully determined by the block list, so the list doubles as a version
        // marker. Comparing it first means an unchanged pack is not rewritten, which is what
        // keeps the "reload BlueMap" warning from firing on every single boot.
        String marker = blocks.stream()
                .map(ResourceLocation::toString)
                .sorted()
                .reduce("", (a, b) -> a + b + "\n");
        if (Files.isRegularFile(packFile) && marker.equals(readMarker(packFile))) {
            return false;
        }

        Files.createDirectories(packFile.getParent());
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(packFile))) {
            entry(zip, "pack.mcmeta", """
                    {
                        "pack": {
                            "description": "BlueMap3D: blocks drawn live, hidden from tiles",
                            "pack_format": 34
                        }
                    }
                    """);
            entry(zip, MARKER_ENTRY, marker);
            entry(zip, "assets/bluemap3d/models/block/empty.json", EMPTY_MODEL);

            for (ResourceLocation block : blocks) {
                // multipart with no `when` applies to every state, so this does not have to
                // enumerate the block's properties.
                entry(zip, "assets/" + block.getNamespace() + "/blockstates/"
                                + block.getPath() + ".json",
                        "{\"multipart\":[{\"apply\":{\"model\":\"bluemap3d:block/empty\"}}]}\n");
            }
        }
        return true;
    }

    private static final String MARKER_ENTRY = "bluemap3d-hidden-blocks.txt";

    private static void entry(java.util.zip.ZipOutputStream zip, String name, String content)
            throws IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** The block list a previously written pack was generated from, or {@code null}. */
    private static String readMarker(Path packFile) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(packFile.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry(MARKER_ENTRY);
            if (entry == null) {
                return null;
            }
            try (var in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // Best effort.
                }
            });
        }
    }
}
