package dev.duzo.bluemap3d.api;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * One rigid body to draw in BlueMap's 3D scene.
 *
 * <p>The split between {@link #geometry()} and {@link #position()}/{@link #rotation()}
 * is the point of this interface. Geometry is expensive: it is meshed into a vertex
 * buffer and a texture atlas, uploaded once, and then cached. The transform is cheap:
 * it is a dozen bytes streamed on every publish tick and interpolated in the browser.
 *
 * <p>So a moving train, a sailing ship and a walking turtle all re-publish only their
 * transform. Nothing is re-meshed while an object merely moves. Geometry is rebuilt
 * only when {@link #geometryVersion()} changes - a carriage gaining a block, a ship
 * taking damage - which is what keeps this affordable for a whole fleet.
 *
 * <h2>Identity</h2>
 * {@link #id()} is the object's identity across ticks. Keep it stable for as long as
 * the object is the same object; if it changes, the old mesh is removed and a new one
 * is baked from scratch.
 *
 * @see SceneObjectProvider
 * @see BlockVolume
 */
public interface SceneObject {

    /**
     * A stable identifier for this object, unique within its provider.
     *
     * <p>Must be safe to use inside a URL path segment: letters, digits,
     * {@code _}, {@code -}, {@code .} and {@code /}. A UUID or a level-unique
     * entity id is usually right.
     *
     * @return this object's id
     */
    String id();

    /**
     * The blocks making up this object, in its own local space.
     *
     * <p>Only read when {@link #geometryVersion()} indicates a rebuild is needed, so
     * it is fine for this to be the expensive call. It may return a volume that is
     * costly to construct - just do not construct it eagerly in
     * {@link SceneObjectProvider#objects(net.minecraft.server.level.ServerLevel)};
     * return a volume that builds lazily, or accept that it is only asked for on
     * version changes.
     *
     * @return the block volume; never {@code null}
     */
    BlockVolume geometry();

    /**
     * A counter that changes whenever {@link #geometry()} would produce something
     * different.
     *
     * <p>Core caches the baked mesh against this value, so it is the only thing
     * standing between a fleet of ships and re-meshing everything every tick. Return
     * a constant for objects whose shape never changes (a turtle is always one
     * block). Otherwise bump it on structural change - blocks added or removed, a
     * train's consist changing - and <em>not</em> on movement.
     *
     * @return the current geometry version
     */
    long geometryVersion();

    /**
     * World position of this object's {@linkplain BlockVolume#pivot() pivot}, in
     * block coordinates.
     *
     * @return the world position
     */
    Vec3 position();

    /**
     * Orientation about the {@linkplain BlockVolume#pivot() pivot}.
     *
     * <p>Return a fresh or effectively-immutable quaternion; core reads it without
     * copying. {@code new Quaternionf()} is identity, which is right for anything
     * axis-aligned.
     *
     * @return the rotation
     */
    Quaternionf rotation();

    /**
     * An optional human-readable name, shown on hover in the browser.
     *
     * <p>A turtle's label, a ship's name, a train's schedule title. Return {@code null}
     * for nothing, which is the default.
     *
     * @return the label, or {@code null}
     */
    default String label() {
        return null;
    }

    /**
     * The dimension this object is in.
     *
     * <p>Used to pick which BlueMap maps the object appears on, and to hide it when
     * the viewer switches to a map of a different world.
     *
     * @return the dimension key
     */
    ResourceKey<Level> dimension();
}
