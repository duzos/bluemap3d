# addon-create dev server

A mechanical bearing, driven by a creative motor, turning on the flat.

```bash
./gradlew :addon-create:prepareDevServer -Paccept_licences
./gradlew :addon-create:runServer
```

Then open <http://localhost:8100>. Unlike Sable, Create needs no jar dropped in by hand: it
resolves from `maven.createmod.net` through `devMods`, as the **main** jar rather than the
`slim` one, because the loader needs the jar-in-jars that slim leaves out.

## What this proves, and what it does not

Be honest with yourself about this rig. It exercises the whole pipeline end to end -
enumeration, geometry, the transform, the mesh cache, the browser - and it does that
correctly. What it does **not** yet do is produce a large, multi-block contraption.

| Verified here | How |
| --- | --- |
| The provider registers and enumerates | `Registered SceneObjectProvider 'create_contraptions'` |
| A contraption is found, meshed and published | `Publishing 1 3D object(s)`, one `.bm3d` and one atlas `.png` under `bluemap/web/assets/bluemap3d/meshes/create_contraptions/` |
| The mesh is cached, not rebuilt | Exactly one `.bm3d` after minutes of rotation. A version that moved with the object would leave one file per interval |
| The object id is dimension-scoped | The file is named `minecraft_overworld_<uuid>-<version>` |
| Position and pivot are right | The object sits on the bearing's axis and does not drift while turning |
| Rotation is live and on the correct axis | `rot` in `entities3d.json` advances in Y only, for a `facing=up` bearing |
| It renders in BlueMap | `mesh ready: ... 24 verts` in the browser console, and the mesh quaternion advances in the scene |
| Removal is handled | `kill` the entity and the tracker drops to `Publishing 0` on the next interval |

**Not verified here:** multi-block contraptions, trains, gantries, minecart contraptions,
and the block-entity gap. See below.

## The single-block problem

The bearing assembles, but it picks up only the one block on its axis. That is Create
behaving correctly, not a bug in the addon: plain blocks do not propagate into a contraption
on their own. Real structures are held together by **super glue**, a **linear or radial
chassis** configured with a wrench, or blocks that Create treats as self-attaching, and none
of those can be set up from `setblock` alone - a chassis needs its radius set with a wrench,
and super glue is an entity placed between block pairs.

So the datapack builds the smallest thing that genuinely assembles, and the arms beside it
stay in the world as ordinary terrain. If you are looking at the map and see a static
coloured L that does not turn, that is the terrain copy; the contraption is the single block
turning at its root.

**To test properly, build it by hand once.** Join the dev server in creative, glue or
chassis a real structure together, assemble it, then copy `run/server/world` into
`devserver/world`. From then on the rig is deterministic and the whole thing is one command
again. That is the right next step for this directory and it has not been done yet.

## What's in here

| Path | Why |
| --- | --- |
| `config/bluemap3d-server.toml` | A byte-for-byte copy of what NeoForge writes. Hand-write it and NeoForge rewrites the file on first load to put the comments back, and the reload that follows can land in the window where BlueMap enables and BlueMap3D reads its config, failing the whole mod with "Cannot get config value before config is loaded" |
| `world/datapacks/bluemap3d_test/` | Builds the bearing rig and triggers assembly once |

## Driving it by hand

RCON is on, port 25575, password `bluemap3d`. The useful queries:

```
data get block 0 -58 0
```
the bearing. `Running: 1b` and an advancing `Angle` means it is turning.

```
data get entity @e[type=create:stationary_contraption,limit=1] Contraption.Blocks.Palette
```
what the contraption actually picked up. This is the one that tells you whether your
structure assembled or only its root block did.

Create's four contraption entity types are `create:contraption`,
`create:stationary_contraption`, `create:gantry_contraption` and
`create:carriage_contraption`. All four go through the same provider.

## The creative motor

Its speed is `ScrollValue`, not the `GeneratedSpeed` you might guess, and it has to be set
when the block is placed:

```
setblock 0 -59 0 create:creative_motor[facing=up]{ScrollValue:4}
```

`data merge` on an existing motor changes the field but does not make Create recompute the
kinetic network, so the bearing keeps reading zero. Break and replace the block instead.

Four RPM is deliberate. A transposed rotation matrix and an undersampled fast rotation look
alike on a spinning object, so the thing that proves the transform has to turn slowly enough
that no single sample is ambiguous. At four RPM each publish moves it a few degrees.
