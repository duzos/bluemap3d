package dev.duzo.bluemap3d.bake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an OBJ mesh, plus its MTL material file, into the same {@link ModelQuad} list a
 * JSON element model produces.
 *
 * <p>Some mods ship OBJ meshes rather than element models for their less box-shaped
 * parts. This class only knows OBJ and MTL syntax: it does not resolve resource packs,
 * it does not walk a parent chain, and it does not know what a block is - that is
 * {@link ResourcePackSource}'s job, and it hands this class an already-resolved texture
 * map so there is only one place that understands {@code #ref} indirection.
 *
 * <p>Every face becomes a quad. Some OBJ exporters emit triangulated meshes only, so a
 * triangle is emitted as a degenerate quad with its last vertex repeated - the smallest
 * change that lets it flow through {@link MeshBuilder#quad}, and a zero-area fourth edge
 * costs nothing on the GPU. A face with more than three vertices is truncated to its
 * first three rather than fanned into multiple quads, since that is the only shape this
 * class has ever had to draw; see the {@code warnedNGon} log below. Every quad gets a
 * {@code null} cull face and a {@code null} shade face: an OBJ mesh has no notion of
 * sitting flush against a neighbouring block to cull against, and no single nominal
 * facing to shade by, so it is drawn at full brightness and never culled - the same
 * treatment {@code emitAttachment} already gives every attachment.
 */
final class ObjModelReader {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Models");

    private ObjModelReader() {
    }

    /**
     * @param objText  the {@code .obj} file, verbatim
     * @param mtlText  the {@code .mtl} file named by the obj's {@code mtllib}, or
     *                 {@code null} if it could not be read
     * @param textures the model's texture variables, already fully resolved to sprite
     *                 ids with no {@code #ref} left, keyed without the leading {@code #}
     * @param flipV    whether v must be flipped ({@code v = 1 - v}) before use, mirroring
     *                 the wrapping model's {@code flip_v} flag
     * @return the parsed quads, or as many as could be parsed before trouble; never null,
     *         never throws
     */
    static List<ModelQuad> read(String objText, String mtlText, Map<String, String> textures, boolean flipV) {
        Map<String, String> materialTexture = resolveMaterialTextures(mtlText, textures);

        List<float[]> positions = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        List<ModelQuad> out = new ArrayList<>();

        String currentMaterial = null;
        // Each kind of trouble is logged once per read, not once per line - a bad model
        // has usually made the same mistake on every one of its faces.
        boolean warnedIndex = false;
        boolean warnedMaterial = false;
        boolean warnedNGon = false;

        for (String rawLine : objText.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] tokens = line.split("\\s+");
            switch (tokens[0]) {
                case "v" -> {
                    // Block units in, model-space units out: core's quads live in 0..16
                    // per block, obj coordinates are in whole blocks.
                    if (tokens.length >= 4) {
                        try {
                            positions.add(new float[]{
                                    Float.parseFloat(tokens[1]) * 16f,
                                    Float.parseFloat(tokens[2]) * 16f,
                                    Float.parseFloat(tokens[3]) * 16f});
                        } catch (NumberFormatException e) {
                            LOGGER.debug("Unparsable obj vertex line: {}", line);
                        }
                    }
                }
                case "vt" -> {
                    if (tokens.length >= 3) {
                        try {
                            float u = Float.parseFloat(tokens[1]);
                            float v = Float.parseFloat(tokens[2]);
                            if (flipV) {
                                v = 1f - v;
                            }
                            uvs.add(new float[]{u * 16f, v * 16f});
                        } catch (NumberFormatException e) {
                            LOGGER.debug("Unparsable obj texcoord line: {}", line);
                        }
                    }
                }
                case "usemtl" -> currentMaterial = tokens.length > 1 ? tokens[1].trim() : null;
                case "f" -> {
                    if (tokens.length < 4) {
                        LOGGER.debug("Skipping obj face with fewer than three vertices: {}", line);
                        continue;
                    }
                    String texture = materialTexture.get(currentMaterial);
                    if (texture == null) {
                        if (!warnedMaterial) {
                            LOGGER.debug("Obj material {} has no usable texture; skipping its faces", currentMaterial);
                            warnedMaterial = true;
                        }
                        continue;
                    }

                    // A face may be a triangle, a quad or an n-gon, and all three turn
                    // up in practice: meshes exported triangulated are all triangles,
                    // while ones exported as-modelled are mostly quads. Read every vertex
                    // and fan the face from its first corner, so nothing is dropped.
                    int corners0 = tokens.length - 1;
                    float[][] pos = new float[corners0][];
                    float[][] uv = new float[corners0][];
                    boolean ok = corners0 >= 3;
                    if (!ok && !warnedNGon) {
                        LOGGER.debug("Obj face has fewer than three vertices: {}", line);
                        warnedNGon = true;
                    }
                    for (int i = 0; ok && i < corners0; i++) {
                        int[] indices = parseFaceVertex(tokens[i + 1]);
                        if (indices == null) {
                            LOGGER.debug("Unparsable obj face vertex: {}", line);
                            ok = false;
                            break;
                        }
                        if (indices[0] < 0 || indices[1] < 0) {
                            if (!warnedIndex) {
                                LOGGER.debug("Obj face uses a relative (negative) index, which is not supported: {}", line);
                                warnedIndex = true;
                            }
                            ok = false;
                            break;
                        }
                        if (indices[0] >= positions.size() || indices[1] >= uvs.size()) {
                            LOGGER.debug("Obj face index out of range: {}", line);
                            ok = false;
                            break;
                        }
                        pos[i] = positions.get(indices[0]);
                        uv[i] = uvs.get(indices[1]);
                    }
                    if (!ok) {
                        continue;
                    }

                    // Fan from corner 0. A quad emits one real quad; a triangle emits one
                    // with its last vertex repeated, which is a degenerate edge the GPU
                    // discards; anything larger emits several. Winding is preserved
                    // because each step keeps the source order.
                    for (int i = 1; i + 1 < corners0; i += 2) {
                        boolean haveFourth = i + 2 < corners0;
                        int d = haveFourth ? i + 2 : i + 1;
                        float[] corners = new float[12];
                        float[] uvCorners = new float[8];
                        int[] pick = {0, i, i + 1, d};
                        for (int c = 0; c < 4; c++) {
                            System.arraycopy(pos[pick[c]], 0, corners, c * 3, 3);
                            System.arraycopy(uv[pick[c]], 0, uvCorners, c * 2, 2);
                        }
                        out.add(new ModelQuad(null, null, corners, uvCorners, texture, 0xFFFFFF));
                    }
                }
                default -> {
                    // vn, o, g, s, mtllib and anything else: irrelevant to geometry we
                    // can draw. Normals in particular are not needed - vertex colours
                    // come from Minecraft's directional face shading, applied elsewhere,
                    // and an obj quad has none.
                }
            }
        }
        return out;
    }

    /** {@code v/vt/vn} with 1-based indices, returned 0-based; {@code {-1,-1}} for a
     * relative (negative) index, or {@code null} if the token cannot be parsed at all. */
    private static int[] parseFaceVertex(String token) {
        String[] parts = token.split("/", -1);
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        try {
            int v = Integer.parseInt(parts[0]);
            int vt = Integer.parseInt(parts[1]);
            if (v < 0 || vt < 0) {
                return new int[]{-1, -1};
            }
            return new int[]{v - 1, vt - 1};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * {@code newmtl} / {@code map_Kd} pairs, with {@code map_Kd}'s {@code #ref} resolved
     * against the wrapping model's texture map - the same indirection an element model's
     * {@code faces} use, just reached from the material side instead.
     */
    private static Map<String, String> resolveMaterialTextures(String mtlText, Map<String, String> textures) {
        Map<String, String> out = new HashMap<>();
        if (mtlText == null) {
            return out;
        }
        String currentName = null;
        for (String rawLine : mtlText.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] tokens = line.split("\\s+", 2);
            switch (tokens[0]) {
                case "newmtl" -> currentName = tokens.length > 1 ? tokens[1].trim() : null;
                case "map_Kd" -> {
                    if (currentName != null && tokens.length > 1) {
                        String ref = tokens[1].trim();
                        String resolved = ref.startsWith("#") ? textures.get(ref.substring(1)) : ref;
                        if (resolved != null) {
                            out.put(currentName, resolved);
                        } else {
                            LOGGER.debug("Obj material {} names texture {} which does not resolve", currentName, ref);
                        }
                    }
                }
                default -> {
                }
            }
        }
        return out;
    }
}
