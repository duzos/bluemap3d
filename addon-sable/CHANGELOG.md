## 1.0.0

First release. Live Sable ships as real 3D geometry in BlueMap's three.js scene, for
NeoForge 1.21.1. Requires BlueMap3D core; see core's own changelog for the shared
rendering engine this addon builds on, including the mesher, the resource-pack model
lookup, this release's blockstate rotation fix, and the pivot-relative double-precision
mesh vertices that keep a ship's plot out at 2e7 from collapsing onto the float grid.

- Live Sable ships, named, meshed out of the sub-level plots BlueMap cannot see. Pivoted on
  the ship's centre of mass, which is what its physics turns about.
- Re-meshed only when the block set changes, never when the ship moves - a hull sails for
  the cost of a transform.
- Terrain a ship invalidates by appearing or being shattered back into the world is queued
  for re-render.
