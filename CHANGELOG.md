## 1.0.0

First release. Real 3D geometry inside BlueMap's three.js scene, for NeoForge 1.21.1.

**Core (`bluemap3d`)**
- `SceneObjectProvider` / `SceneObject` / `BlockVolume` - the whole extension API, stable
  from this release.
- Server-side block-volume mesher: face culling, Minecraft's directional face shading baked
  into vertex colours, texture atlas packing.
- Real block models read from resource packs, BlueMap's resource extensions, the vanilla
  client jar and mod jars, with a map-colour cube as a per-block fallback.
- Block entities - chests, beds, shulker boxes, signs, banners - via BlueMap's own
  `resourceExtensions.zip`.
- Meshes baked once and cached against `geometryVersion()`; only position and rotation are
  republished per interval.
- Mesh vertices are made pivot-relative in double precision, so a volume read from
  coordinates a long way from the origin - a Sable ship's plot, out at 2e7 - keeps its
  shape instead of collapsing onto the float grid.
- Blockstate `x`/`y` rotation turns the model the way vanilla does. It was turning the
  opposite way, which mirrored every rotated model - a `facing=south` stair pointed north -
  and put each quad's cull face on the opposite side of the block, so a rotated block lost
  whichever face was against a neighbour. A barrel on the floor came out with no lid and
  its underside showing.
- Blocks that no source could model are named in the log instead of silently becoming
  coloured cubes.
- Injected webapp script adds meshes to BlueMap's marker scene and interpolates transforms
  per frame, with terrain occlusion and dimension filtering.

- `ModelAttachment` for geometry no block state describes - a turtle's modem, an item
  frame's contents. Item models are extruded from their sprite, since vanilla item models
  carry no geometry of their own.
- Blocks drawn live are hidden from BlueMap's terrain tiles, so nothing renders twice.
- Optional live terrain reload, so a viewer sees mined terrain without refreshing the page.

**Turtles (`bluemap3d_computercraft`)**
- Live ComputerCraft turtles, with labels. Found through the vanilla block-entity registry
  rather than CC's implementation classes.
- Upgrades render: peripherals from CC's own models, tools extruded from their item sprite
  and placed with CC's own transform.

**Ships (`bluemap3d_sable`)**
- Live Sable ships, named, meshed out of the sub-level plots BlueMap cannot see. Pivoted on
  the ship's centre of mass, which is what its physics turns about.
- Re-meshed only when the block set changes, never when the ship moves - a hull sails for
  the cost of a transform.
- Terrain a ship invalidates by appearing or being shattered back into the world is queued
  for re-render.

**Trains (`bluemap3d_create`)**
- Module, dependencies and publishing set up; provider not implemented yet.
