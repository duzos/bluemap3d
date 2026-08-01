package dev.duzo.bluemap3d.bake;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Serialises a {@link BakedMesh} to the {@code .bm3d} wire format.
 *
 * <p>Deliberately not glTF. BlueMap 5.7's webapp bundle contains no {@code GLTFLoader}
 * (checked against the deployed bundle), so glTF would mean vendoring a loader into the
 * injected script and pinning it to a three.js version. It does, however, export its
 * own three.js as {@code window.BlueMap.Three} - so a format the client can turn
 * straight into a {@code BufferGeometry} costs a few dozen lines at each end, ships no
 * third-party code, and cannot drift out of version with the renderer.
 *
 * <h2>Layout</h2>
 * Little-endian throughout. Every multi-byte array starts on a 4-byte boundary so the
 * browser can wrap the buffer in typed arrays with no copying.
 * <pre>
 *   offset  type          field
 *   0       char[4]       magic "BM3D"
 *   4       u32           format version (1)
 *   8       u32           vertex count
 *   12      u32           index count
 *   16      u32           atlas url length in bytes
 *   20      u8[]          atlas url, utf-8, zero-padded to a 4-byte boundary
 *   ...     f32[v * 3]    positions, block units relative to the pivot
 *   ...     f32[v * 2]    uvs, normalised into the atlas
 *   ...     u32[i]        indices
 *   ...     u8[v * 3]     vertex colours, RGB
 * </pre>
 */
public final class Bm3dWriter {

    /** Current format version. Bumped only on an incompatible layout change. */
    public static final int VERSION = 1;

    private static final byte[] MAGIC = {'B', 'M', '3', 'D'};

    private Bm3dWriter() {
    }

    /**
     * Encodes a mesh.
     *
     * @param mesh     the mesh; must not be {@linkplain BakedMesh#isEmpty() empty}
     * @param atlasUrl the URL the client should load the texture atlas from
     * @return the encoded bytes
     */
    public static byte[] encode(BakedMesh mesh, String atlasUrl) {
        byte[] url = atlasUrl.getBytes(StandardCharsets.UTF_8);
        int urlPadded = (url.length + 3) & ~3;

        int vertices = mesh.vertexCount();
        int size = 20 + urlPadded
                + vertices * 3 * 4
                + vertices * 2 * 4
                + mesh.indices().length * 4
                + vertices * 3;

        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.putInt(VERSION);
        buf.putInt(vertices);
        buf.putInt(mesh.indices().length);
        buf.putInt(url.length);
        buf.put(url);
        for (int i = url.length; i < urlPadded; i++) {
            buf.put((byte) 0);
        }

        for (float f : mesh.positions()) {
            buf.putFloat(f);
        }
        for (float f : mesh.uvs()) {
            buf.putFloat(f);
        }
        for (int i : mesh.indices()) {
            buf.putInt(i);
        }
        buf.put(mesh.colors());

        return buf.array();
    }

    /** Encodes a mesh straight to a stream. The stream is not closed. */
    public static void write(BakedMesh mesh, String atlasUrl, OutputStream out) throws IOException {
        out.write(encode(mesh, atlasUrl));
    }
}
