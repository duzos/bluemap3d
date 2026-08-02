<div align="center">

# BlueMap3D: Ships

### Your fleet, on the map, where it actually is.

![Mod id](https://img.shields.io/badge/mod%20id-bluemap3d__sable-2D6FE0?style=for-the-badge)
![Status](https://img.shields.io/badge/status-working-3FB950?style=for-the-badge)

[![Requires Sable](https://img.shields.io/badge/requires-Sable-A0A0A0?style=flat-square)](https://modrinth.com/mod/sable)
[![Requires BlueMap3D](https://img.shields.io/badge/requires-BlueMap3D-2D6FE0?style=flat-square)](../README.md)

</div>

## ⛵ What it does

Every Sable ship shows up on your BlueMap as the ship itself - hull, sails, rigging, glass,
lanterns, all of it - sailing where it is really sailing, turning as it really turns, with
its name on it when you hover.

Without this addon there is nothing there. Not a marker in the wrong place, not a stale
copy: a ship is simply absent from the map, because its blocks are not in the world the
map is drawn from.

- **It is the ship, not an icon.** Real blocks, real textures. A fence rail is a fence
  rail; a glass window is glass.
- **It moves as it moves.** Position and heading update several times a second, and the
  map smooths between them, so a ship under way glides rather than stepping.
- **It leans as it leans.** Ships pitch and roll, and the map shows it, because the whole
  orientation is sent rather than just a compass heading.
- **Names.** Whatever you called the ship with `/sable name set`.
- **Big fleets are fine.** A ship is drawn once and then only told where it is, so a
  harbour full of them costs the server almost nothing while they sail.

## 📦 Install

Server side only. Nothing for players to install.

Drop **BlueMap3D**, **BlueMap3D: Ships** and **[Sable](https://modrinth.com/mod/sable)**
into `mods/`, alongside [BlueMap](https://modrinth.com/mod/bluemap). Or take the BlueMap3D
bundle jar, which already carries this addon inside it.

There is nothing to configure. Ships appear on their own.

## 🚢 A note on ships that have not moved yet

When a ship is first assembled, its blocks leave the world - and BlueMap has already drawn
them into the map. On Sable 2.0.3 that flattened copy stays on the ground where the ship
was built until something else causes that area to be redrawn. The live ship is correct
and sails away as it should; it is the picture underneath it that is behind.

This is not something the addon can fix from the outside, and it does not happen the other
way round: break a ship back into the world and the map catches up within seconds.

## ⚙️ Related settings

From core's `config/bluemap3d-server.toml`:

| Key | Why you might touch it |
| --- | --- |
| `maxBlocksPerObject` | Default 20000. A genuinely enormous ship will be skipped until you raise this |
| `publishIntervalTicks` | Default 10, twice a second. Lower it if your ships turn fast enough to look like they are spinning the wrong way |

## 🔗 Links

- [Sable](https://modrinth.com/mod/sable) - required
- [BlueMap](https://modrinth.com/mod/bluemap) - required
- [Contributing and internals](../core/README.md) - if you are writing an addon of your own

Working on this addon? See **[devserver/README.md](devserver/README.md)** for the test
world: two ships, a hover arena, and a pile of physics objects flying about in it.
