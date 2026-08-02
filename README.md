<div align="center">

# BlueMap3D

### Your turtles, ships and trains, as real 3D models on your live map.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=for-the-badge)
![Server side](https://img.shields.io/badge/Server%20side-only-2D6FE0?style=for-the-badge)
![Licence](https://img.shields.io/badge/licence-LGPL--3.0-A42E2B?style=for-the-badge)

[<img alt="neoforge" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/neoforge_vector.svg">](https://neoforged.net/)

[![Requires BlueMap](https://img.shields.io/badge/requires-BlueMap%205.x-006EDE?style=flat-square)](https://modrinth.com/mod/bluemap)

</div>

## 🧊 What is it?

Actual meshed blocks with their real textures, moving around inside BlueMap's 3D scene. Not
POI markers, not overlays, not a coloured blob where a thing is - the thing itself, textured
and lit like the terrain it sits on, updating live as it moves.

Server side only. Nothing for players to install.

## 🚢 The addons

| Addon | Mod id | Shows | Status |
| --- | --- | --- | --- |
| **Turtles** | `bluemap3d_computercraft` | Live CC:Tweaked turtles, labelled, with their upgrades | ✅ Working |
| **Ships** | `bluemap3d_sable` | Sable ships, named, which BlueMap cannot show at all today | ✅ Working |
| **Trains** | `bluemap3d_create` | Create trains, one object per carriage | 🚧 Not implemented |

Ships and trains are the interesting cases: their blocks live outside world chunks, so BlueMap
has no way to draw them. Turtles are in world chunks, so BlueMap3D also hides them from the
terrain tiles to stop them being drawn twice.

## 📦 Install

Drop **BlueMap3D** plus whichever addons you want into `mods/`, alongside
[BlueMap](https://modrinth.com/mod/bluemap). Or take the bundle jar, which carries all three
addons nested inside core.

`bluemap3d` on its own is a library. With no addons installed it loads, logs one line, and
never touches a tick again.

## ⚙️ Config

`config/bluemap3d-server.toml`:

| Key | Default | Does |
| --- | --- | --- |
| `publishIntervalTicks` | `10` | How often object positions are republished. Do not set this slower than your objects turn, or a 180° turn resolves the wrong way round. |
| `maxBlocksPerObject` | `20000` | Refuses to mesh anything bigger, so one absurd ship cannot stall a tick. |
| `hideLiveBlocksFromTiles` | `true` | Stops blocks drawn live also being baked into terrain tiles. |
| `tileReloadMinSeconds` | `-1` | Makes viewers re-download terrain that changed. Off by default because it acts on viewers unasked; `5` is a sensible value. |
| `useResourcePacks` | `true` | Read real block models and textures rather than only map colours. |
| `sources` | `[]` | Extra jars, zips or folders to search for models. |

## 🔌 Writing your own

The whole extension surface is one interface. You say what exists; core meshes it, publishes
it, streams it and draws it. No client code, no JavaScript.

```java
final class BeeHiveProvider implements SceneObjectProvider {
    @Override public String id() {
        return "bee_hives";
    }

    @Override public Collection<? extends SceneObject> objects(ServerLevel level) {
        List<SceneObject> out = new ArrayList<>();
        for (MyHive hive : MyHiveTracker.in(level)) {
            out.add(new SceneObject() {
                public String id()                    { return "hive/" + hive.uuid(); }
                public BlockVolume geometry()         { return BlockVolume.single(hive.blockState()); }
                public long geometryVersion()         { return 1L; }   // shape never changes
                public Vec3 position()                { return hive.center(); }
                public Quaternionf rotation()         { return new Quaternionf().rotateY(hive.yaw()); }
                public String label()                 { return hive.name(); }
                public ResourceKey<Level> dimension() { return level.dimension(); }
            });
        }
        return out;
    }
}
```

Register it with `BlueMap3D.register(new BeeHiveProvider())` and you are done.

**The one thing to get right** is `geometryVersion()`. Bump it when the *shape* changes;
never when the object merely moves. A train travelling should cost one transform per carriage
per tick and no meshing at all.

See **[core/README.md](core/README.md)** for the full API.

## 🔬 How it works

Bake once, then stream. A block volume is meshed server-side into a vertex buffer and a
texture atlas, written into BlueMap's web root, and after that only position and rotation go
out. The browser interpolates between samples, so motion stays smooth however slowly the
server publishes.

The injected script adds meshes straight to `bluemap.mapViewer.markers`, which is a real
`THREE.Scene` rendered with the real camera - so objects get correct depth against terrain for
free.

The awkward part is textures. A dedicated server has no client assets, so blocks are meshed
from resource packs, BlueMap's own `resourceExtensions.zip` (which is where chests and beds
get their geometry), the vanilla client jar BlueMap downloaded, and finally every mod jar.
Anything unresolved falls back to a solid cube in the block's map colour - correct shape, no
texture, and only for that one block.

## 🧪 Testing it locally

Every module has its own dev server.

```bash
./gradlew :addon-turtles:prepareDevServer -Paccept_licences
./gradlew :addon-turtles:runServer
```

Then open <http://localhost:8100>. That one spins up real CC:Tweaked from maven with five
turtles running real Lua - four wandering, one quarrying. See
**[addon-turtles/devserver](addon-turtles/devserver/README.md)**.

```bash
./gradlew :addon-sable:prepareDevServer -Paccept_licences
./gradlew :addon-sable:runServer
```

The ships one builds two vessels, assembles them into real Sable sub-levels and flies one
of them - yaw, roll and climb at once, so a wrong pivot reads as a swing. Sable publishes
no maven, so its jar goes in `addon-sable/libs/` by hand. See
**[addon-sable/devserver](addon-sable/devserver/README.md)**.

## 🔨 Building

```bash
./gradlew build          # the four publishable modules
./gradlew bundleJar      # single jar: core with the three addons nested
```

Publishing the four separately needs no source changes, only not running `bundleJar`.

## 🔗 Links

- [BlueMap](https://modrinth.com/mod/bluemap) - required
- [CC:Tweaked](https://modrinth.com/mod/cc-tweaked) - for the turtle addon

## 🙏 Credits

- [BlueMap](https://github.com/BlueMap-Minecraft/BlueMap) by Blue (Lukas Rieger) - and its
  `resourceExtensions.zip`, which is where the block-entity geometry comes from.
- [create_bluemap](https://modrinth.com/mod/create_bluemap) by Szedann - prior art for reading
  Create's train data.
