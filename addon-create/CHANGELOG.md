## 1.0.0

First release. Live Create contraptions as real 3D geometry in BlueMap's three.js scene,
for NeoForge 1.21.1. Requires BlueMap3D core; see core's own changelog for the shared
rendering engine this addon builds on, including the mesher, the resource-pack model
lookup, and this release's blockstate rotation fix.

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
- Bogey wheels turn with the carriage they ride under, small and large bogeys both, plus all
  twenty-seven Steam 'n' Rails bogey styles across its medium, double-axle, large-Create-styled,
  single-axle and triple-axle families.
- Bearing caps - mechanical, windmill and clockwork alike - turn at the rate they actually
  turn in game, not a speed value that can be spinning while the bearing itself is stalled.
- Station flags raise and lower and change texture for whether a train is present, absent,
  or the station is mid-assembly.
