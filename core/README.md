<div align="center">

# BlueMap3D

### The library. Install an addon to see something.

![Mod id](https://img.shields.io/badge/mod%20id-bluemap3d-2D6FE0?style=for-the-badge)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=for-the-badge)
![Licence](https://img.shields.io/badge/licence-LGPL--3.0-A42E2B?style=for-the-badge)

</div>

## 🧊 What it does

Takes blocks and transforms from a provider, meshes them into a vertex buffer and texture
atlas, publishes them into BlueMap's web root, and draws them in BlueMap's three.js scene with
smooth interpolated motion.

With no addons registered it loads, logs one line, and never touches a tick again.

> **Hard rule:** core references Create, Sable and CC:Tweaked **nowhere**. No imports, no
> classpath dependency, no soft-compat shims. That is what makes the module split trivial and
> core publishable on its own.

## 🔌 The API

Everything in `dev.duzo.bluemap3d.api` is stable from 1.0.0. Everything outside it is
implementation and may change in any release.

### `SceneObjectProvider`

```java
String id();                                          // "create_trains"
Collection<? extends SceneObject> objects(ServerLevel level);
default Collection<ResourceLocation> hiddenBlocks();  // blocks you draw, hide from tiles
```

Called on the server thread, once per publish interval per level. Keep it cheap - return
already-tracked state rather than scanning chunks.

### `SceneObject`

```java
String id();                     // stable across ticks, url-safe
BlockVolume geometry();          // read only when geometryVersion() changes
long geometryVersion();          // the one thing to get right
Vec3 position();
Quaternionf rotation();
default String label();          // hover text
ResourceKey<Level> dimension();
```

### `BlockVolume`

| Factory | For |
| --- | --- |
| `single(state)` | one block - a turtle |
| `region(source, min, max, pivot)` | a region of any level, including a sub-level - a ship |
| `of(blocks, pivot)` | a sparse position-to-state map - a contraption or carriage |

`region` and `of` copy eagerly, so the volume is a snapshot and safe to mesh off the server
thread.

### `ModelAttachment`

For geometry no block state describes - a turtle's modem, an item frame's contents, a sign's
text. Returned from `BlockVolume.attachments()`, so it is cached against `geometryVersion()`
like everything else.

```java
new ModelAttachment(BlockPos.ZERO,
        ResourceLocation.parse("computercraft:block/turtle_speaker_left"),
        Map.of());
```

Naming an **item** model works too and gets you a real extruded tool, not a flat sprite -
core builds the shape from the sprite the way the client does. Item models are authored
face-on, so those want the optional `transform`, in block units.

### `BlueMap3D`

```java
BlueMap3D.register(provider);              // any time in the mod lifecycle
BlueMap3D.refreshArea(level, pos);         // your object changed the world
```

## ⚠️ geometryVersion

Core caches the baked mesh against it.

- **Bump it** when the shape changes: a block added, a consist changing, a hull damaged.
- **Never bump it** when the object merely moves.

Get this wrong and you re-mesh a ship hull every second.

## 🧪 Its dev server

BlueMap and nothing else - the check that core standalone genuinely does nothing.

```bash
./gradlew :core:prepareDevServer -Paccept_licences
./gradlew :core:runServer
```

## 🔬 Implementation notes

The parts most likely to bite, all recorded in the source where they apply:

- **Reaching the scene** - `bluemap3d.core.js` opens with why `window.BlueMap.Three` and
  `mapViewer.markers` work, and what depends on where BlueMap's marker render pass sits.
- **The mesh format** - `Bm3dWriter`, including why it is not glTF.
- **Textures on a server** - `AssetIndex`, including block entities and why BlueMap's
  `resourceExtensions.zip` matters.
- **Drawing things twice** - `HiddenBlockPack`, including the config-vs-data directory trap
  that fails silently.
- **Terrain that changes** - `TileRefreshQueue` and the terrain reload in the client script.
- **Volumes a long way from the origin** - `VolumeMesher`, on why the pivot is subtracted
  first and in double. A Sable ship's blocks live out at about 2e7, past where a float can
  tell one block from the next; get the order wrong and the model offsets are gone before
  the pivot is ever subtracted. A turtle at spawn never shows it.

## 🚂 Writing the trains addon

`addon-create` is a registered stub. Whoever picks it up:

1. Enumerate live trains and their carriages for a `ServerLevel`.
2. One `SceneObject` **per carriage**, not per train - a train articulates, so it is not one
   rigid body. Geometry comes from `BlockVolume.of`, which takes a carriage's in-memory
   block map directly.
3. Position and rotation per carriage each tick.
4. Bump `geometryVersion()` when the consist changes - a carriage added, removed, or its
   contraption edited - and never on movement.

`hiddenBlocks()` is not needed: a contraption's blocks are not in world chunks, so they are
never drawn twice.

Prior art for the data access:
[create_bluemap](https://modrinth.com/mod/create_bluemap) by Szedann reads
`com.simibubi.create.content.trains.entity.Train` off a scheduled executor; reuse how it
reaches the train list and replace its marker output with a provider.

Create's maven pulls a large transitive graph - Registrate, Flywheel, Ponder - so the
dependency is left commented in `addon-create/build.gradle` rather than paid for on every
build while the provider is a stub.
