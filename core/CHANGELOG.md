## 1.0.0

First release. Real 3D geometry inside BlueMap's three.js scene, for NeoForge 1.21.1.

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
