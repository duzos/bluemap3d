<div align="center">

<img src="img/logo.png" width="160" alt="logo">

# BlueMap: Aeronautics

### Airships, planes and cars on your BlueMap, moving in real time.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=for-the-badge)
![Server side](https://img.shields.io/badge/Server%20side-only-2D6FE0?style=for-the-badge)

[<img alt="neoforge" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/neoforge_vector.svg">](https://neoforged.net/)

[![Requires Sable](https://img.shields.io/badge/requires-Sable-A0A0A0?style=flat-square)](https://modrinth.com/mod/sable)
[![Requires BlueMap](https://img.shields.io/badge/requires-BlueMap%205.x-006EDE?style=flat-square)](https://modrinth.com/mod/bluemap)
[![Requires BlueMap3D](https://img.shields.io/badge/requires-BlueMap3D-2D6FE0?style=flat-square)](https://modrinth.com/mod/bluemap3d)

</div>

## What does this mod do?

Puts your [Create: Aeronautics](https://modrinth.com/mod/create-aeronautics) vehicles on your
web map.

Fly an airship across the world and a normal BlueMap shows empty sky. That is not BlueMap
being broken. An assembled vehicle blocks live in a
[Sable](https://modrinth.com/mod/sable) sub-level, which the map never looks at.

This finds them and draws them where they actually are:

- **Airships**, **planes** and **cars**, or anything else built on a Sable sub-level
- Real blocks and real textures. Fence rails are fence rails, glass is glass
- Pitch, roll and bank, not just a compass heading
- Turns around its centre of mass, which is what the physics uses, so a turn looks like a
  turn
- Whatever you named it, shown on hover

## Install

Server side. Players do not need it.

Put BlueMap3D, BlueMap: Aeronautics and [Sable](https://modrinth.com/mod/sable) in `mods/`,
next to [BlueMap](https://modrinth.com/mod/bluemap). If you run Create: Aeronautics you
already have Sable. Nothing to configure.

## Questions

**Do I need Create: Aeronautics?**
No, only Sable. Aeronautics is just what most people use Sable for.

**There is a flat copy of my ship where I built it.**
That one is Sable, not this. When a vehicle assembles its blocks leave the world, but the
map copy of that chunk still has them and never gets told to redraw. Break the vehicle back
into the world and the map catches up in a few seconds.

**Does a big fleet lag the server?**
No. A vehicle is only redrawn when its blocks change, never when it moves.

**My vehicle is not on the map.**
Check the area is loaded. Sable only runs physics for a loaded sub-level, so if it is not
loaded it is not moving either.

**A huge ship is missing.**
Raise `maxBlocksPerObject` in `config/bluemap3d-server.toml`. Defaults to 20000.

**Versions?**
1.21.1, NeoForge, Sable 2.x.

## Links

- [Sable](https://modrinth.com/mod/sable)
- [Create: Aeronautics](https://modrinth.com/mod/create-aeronautics)
- [BlueMap](https://modrinth.com/mod/bluemap)
- [BlueMap3D](https://modrinth.com/mod/bluemap3d)
- [Bug reports](https://github.com/duzos/bluemap3d/issues)

---

<div align="center">
<sub>

This is a third party mod, not approved by or associated with the developers of Sable,
Create: Aeronautics or BlueMap.

NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.

</sub>
</div>
