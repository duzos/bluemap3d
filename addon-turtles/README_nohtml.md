![logo](https://raw.githubusercontent.com/duzos/bluemap3d/master/addon-turtles/img/logo.png)

# Bluemap: Computer Craft

### ComputerCraft turtles on your BlueMap, moving in real time.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=for-the-badge)
![Server side](https://img.shields.io/badge/Server%20side-only-2D6FE0?style=for-the-badge)

[![neoforge](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/neoforge_vector.svg)](https://neoforged.net/)

[![Requires CC:Tweaked](https://img.shields.io/badge/requires-CC%3ATweaked-A0A0A0?style=flat-square)](https://modrinth.com/mod/cc-tweaked)
[![Requires BlueMap](https://img.shields.io/badge/requires-BlueMap%205.x-006EDE?style=flat-square)](https://modrinth.com/mod/bluemap)
[![Requires BlueMap3D](https://img.shields.io/badge/requires-BlueMap3D-2D6FE0?style=flat-square)](https://modrinth.com/mod/bluemap3d)

## What does this mod do?

Puts your [CC:Tweaked](https://modrinth.com/mod/cc-tweaked) turtles on your web map. Every
loaded turtle shows up as its actual model, moving as it moves, with its label on it.

Leave a quarry running and watch the hole appear from the map.

![ComputerCraft turtles moving live on a BlueMap web map, with the terrain they have mined](https://raw.githubusercontent.com/duzos/bluemap3d/master/addon-turtles/img/turtles-live.gif)

A fleet wandering a superflat. The dark patches are blocks they mined.

![A ComputerCraft turtle with a wireless modem on its side](https://raw.githubusercontent.com/duzos/bluemap3d/master/addon-turtles/img/upgrade-modem.png)
**Peripherals** use CC own models

![A ComputerCraft turtle with a diamond pickaxe on its side](https://raw.githubusercontent.com/duzos/bluemap3d/master/addon-turtles/img/upgrade-pickaxe.png)
**Tools** are built from their sprite, so they have thickness

- The real turtle model with its real textures, facing the way it is facing
- **Labels** from `os.setComputerLabel`, on hover
- **Upgrades** show. Modems, speakers and crafting tables use CC own models. Tools are built
  out of the item sprite, so a diamond pickaxe looks like a diamond pickaxe
- Turning is smooth. A turtle rounding a corner sweeps round instead of snapping
- No double vision. A turtle is a real block in a real chunk, so the map would otherwise
  show it twice, once live and once frozen where it was. The frozen one gets hidden
- Mined ground gets redrawn, so a quarry appears while you watch

## Install

Server side. Players do not need it.

Put BlueMap3D, Bluemap: Computer Craft and
[CC:Tweaked](https://modrinth.com/mod/cc-tweaked) in `mods/`, next to
[BlueMap](https://modrinth.com/mod/bluemap). Nothing to configure.

## Worth turning on

Set `tileReloadMinSeconds = 5` in `config/bluemap3d-server.toml` and anyone with the map
open sees mined ground update without refreshing. That is most of the fun of watching a
quarry.

## Links

- [CC:Tweaked](https://modrinth.com/mod/cc-tweaked)
- [BlueMap](https://modrinth.com/mod/bluemap)
- [BlueMap3D](https://modrinth.com/mod/bluemap3d)
- [Bug reports](https://github.com/duzos/bluemap3d/issues)

---

This is a third party mod, not approved by or associated with the developers of CC:Tweaked
or BlueMap.

NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
