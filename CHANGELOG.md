## 1.0.0

First release. Real 3D geometry inside BlueMap's three.js scene, for NeoForge 1.21.1.

**Core (`bluemap3d`)**
- `SceneObjectProvider` / `SceneObject` / `BlockVolume` - the whole extension API, stable
  from this release.
- Server-side block-volume mesher: face culling, Minecraft's directional face shading baked
  into vertex colours, texture atlas packing.
- Real block models read from resource packs, BlueMap's resource extensions, the vanilla
  client jar and mod jars, with a map-colour cube as a per-block fallback.
- OBJ meshes (plus their MTL materials) read the same way, for the mods that ship less
  box-shaped parts that way instead of as a JSON element model. A model that reaches its
  mesh through a parent - the shape most mods use to reskin another mod's part, with a
  leaf that is nothing but a parent and a texture list - resolves too, rather than falling
  through to a grey map-colour cube.
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
- Attachments can move on their own as the object travels - spin about an axle, slide back
  and forth, orbit a pivot, or turn at a constant rate - without needing a pose sampled
  every publish, which would alias badly against anything turning faster than the map
  publishes.
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

**Create (`bluemap3d_create`)**
- Live Create contraptions: train carriages, minecart contraptions, gantry carriages,
  piston and pulley assemblies, and rotating bearings.
- Minecart contraptions, gantry carriages and piston/pulley assemblies are found by
  enumerating `AbstractContraptionEntity` - Create already spawns one entity per
  contraption, so nothing is missed.
- Train carriages are walked straight off `Create.RAILWAYS.trains` instead, since a
  carriage's own entity can be absent for long stretches while the train it belongs to
  keeps moving.
- Rotation for the entity-backed contraptions is recovered by sampling Create's own
  `applyRotation` with the three basis vectors, so they all go through one path and a new
  contraption type would too. Train carriages derive theirs from the railway anchors
  instead, since that path has no entity to ask.
- A contraption that disassembles becomes a new entity, and so a new object with a fresh
  bake, at no cost.
- Curved track is drawn as real geometry - Create draws it from a bezier at render time
  with no blocks of its own, so this addon walks the same bezier itself. Straight,
  diagonal, ascending and crossing track all draw too.
- A curve draws in the material it is actually built from rather than andesite, found from
  the material's own blockstate, so another mod's track needs no list here to keep up with.
  Steam 'n' Rails' hundred-and-fifty-odd wood, narrow-gauge and wide-gauge tracks all draw
  as themselves; its monorail curves stay andesite, since a monorail curve is a girder
  rather than sleepers and rails.
- Bogey wheels turn with the carriage they ride under, small and large bogeys both.
- Bearing caps - mechanical, windmill and clockwork alike - turn at the rate they actually
  turn in game, not a speed value that can be spinning while the bearing itself is stalled.
- Station flags raise and lower and change texture for whether a train is present, absent,
  or the station is mid-assembly.
