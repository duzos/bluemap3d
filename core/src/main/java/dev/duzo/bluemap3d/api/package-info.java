/**
 * BlueMap3D's public API. Stable from 1.0.0.
 *
 * <p>Everything in this package is API: it will not change incompatibly within a major
 * version. Everything outside it is core's implementation and may change in any
 * release, so do not depend on it.
 *
 * <p>To render something of your own in BlueMap's 3D scene, implement
 * {@link dev.duzo.bluemap3d.api.SceneObjectProvider} and register it with
 * {@link dev.duzo.bluemap3d.api.BlueMap3D#register}. You supply blocks and transforms;
 * core meshes, publishes and renders them. There is no client-side code and no
 * JavaScript for you to write - if you find yourself needing either, the abstraction
 * here has leaked and the right fix is in core.
 *
 * @see dev.duzo.bluemap3d.api.SceneObjectProvider
 * @see dev.duzo.bluemap3d.api.SceneObject
 * @see dev.duzo.bluemap3d.api.BlockVolume
 */
package dev.duzo.bluemap3d.api;
