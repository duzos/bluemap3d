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
- **Blocks with no model** - `AssetIndex` and `ResourcePackSource`. A block the client
  draws with a block-entity renderer has a stub model with no geometry, so nothing can be
  read for it. Core names those in the log at the end of a bake rather than leaving you to
  guess which grey lump was which, and `assets.shapeFallback` will draw them from their
  voxel outline instead of a coloured cube.
- **Volumes a long way from the origin** - `VolumeMesher`, on why the pivot is subtracted
  first and in double. A Sable ship's blocks live out at about 2e7, past where a float can
  tell one block from the next; get the order wrong and the model offsets are gone before
  the pivot is ever subtracted. A turtle at spawn never shows it.

## 🚂 How the Create addon works

`addon-create` enumerates `AbstractContraptionEntity` and reports one `SceneObject` per
contraption entity. That single choice is worth understanding before changing anything
here:

1. **Trains come free.** Create's four contraption entity types all extend that base, and a
   train carriage is already one entity per carriage with its own server-updated pose. The
   articulation that made trains look like the hardest addon is solved by Create before
   this addon sees it. Bearings, gantries, pistons and minecart contraptions arrive through
   the same enumeration, and a fifth subclass would need no change.
2. **The registry is the wrong layer.** `Create.RAILWAYS` is what
   [create_bluemap](https://modrinth.com/mod/create_bluemap) and `create-track-map` read,
   and correctly so - they draw markers, and a dot on a 2D map wants the track graph. This
   wants blocks and a pose, which the entity has and the registry does not. It also keeps
   `Create` itself off the compile classpath, and with it Registrate.
3. **The transform is Create's own.** `toGlobalVector` is
   `anchor + off + applyRotation(local - off)` with `off = (0.5, 0.5, 0.5)`, which matches
   core's `position + rotation * (local - pivot)` with a constant pivot. The rotation is
   recovered by sampling `applyRotation` with the three basis vectors rather than by
   reimplementing four subclasses.
4. **`geometryVersion()` has to hash block states, not count them.** Create mutates a
   contraption's states in place - doors, lamps, deployers - so a count alone never changes
   and a carriage door would never re-mesh.

`hiddenBlocks()` is not needed: a contraption's blocks are not in world chunks, so they are
never drawn twice.

The dependency is `compileOnly` on the `slim` artifact, which is one jar: every dependency
in Create's pom is runtime-scoped, so nothing transitive arrives. The dev server takes the
full jar instead, because it has to run and the loader needs the jar-in-jars.
