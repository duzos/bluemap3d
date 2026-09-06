![logo](https://raw.githubusercontent.com/duzos/bluemap3d/master/addon-create/img/logo.png)

# BlueMap: Create

### Create trains and contraptions on your BlueMap, moving in real time.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=for-the-badge)
![Server side](https://img.shields.io/badge/Server%20side-only-2D6FE0?style=for-the-badge)

[![neoforge](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/neoforge_vector.svg)](https://neoforged.net/)
[![create](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/requires/create_vector.svg)](https://modrinth.com/mod/create)

[![Requires BlueMap](https://img.shields.io/badge/requires-BlueMap%205.x-006EDE?style=flat-square)](https://modrinth.com/mod/bluemap)
[![Requires BlueMap3D](https://img.shields.io/badge/requires-BlueMap3D-2D6FE0?style=flat-square)](https://modrinth.com/mod/bluemap3d)

## What does this mod do?

Puts your [Create](https://modrinth.com/mod/create) trains on your web map. A normal BlueMap
shows the track and nothing on it, because a contraption's blocks leave the world when it
assembles.

Every carriage gets drawn with its real blocks and textures, and moves along the line while
you watch.

Also shows:

- **Minecart contraptions** rolling along the rail
- **Windmills and bearings** turning on their real axis
- **Gantry carriages** sliding along the shaft
- **Pistons and pulleys**, including rope and elevator pulleys
- **[Steam 'n' Rails](https://modrinth.com/mod/create-steam-n-rails-1.21.1) bogeys**, drawn with their own
  wheels and frames instead of Create's, if you have it installed

Anything Create moves as a contraption gets moved on the map too.

## Install

Server side. Players do not need it.

Put BlueMap3D, BlueMap: Create and [Create](https://modrinth.com/mod/create) in `mods/`,
next to [BlueMap](https://modrinth.com/mod/bluemap). Works with the defaults.

A config file appears at `config/bluemap3d_create-server.toml` after the first run, with
settings for curved track (`curvedTrack`, `gridSize`, `maxCurveObjects`, `trackMaterials`),
bearing caps (`bearingCaps`, `maxBearingCaps`, `maxBearingCacheEntries`), station flags
(`stationFlags`, `maxStationFlags`) and a verbose logging switch (`verboseTrackLogging`).
Most servers will not need to touch it.

## Links

- [Create](https://modrinth.com/mod/create)
- [Steam 'n' Rails](https://modrinth.com/mod/create-steam-n-rails-1.21.1)
- [BlueMap](https://modrinth.com/mod/bluemap)
- [BlueMap3D](https://modrinth.com/mod/bluemap3d)
- [Bug reports](https://github.com/duzos/bluemap3d/issues)

---

This is a third party mod, not approved by or associated with the developers of Create or
BlueMap.

NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
