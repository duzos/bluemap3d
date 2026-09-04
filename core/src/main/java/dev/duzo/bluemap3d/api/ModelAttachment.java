package dev.duzo.bluemap3d.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Objects;

/**
 * An extra model to draw on top of a {@link BlockVolume}'s blocks.
 *
 * <p>Block states do not describe everything you can see. A turtle's modem and pickaxe, an
 * item frame's contents, the text on a sign - all of those are drawn by a block-entity
 * renderer from state that lives in the block entity, not in the block state. A volume made
 * only of block states cannot show any of it.
 *
 * <p>An attachment fills that gap by naming a model directly. It is deliberately not a
 * general-purpose transform: the models involved are authored in the same 0..16 space as the
 * block they belong to - CC's {@code turtle_modem_normal_off_left} is positioned against the
 * turtle's left face already - so an attachment just says which model, at which block.
 *
 * <p>Attachments live on {@link BlockVolume} rather than {@link SceneObject} because they
 * are geometry, which means they are baked and cached against
 * {@link SceneObject#geometryVersion()} like everything else. Bump that version when an
 * attachment appears, disappears or changes.
 *
 * @param at       which block of the volume this hangs off, in the volume's local
 *                 coordinates
 * @param model    the model to draw, e.g.
 *                 {@code computercraft:block/turtle_modem_normal_off_left}
 * @param textures overrides for the model's {@code #ref} texture variables, highest
 *                 priority. CC's upgrade mount leaves {@code #texture} for the caller to
 *                 fill in, which is how one mount model serves every tool.
 * @param transform an optional transform in <em>block units</em>, applied after the model's
 *                  0..16 coordinates are scaled down. Identity for models already positioned
 *                  where they belong, which is most of them. It exists for item models,
 *                  which are authored facing the viewer and have to be rotated into place -
 *                  CC positions a turtle's tool with exactly such a matrix.
 * @param spin      set when the part turns as the object travels, null when it is baked in
 *                  place. See {@link Spin}
 */
public record ModelAttachment(BlockPos at, ResourceLocation model, Map<String, String> textures,
                              org.joml.Matrix4f transform, Spin spin) {

    public ModelAttachment {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(model, "model");
        textures = textures == null ? Map.of() : Map.copyOf(textures);
        transform = transform == null ? new org.joml.Matrix4f() : new org.joml.Matrix4f(transform);
    }

    /** No transform: the model is already positioned where it belongs. */
    public ModelAttachment(BlockPos at, ResourceLocation model, Map<String, String> textures) {
        this(at, model, textures, null, null);
    }

    /** A static attachment: no spin. */
    public ModelAttachment(BlockPos at, ResourceLocation model, Map<String, String> textures,
                           org.joml.Matrix4f transform) {
        this(at, model, textures, transform, null);
    }

    /**
     * A part that turns as its object travels, rather than one baked in place.
     *
     * <p>Rotation is derived in the browser from how far the object moved, not published
     * as an angle. An angle sampled at {@code publishIntervalTicks} would alias hopelessly:
     * the default is two samples a second and a wheel turns several times a second. A
     * distance is immune to that, because it is integrated rather than sampled.
     *
     * @param pivot  the point the part turns about, in the model's own 0..16 space
     * @param axis   the axle direction, in the model's own 0..16 space. Normalised on
     *               construction
     * @param radius the part's visual radius, in the model's own 0..16 space. The browser
     *               turns the part by {@code travel / radius} radians, so a radius that
     *               does not match what is drawn makes the part slip against the ground
     */
    public record Spin(Vector3f pivot, Vector3f axis, float radius) {
        public Spin {
            Objects.requireNonNull(pivot, "pivot");
            Objects.requireNonNull(axis, "axis");
            // JOML's normalize() has no zero guard - it multiplies by invsqrt(0) - so a
            // zero axis would return (NaN, NaN, NaN) rather than throwing. A NaN axis
            // makes the whole child vanish in the browser, which is a far worse failure
            // than an exception here.
            if (axis.lengthSquared() < 1.0e-20f) {
                throw new IllegalArgumentException("axis must be non-zero");
            }
            // Copied for the same reason the enclosing record copies its transform: JOML
            // types are mutable and the volume is handed to a background baker.
            pivot = new Vector3f(pivot);
            axis = new Vector3f(axis).normalize();
            // Phrased as a negated comparison so NaN is rejected too.
            if (!(radius > 0)) {
                throw new IllegalArgumentException("radius must be positive, was " + radius);
            }
        }
    }

    /** An attachment on the block at the volume's local origin, with no texture overrides. */
    public static ModelAttachment of(ResourceLocation model) {
        return new ModelAttachment(BlockPos.ZERO, model, Map.of());
    }

    /** An attachment on the block at the volume's local origin, overriding one texture. */
    public static ModelAttachment of(ResourceLocation model, String variable, String texture) {
        return new ModelAttachment(BlockPos.ZERO, model, Map.of(variable, texture));
    }

    /** Whether {@link #transform()} is anything other than the identity. */
    public boolean hasTransform() {
        return !transform.equals(new org.joml.Matrix4f(), 1.0e-6f);
    }
}
