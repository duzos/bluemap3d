<div align="center">

# BlueMap3D: Turtles

### Watch your fleet work, live, from the map.

![Mod id](https://img.shields.io/badge/mod%20id-bluemap3d__computercraft-2D6FE0?style=for-the-badge)
![Status](https://img.shields.io/badge/status-working-3FB950?style=for-the-badge)

[![Requires CC:Tweaked](https://img.shields.io/badge/requires-CC%3ATweaked-A0A0A0?style=flat-square)](https://modrinth.com/mod/cc-tweaked)
[![Requires BlueMap3D](https://img.shields.io/badge/requires-BlueMap3D-2D6FE0?style=flat-square)](../README.md)

</div>

## 🐢 What it does

Every loaded turtle appears on your BlueMap as its actual model - body, backpack, and
whatever is bolted to its sides - moving in real time and labelled with whatever the turtle
called itself.

<div align="center">
<img src="img/turtles-live.gif" width="600" alt="Turtles moving live on BlueMap, with the terrain they have dug">
<br><sub>A fleet wandering a superflat, live. The dark patches are blocks they have mined.</sub>
</div>

<div align="center">
<table>
  <tr>
    <td align="center" valign="top"><img src="img/upgrade-modem.png" height="240" alt="A turtle with a wireless modem on its side"><br><sub><b>Peripherals</b> use CC's own models, so a modem is exactly the modem</sub></td>
    <td align="center" valign="top"><img src="img/upgrade-pickaxe.png" height="240" alt="A turtle with a diamond pickaxe on its side"><br><sub><b>Tools</b> are extruded from their sprite - a real pickaxe with thickness, not a flat decal</sub></td>
  </tr>
</table>
</div>

- **It is the turtle, not an icon.** The real model with its real textures, facing the way
  it is actually facing.
- **Labels.** Whatever `os.setComputerLabel` set. Hover to read it.
- **Upgrades show.** Modems, speakers and crafting tables use CC's own models. Tools are
  built out of their item sprite, so a diamond pickaxe looks like a diamond pickaxe.
- **Turning is smooth.** A turtle turning a corner sweeps round rather than snapping
  between four poses.
- **No double vision.** A turtle is a real block in a real chunk, so the map would
  otherwise show it twice - the live one, and a frozen copy wherever it was when that patch
  of map was last drawn. The frozen one is hidden.
- **Their work shows up.** A turtle that mines or builds gets that part of the map redrawn,
  so a quarry appears while you watch instead of at the next full render.

## 📦 Install

Server side only. Nothing for players to install.

Drop **BlueMap3D**, **BlueMap3D: Turtles** and
**[CC:Tweaked](https://modrinth.com/mod/cc-tweaked)** into `mods/`, alongside
[BlueMap](https://modrinth.com/mod/bluemap). Or take the BlueMap3D bundle jar, which
already carries this addon inside it.

There is nothing to configure. Turtles appear on their own.

## 🐢 Which turtles show

Loaded ones. A turtle in a chunk nobody is keeping loaded is not running either, so there
is nothing to watch.

A turtle that has never been switched on has no computer id yet, so it is tracked by
position instead - which is fine, because it cannot move until it boots.

## ⚙️ Related settings

From core's `config/bluemap3d-server.toml`:

| Key | Why you might touch it |
| --- | --- |
| `tileReloadMinSeconds` | Off by default. Set it to `5` and a viewer with the map open sees mined terrain appear without reloading the page |
| `publishIntervalTicks` | Default 10, twice a second. Lower it for snappier movement at the cost of a little bandwidth |

## 🔗 Links

- [CC:Tweaked](https://modrinth.com/mod/cc-tweaked) - required
- [BlueMap](https://modrinth.com/mod/bluemap) - required
- [Contributing and internals](../core/README.md) - if you are writing an addon of your own

Working on this addon? See **[devserver/README.md](devserver/README.md)** for the test
world: five turtles running real Lua, four wandering and one quarrying.
