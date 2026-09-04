package dev.duzo.bluemap3d.publish;

import dev.duzo.bluemap3d.bake.BakedMesh;
import dev.duzo.bluemap3d.bake.Bm3dWriter;
import de.bluecolored.bluemap.api.BlueMapAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Writes everything the browser needs into BlueMap's web root.
 *
 * <p>The web root rather than {@code AssetStorage}, for a reason worth recording:
 * {@code WebApp.registerScript} takes a URL relative to the web root, so anything the
 * webapp loads has to be reachable there anyway. The live server's own
 * {@code settings.json} confirms the shape - another mod's script is listed as
 * {@code "assets/bmopm.js"}. Publishing meshes the same way keeps it to one mechanism
 * and one write per interval, instead of fanning the same feed out across every map.
 *
 * <p>Mesh URLs carry their geometry version, so a re-bake produces a new URL and browser
 * caching stops being something anyone has to think about.
 */
public final class WebRootPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Publish");

    /** Web-root-relative directory for everything this mod writes. */
    private static final String BASE = "assets/bluemap3d";
    /** Web-root-relative URL of the injected loader. Must stay fixed; see {@link #install}. */
    public static final String SCRIPT_URL = BASE + "/bluemap3d.js";
    /** The real client script, which the loader fetches with a cache-buster. */
    private static final String CORE_SCRIPT_URL = BASE + "/bluemap3d.core.js";
    /** Web-root-relative URL of the live transform feed. */
    public static final String FEED_URL = BASE + "/entities3d.json";

    private final Path webRoot;

    private WebRootPublisher(Path webRoot) {
        this.webRoot = webRoot;
    }

    /**
     * Prepares the web root: clears any meshes left over from a previous run, copies the
     * client script out of the mod jar, and registers it with BlueMap.
     *
     * @return the publisher, or {@code null} if the web root is unusable
     */
    public static WebRootPublisher install(BlueMapAPI api) {
        Path webRoot;
        try {
            webRoot = api.getWebApp().getWebRoot();
        } catch (RuntimeException e) {
            LOGGER.error("BlueMap did not give us a web root; 3D objects are disabled", e);
            return null;
        }

        WebRootPublisher publisher = new WebRootPublisher(webRoot);
        try {
            // Meshes are rebuilt on demand, and stale ones from a previous run would
            // otherwise accumulate forever.
            publisher.deleteRecursively(webRoot.resolve(BASE).resolve("meshes"));
            Files.createDirectories(webRoot.resolve(BASE).resolve("meshes"));
            publisher.copyScript();
        } catch (IOException e) {
            LOGGER.error("Could not prepare {}; 3D objects are disabled", webRoot.resolve(BASE), e);
            return null;
        }

        // A fixed url on purpose - see bluemap3d.js, which is a loader that pulls the real
        // script in with a cache-buster. registerScript writes into a persisted Set with no
        // unregister, so a url that varied would accumulate and inject every past version.
        api.getWebApp().registerScript(SCRIPT_URL);
        LOGGER.info("Registered {} with BlueMap's webapp", SCRIPT_URL);
        return publisher;
    }

    /**
     * Writes a mesh and its atlas, and returns the web-root-relative URL of the mesh.
     *
     * @param provider the provider's id
     * @param objectId the object's id
     * @param version  the object's geometry version, which becomes part of the URL
     */
    public String writeMesh(String provider, String objectId, long version, BakedMesh mesh)
            throws IOException {
        String stemPart = stem(objectId, version);
        String meshUrl = BASE + "/meshes/" + sanitise(provider) + "/" + stemPart + ".bm3d";
        String atlasUrl = BASE + "/meshes/" + sanitise(provider) + "/" + stemPart + ".png";

        Path meshPath = resolve(meshUrl);
        Files.createDirectories(meshPath.getParent());

        if (mesh.atlas() != null) {
            Path atlasPath = resolve(atlasUrl);
            writeAtomically(atlasPath, out -> ImageIO.write(mesh.atlas(), "PNG", out));
        }
        byte[] encoded = Bm3dWriter.encode(mesh, atlasUrl);
        writeAtomically(meshPath, out -> out.write(encoded));

        return meshUrl;
    }

    /** Removes every mesh belonging to an object that no longer exists. */
    public void deleteMeshes(String provider, String objectId) {
        deleteMatching(provider, sanitise(objectId) + "-");
    }

    /**
     * Removes one superseded version of an object's mesh, leaving newer ones alone.
     *
     * <p>Called after a re-bake, once the feed has stopped pointing at the old url.
     */
    public void deleteMeshVersion(String provider, String objectId, long version) {
        deleteMatching(provider, stem(objectId, version) + ".");
    }

    private void deleteMatching(String provider, String prefix) {
        Path dir = resolve(BASE + "/meshes/" + sanitise(provider));
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            // Left behind; harmless, and the next restart clears it.
                        }
                    });
        } catch (IOException e) {
            // Nothing worth failing a tick over.
        }
    }

    /** Publishes the live transform feed. */
    public void writeFeed(String json) throws IOException {
        Path path = resolve(FEED_URL);
        Files.createDirectories(path.getParent());
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        writeAtomically(path, out -> out.write(bytes));
    }

    /**
     * Copies the client script out of the mod jar into the web root.
     *
     * <p>Done on every startup so an updated mod ships an updated script.
     */
    private void copyScript() throws IOException {
        copyResource("bluemap3d.js", resolve(SCRIPT_URL));
        copyResource("bluemap3d.core.js", resolve(CORE_SCRIPT_URL));
    }

    private static void copyResource(String name, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = WebRootPublisher.class
                .getResourceAsStream("/assets/bluemap3d/web/" + name)) {
            if (in == null) {
                throw new IOException(name + " is missing from the mod jar");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Writes via a temporary file and a move, so the webapp never reads a half-written
     * feed. The feed is rewritten every interval while browsers poll it, and without
     * this a poll landing mid-write gets truncated json.
     */
    private void writeAtomically(Path target, StreamWriter writer) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            writer.write(out);
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Some filesystems cannot do an atomic move across a rename; fall back.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path resolve(String webRelative) {
        Path path = webRoot.resolve(webRelative).normalize();
        if (!path.startsWith(webRoot.normalize())) {
            throw new IllegalArgumentException("Refusing to write outside the web root: " + webRelative);
        }
        return path;
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // Best effort.
                }
            });
        }
    }

    /**
     * Makes an id safe for a URL path segment. Object ids come from third-party
     * providers, so this is a boundary, not a formality: it stops a provider id from
     * escaping the meshes directory.
     */
    private static String sanitise(String id) {
        StringBuilder out = new StringBuilder(id.length());
        for (char c : id.toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            out.append(ok ? c : '_');
        }
        // "." and ".." would still be path traversal after the filter above.
        String result = out.toString();
        return result.equals(".") || result.equals("..") || result.isEmpty() ? "_" : result;
    }

    /**
     * The filename stem for one object at one geometry version.
     *
     * <p>The format version goes before the geometry version, and that order is
     * not cosmetic: {@link #deleteMeshVersion} matches on the geometry version followed by
     * a dot, so a format version after it would match nothing and every superseded mesh
     * and atlas would survive for the life of the server.
     *
     * <p>The format version is here at all because the browser fetches meshes with
     * {@code cache: "force-cache"} and a geometry version is a hash of the block map, so
     * the same object yields the same URL across a restart or an upgrade. Without this a
     * returning viewer would hand a v1 body to a v2 decoder.
     */
    private static String stem(String objectId, long version) {
        return sanitise(objectId) + "-v" + Bm3dWriter.VERSION + "-" + version;
    }

    @FunctionalInterface
    private interface StreamWriter {
        void write(OutputStream out) throws IOException;
    }
}
