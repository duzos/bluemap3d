![logo](https://raw.githubusercontent.com/duzos/bluemap3d/master/img/logo.png)

# BlueMap3D

### The library the add-ons run on.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=for-the-badge)
![Server side](https://img.shields.io/badge/Server%20side-only-2D6FE0?style=for-the-badge)
![Licence](https://img.shields.io/badge/licence-LGPL--3.0-A42E2B?style=for-the-badge)

[![neoforge](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/neoforge_vector.svg)](https://neoforged.net/)

[![Requires BlueMap](https://img.shields.io/badge/requires-BlueMap%205.x-006EDE?style=flat-square)](https://modrinth.com/mod/bluemap)

## What does this mod do?

[BlueMap](https://modrinth.com/mod/bluemap) renders your world as a 3D map in the browser,
but only the terrain. BlueMap3D takes things that move, turns them into real 3D geometry
with their real textures, and puts them into BlueMap 3D scene as they move.

**On its own it does nothing.** You want one of the add-ons:

| Add-on | Shows | Needs |
| --- | --- | --- |
| **[BlueMap: Create](https://modrinth.com/mod/rxpPQGD1)** | Trains, windmills, bearings, gantries, pistons, minecart contraptions | [Create](https://modrinth.com/mod/create) |
| **[BlueMap: Aeronautics](https://modrinth.com/mod/owyPt6vs)** | Airships, planes, cars | [Sable](https://modrinth.com/mod/sable) |
| **[Bluemap: Computer Craft](https://modrinth.com/mod/uWqMFrYC)** | Turtles, with their labels and tools | [CC:Tweaked](https://modrinth.com/mod/cc-tweaked) |

## Install

Server side. Players do not need it.

Put it in `mods/` next to [BlueMap](https://modrinth.com/mod/bluemap), then add the add-ons.

## Settings

`config/bluemap3d-server.toml`. All optional.

| Setting | Default | Does |
| --- | --- | --- |
| `publishIntervalTicks` | `10` | How often positions update. Lower is smoother and uses more bandwidth |
| `maxBlocksPerObject` | `20000` | Skips anything bigger than this |
| `hideLiveBlocksFromTiles` | `true` | Stops things being drawn twice |
| `tileReloadMinSeconds` | `-1` | Set to `5` so viewers see changed terrain without refreshing |
| `useResourcePacks` | `true` | Real models and textures instead of flat colours |
| `sources` | `[]` | Extra resource packs or jars to read models from |
| `shapeFallback` | `true` | Blocks with no model get drawn as their outline shape instead of a cube |
| `maxSpinNodesPerObject` | `32` | How many spinning parts one object can have |
| `maxAttachmentsPerObject` | `4096` | Skips meshing an object with more attachments than this |

## Making an add-on

Implement `SceneObjectProvider` and register it. Anything with blocks and a position can be
drawn. The API is `dev.duzo.bluemap3d.api` and the javadoc covers it with an example. No
client side code, no JavaScript.

## Links

- [BlueMap](https://modrinth.com/mod/bluemap)
- [Bug reports](https://github.com/duzos/bluemap3d/issues)

---

This is a third party mod, not approved by or associated with the developers of BlueMap.

NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
