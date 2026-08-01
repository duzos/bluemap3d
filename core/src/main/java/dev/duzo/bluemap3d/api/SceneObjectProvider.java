package dev.duzo.bluemap3d.api;

import net.minecraft.server.level.ServerLevel;

import java.util.Collection;

/**
 * Supplies the live set of {@link SceneObject}s of one kind for one level.
 *
 * <p>This is the whole extension surface of BlueMap3D. A provider answers "what
 * objects of my kind exist in this level right now"; core does everything else -
 * baking geometry to a mesh, publishing it, streaming transforms, and rendering in
 * BlueMap's three.js scene. A provider contains no rendering code and no JavaScript.
 *
 * <p>Register one during your mod's setup:
 * <pre>{@code
 * @Mod("mymod_bluemap3d")
 * public final class MyAddon {
 *     public MyAddon(IEventBus bus) {
 *         BlueMap3D.register(new BeeHiveProvider());
 *     }
 * }
 *
 * final class BeeHiveProvider implements SceneObjectProvider {
 *     @Override public String id() {
 *         return "bee_hives";
 *     }
 *
 *     @Override public Collection<? extends SceneObject> objects(ServerLevel level) {
 *         List<SceneObject> out = new ArrayList<>();
 *         for (MyHive hive : MyHiveTracker.in(level)) {
 *             out.add(new SceneObject() {
 *                 public String id() { return "hive/" + hive.uuid(); }
 *                 // One block; baked once because the version never changes.
 *                 public BlockVolume geometry() { return BlockVolume.single(hive.blockState()); }
 *                 public long geometryVersion() { return 1L; }
 *                 public Vec3 position() { return hive.center(); }
 *                 public Quaternionf rotation() { return new Quaternionf().rotateY(hive.yaw()); }
 *                 public ResourceKey<Level> dimension() { return level.dimension(); }
 *             });
 *         }
 *         return out;
 *     }
 * }
 * }</pre>
 *
 * <h2>Threading</h2>
 * {@link #objects(ServerLevel)} is called on the server thread, so touching level and
 * block-entity state directly is safe. It is called once per publish interval per
 * level, so keep it cheap: return already-tracked state rather than scanning chunks.
 *
 * @see SceneObject
 * @see BlockVolume
 */
public interface SceneObjectProvider {

    /**
     * A stable identifier for this provider, unique across all providers.
     *
     * <p>Lowercase with underscores, e.g. {@code "create_trains"}. It namespaces the
     * provider's published assets and appears in the live feed, so changing it
     * orphans previously baked meshes.
     *
     * @return this provider's id
     */
    String id();

    /**
     * The objects of this kind that currently exist in {@code level}.
     *
     * <p>Called on the server thread. Returning an empty collection is normal and
     * cheap - it removes any objects this provider previously reported for that
     * level. Objects whose {@link SceneObject#id()} disappears between calls are
     * removed from the map.
     *
     * @param level the level being published
     * @return the live objects; never {@code null}
     */
    Collection<? extends SceneObject> objects(ServerLevel level);

    /**
     * Blocks this provider draws itself, which BlueMap should therefore leave out of its
     * terrain tiles.
     *
     * <p>Only relevant when your objects are made of blocks that really exist in the world.
     * A turtle is: BlueMap renders it into a tile like any other block, so without this you
     * get the object twice - a live one that moves and a baked one stuck wherever the turtle
     * happened to be when that tile was last rendered. A Create carriage or a Sable ship has
     * the opposite problem and needs nothing here, because their blocks are not in world
     * chunks at all, which is why BlueMap cannot show them in the first place.
     *
     * <p>Return block ids, e.g. {@code computercraft:turtle_normal}. Core generates a
     * BlueMap resource pack that renders them as nothing. Two consequences worth knowing:
     * the block becomes invisible in <em>every</em> tile, including where one is sitting
     * still, and already-rendered tiles keep the old geometry until they are re-rendered.
     *
     * @return block ids to hide; empty by default
     */
    default Collection<net.minecraft.resources.ResourceLocation> hiddenBlocks() {
        return java.util.List.of();
    }
}
