## 1.0.0

First release. Live ComputerCraft turtles as real 3D geometry in BlueMap's three.js scene,
for NeoForge 1.21.1. Requires BlueMap3D core; see core's own changelog for the shared
rendering engine this addon builds on, including the mesher, the resource-pack model
lookup, and this release's blockstate rotation fix.

- Live ComputerCraft turtles, with labels. Found through the vanilla block-entity registry
  rather than CC's implementation classes.
- Upgrades render: peripherals from CC's own models, tools extruded from their item sprite
  and placed with CC's own transform.
