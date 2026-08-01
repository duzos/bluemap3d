# BlueMap3D

Renders **real 3D geometry** inside BlueMap's three.js scene: actual meshed blocks with
their real textures, positioned and rotated live. Not POI markers, not overlays.

NeoForge 1.21.1. Server-side only - nothing to install on clients.

```
core/            bluemap3d                    the library: provider API, baker, live feed, webapp JS
addon-turtles/   bluemap3d_computercraft      live ComputerCraft turtles
addon-sable/     bluemap3d_sable              Sable ships          (not implemented yet)
addon-create/    bluemap3d_create             Create trains        (not implemented yet)
dev-harness/     bluemap3d_devharness         local test harness   (never published)
```

`core` has a hard rule: it references Create, Sable and CC:Tweaked **nowhere**. No imports,
no classpath dependency, no soft-compat shims. It compiles and runs standalone with zero
addons and does nothing at all. That is what makes it publishable on its own and useful to
anyone.

## Writing a provider

The entire extension surface is one interface. You describe what exists; core meshes it,
publishes it, streams it and renders it. There is no client-side code and no JavaScript for
you to write.

```java
@Mod("mymod_bluemap3d")
public final class MyAddon {
    public MyAddon() {
        BlueMap3D.register(new BeeHiveProvider());
    }
}

final class BeeHiveProvider implements SceneObjectProvider {
    @Override public String id() {
        return "bee_hives";
    }

    @Override public Collection<? extends SceneObject> objects(ServerLevel level) {
        List<SceneObject> out = new ArrayList<>();
        for (MyHive hive : MyHiveTracker.in(level)) {
            out.add(new SceneObject() {
                public String id()                 { return "hive/" + hive.uuid(); }
                public BlockVolume geometry()      { return BlockVolume.single(hive.blockState()); }
                public long geometryVersion()      { return 1L; }   // shape never changes
                public Vec3 position()             { return hive.center(); }
                public Quaternionf rotation()      { return new Quaternionf().rotateY(hive.yaw()); }
                public String label()              { return hive.name(); }
                public ResourceKey<Level> dimension() { return level.dimension(); }
            });
        }
        return out;
    }
}
```

`BlockVolume` has three factories, one per shape that has to work:

| Factory | For |
|---|---|
| `BlockVolume.single(state)` | one block - a turtle |
| `BlockVolume.region(source, min, max, pivot)` | a region of any level, including a sub-level - a ship |
| `BlockVolume.of(blocks, pivot)` | a sparse position-to-state map - a contraption or train carriage |

`region` and `of` copy eagerly, so the volume is a snapshot and safe to mesh off the server
thread.

### Things block states don't describe

A turtle's modem and pickaxe, an item frame's contents, a sign's text: all drawn by a
block-entity renderer from data the block state knows nothing about. A volume made only of
block states comes out bare.

`BlockVolume.attachments()` returns `ModelAttachment`s, which name a model directly:

```java
new ModelAttachment(BlockPos.ZERO,
        ResourceLocation.parse("computercraft:block/turtle_speaker_left"),
        Map.of());                       // no texture overrides, no transform
```

They live on `BlockVolume` rather than `SceneObject` because they are geometry, so they are
cached against `geometryVersion()` like everything else - bump it when an attachment appears
or changes.

**Item models work too.** Naming `minecraft:item/diamond_pickaxe` gets you a real extruded
tool, not a flat sprite: vanilla item models carry no geometry at all (`item/handheld` plus a
`layer0` texture), so core builds the shape from the sprite the way the client does - merged
front and back faces plus a per-pixel rim. Item models are authored face-on, so those need
the optional `transform`, in block units.

### The one thing to get right

`geometryVersion()`. Core caches the baked mesh against it, so:

- **Bump it** when the shape changes: a block added, a consist changing, a hull damaged.
- **Never bump it** when the object merely moves.

A train travelling should cost one transform per carriage per tick and no meshing at all.
Get this wrong and you re-mesh a ship hull every second.

## How it works

Baking is once; movement is cheap. Server-side, a `BlockVolume` is meshed into a vertex
buffer plus a texture atlas and written into BlueMap's web root, then only position and
rotation are republished on an interval. The browser interpolates between samples, so
motion is smooth regardless of how often the server publishes.

### Reaching BlueMap's scene

All of this was verified against the deployed BlueMap 5.7 webapp bundle, not assumed:

- **`window.BlueMap.Three` is three.js itself.** BlueMap re-exports its own module, so the
  injected script bundles no three.js and cannot drift out of version with the renderer.
- **`window.bluemap.mapViewer.markers` is a real `THREE.Scene`** (`class MarkerSet extends
  Scene`), and `MapViewer.render()` does `renderer.render(this.markers, this.camera)` every
  frame. Anything added to it is drawn by the real renderer with the real camera.
- That pass runs **after** the terrain passes with no intervening `clearDepth()`, so objects
  behind a hill are correctly occluded for free.
- It runs **outside** BlueMap's precision shift (the camera is moved toward the origin for
  terrain and moved back first), so positions are plain world coordinates.
- `MarkerSet.clear()` and `updateFromData()` only remove markers they track in their own
  maps, so children added directly survive BlueMap's marker sync.
- Coordinates are **raw Minecraft coordinates** - BlueMap's own `ExtrudeMarker` does
  `point.x - this.position.x` with no axis remapping.
- `WebApp.registerScript(url)` adds the url to `settings.json`'s `scripts` array, which the
  webapp loads as plain `<script>` tags after `window.bluemap` exists.

### Not glTF

BlueMap 5.7's bundle contains no `GLTFLoader` (0 hits in the deployed bundle). glTF would
mean vendoring a loader and pinning a three.js version. Since `BlueMap.Three` is right there,
meshes use a small binary format (`.bm3d`) that becomes a `BufferGeometry` directly - a few
dozen lines at each end, no third-party code.

### Shading

BlueMap's marker scene contains no lights, so a lit material renders black. Minecraft's own
directional face shading (top 1.0, north/south 0.8, east/west 0.6, bottom 0.5) is baked into
vertex colours and drawn unlit. That is also what makes these meshes sit correctly next to
BlueMap's shader-lit terrain.

## Textures on a server

A dedicated server has no client assets, which is the awkward part of meshing server-side.
Core searches, in priority order:

1. roots you configure in `bluemap3d-server.toml`;
2. resource packs in BlueMap's directory;
3. **BlueMap's own `resourceExtensions.zip`**;
4. the vanilla client jar BlueMap downloaded;
5. the mod class loader, covering every installed mod.

Anything unresolved falls back to a solid cube in the block's map colour - correct 3D shape,
no texture. Per block, so one gap costs one block.

### Block entities

Chests, beds, shulker boxes, signs and banners have no model geometry in vanilla; the client
draws them in code. Falling back to their `particle` texture is worse than nothing - it draws
a chest as a cube of oak planks.

BlueMap already solved this, which is why (3) above is in the list. It ships hand-authored
blockstates and models for exactly those blocks inside its own jar and overlays them on
vanilla, so BlueMap3D reads the same zip and gets chests for free. It has to sit above the
client jar in the order, because vanilla does ship a `chest.json` and the extension is what
replaces it.

Modded block entities need much less help: a mod almost always ships an ordinary blockstate
and model and uses its renderer only for the moving part. A CC turtle is
`computercraft:block/turtle_normal`, whose parent carries the real body and backpack cuboids.
What genuinely has no geometry is a block drawn entirely in code - and because configured
roots are searched first, a zip of hand-authored models fixes those with no code change.

## Drawing the same thing twice

An object made of blocks that really exist in world chunks - a turtle - gets rendered into
BlueMap's terrain tiles as well as drawn live. You end up with two: one that moves, and one
frozen wherever it was when that tile was last rendered.

`hideLiveBlocksFromTiles` (on by default) fixes it, by writing a resource pack that maps
those blocks to an empty model. Providers declare what to hide via
`SceneObjectProvider.hiddenBlocks()`.

Two things this depends on, both of which cost real time to find:

**It goes in the config directory, not the data directory.** BlueMap has two roots and they
are easy to confuse:

| | contains | reached by |
|---|---|---|
| data | `web/`, the downloaded client jar, `resourceExtensions.zip` | `getWebRoot().getParent()` |
| config | `maps/`, `storages/`, `plugin.conf`, **`packs/`** | `getPacksFolder()` |

Writing the pack to the data directory fails **silently** - no error, no warning, BlueMap
simply never reads it.

**It has to be written before BlueMap starts.** BlueMap reads its packs at startup, which is
before it hands out its API, so this happens on `ServerAboutToStartEvent`. Tiles rendered
earlier keep the old geometry until re-rendered (`/bluemap purge`).

Ships and trains never hit any of this - their blocks are not in world chunks, which is the
whole reason BlueMap cannot draw them.

## Terrain that changes

When an object edits the world - a turtle mining - the tile goes stale. Core coalesces
changed positions into tiles and calls `scheduleMapUpdateTask`, via
`BlueMap3D.refreshArea(level, pos)`.

That re-renders it server-side, but **a viewer with the page open still will not see it**:
BlueMap's webapp never revalidates tiles, its update loop only follows the player marker, and
tile urls carry a cache hash fixed for the session. So the server also publishes which tiles
changed, and the injected script replaces exactly those - hires only, never lowres, because
dropping lowres blanks the whole view to show a change too small to see at that zoom.

Off by default (`tileReloadMinSeconds = -1`), because it makes viewers re-download without
asking. Set it to `5` to enable.

## Seeing it work locally

Every module has its own dev server, so testing the addon you are working on is one command
in that module rather than a shared setup you have to reconfigure.

```bash
./gradlew :addon-turtles:prepareDevServer -Paccept_licences
./gradlew :addon-turtles:runServer
```

Then open <http://localhost:8100>.

`-Paccept_licences` writes `eula=true` and BlueMap's `accept-download: true`. Drop it and the
task prints the two lines to edit yourself. Without `accept-download` the terrain is blank and
objects render as map-colour cubes - they still move, so the pipeline is testable either way.

| Module | What its dev server gives you |
|---|---|
| `addon-turtles` | **The real test.** CC:Tweaked from maven, four turtles placed by a datapack, each running a `startup.lua` that labels itself and random-walks. See [addon-turtles/devserver](addon-turtles/devserver/README.md). |
| `dev-harness` | Four synthetic objects, no third-party mods. Isolates core's pipeline from any question about a target mod's API. |
| `core` | BlueMap and nothing else - the check that core standalone does nothing at all. |

A module adds mods to its dev server from maven with `devMods "group:artifact:version"`, and
world content by dropping files in `<module>/devserver/`, which is copied over `run/server`.

## Building

```bash
./gradlew build          # all four publishable modules
./gradlew bundleJar      # single jar: core with the three addons nested
```

The bundle is packaging only, via NeoForge's jar-in-jar. Publishing the four artifacts
separately needs no source changes - only not running that task.

## Licence

LGPL-3.0-only.
