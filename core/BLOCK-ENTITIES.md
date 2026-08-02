# Blocks with no model

### Why some blocks come out as coloured lumps, and what can be done about it

Measured on 1.21.1 against Create 6.0.10, BlueMap 5.7 and Sable 2.0.3.

---

## The short version

A dedicated server has no baked models. Core reconstructs geometry by reading blockstate
and model JSON out of jars and resource packs. That works for the overwhelming majority of
blocks, **including most block entities** - but it fails for any block whose blockstate
points at a model with no `elements`, because the mod draws it from code instead.

Those blocks are not missing. They fall back to `MapColorSource` and come out as a
slightly-shrunk cube in the block's map colour, which at map zoom reads as "it did not
render".

As of this release core says so, once per bake:

```
No model found for 1 block type(s); drawn as map-colour cubes: create:belt.
Supply models for these through bluemap3d.assets.sources if you want them textured.
```

That line is the whole diagnosis. Anything not in it resolved properly.

## What was actually measured

A ship containing one of each of the following was assembled and meshed, and the source
that served each block recorded:

| Block | Result |
| --- | --- |
| `minecraft:chest` | 18 quads, real geometry |
| `minecraft:barrel`, `minecraft:bell` | real geometry |
| `create:redstone_link` | 22 quads, real geometry |
| `create:item_vault`, `create:fluid_tank`, `create:mechanical_press` | real geometry |
| `create:shaft`, `create:cogwheel`, `create:depot`, `create:chute` | real geometry |
| **`create:belt`** | **map-colour cube** |

So the gap is narrow. It is not "block entities do not render" - vanilla chests render,
and every Create block tested except the belt renders. It is specific blocks.

## Why the belt fails, exactly

Create's `assets/create/blockstates/belt.json` has 320 variants. Every one of the 160 with
`casing=false` points at `create:block/belt/particle`, which is this in full:

```json
{ "textures": { "particle": "create:block/belt" }, "elements": [] }
```

No geometry at all. The other 160, with `casing=true`, point at
`create:block/belt_casing/*`, which do have geometry - but only the casing box, not the
belt band or the pulleys.

The real belt geometry **is in the jar**: `create:block/belt/start`, `middle`, `end`,
`diagonal_start`, `diagonal_middle`, `diagonal_end`, the `_bottom` variants and
`belt_pulley`, all hand-authored in Blockbench. Nothing references them. Create's
`BeltRenderer` picks the right one at runtime from the block entity, which is invisible to
anything reading assets off disk.

That is the general shape of the problem: **the geometry usually exists, it is just not
reachable from the blockstate.**

## The three kinds of failure

**1. Vanilla block entities** - chest, bed, shulker box, sign, banner. Already solved:
BlueMap ships hand-authored blockstates and models for exactly these in
`resourceExtensions.zip`, and core reads the same zip. Nothing to do.

**2. A stub blockstate over geometry that exists** - Create's belt. Fixable with a
blockstate override alone; no new models need authoring, only a JSON that points the
variants at the models already in the jar.

**3. Geometry that exists only as code** - a modded chest is the usual case. Its blockstate
is a stub like vanilla's, and its shape lives in a `LayerDefinition` in Java, not in any
JSON. The *texture* is on disk (`textures/entity/chest/...`) but the *shape* is not.
Vanilla's chest is only solved because Blue hand-authored a model for it.

## Workarounds, in the order worth trying

### A. A resource pack, no code

Already supported and needs nothing new. Put a zip or folder in
`bluemap3d.assets.sources`; it is searched before everything else, so an
`assets/<mod>/blockstates/<block>.json` in it replaces the mod's.

For the belt that is a generated file mapping each `part`/`slope`/`facing` combination to
the matching `create:block/belt/*` model with the right `x`/`y` rotation. 320 variants, but
mechanically derivable, and no models to draw.

For a modded chest, point its blockstate at BlueMap's own chest model with the texture
overridden - the shape is identical, only the texture differs.

**This is the recommendation.** It is per-server, needs no release, and cannot break
anything else.

### B. Ship a curated pack in core

The same thing, maintained upstream, so belts work out of the box. It is what BlueMap does
for vanilla. The cost is real: a pack per mod per version, and it silently rots when a mod
changes its blockstate. Worth doing only for a handful of very common blocks, and belts are
a strong candidate.

### C. Fall back to the collision shape instead of a cube

The only one that helps blocks nobody has authored anything for, and the one I would build
next.

Today an unresolved block becomes a cube shrunk to 4/16..12/16 - the same silhouette
whatever the block is. But the server *does* know the block's real shape:
`state.getShape(...)` gives the voxel boxes the client uses for collision and outlines, and
`state.getBlock().defaultBlockState()` can be asked for a particle texture. Building the
fallback out of those boxes, textured with the particle sprite, would give:

- a belt as a flat slab in belt texture rather than a floating cube;
- a modded chest as a 14×14×14 box in its own wood;
- anything with a distinctive shape reading as that shape.

Not correct - a chest would have no lid seam and no lock - but much closer, and it costs no
assets and no per-mod maintenance. It would also make the fallback nearly invisible at map
zoom, which is where it matters.

### D. Read the block-entity renderer

Not possible. `BlockEntityRenderer` and `LayerDefinition` are client classes; they are not
on a dedicated server's classpath at all. Nothing to read.

## What this does not explain

A belt that is genuinely absent rather than lumpy is a different problem. Create belts
validate themselves: a run needs a matching `part=start` / `part=middle` / `part=end` and
breaks itself if it does not have one. Assembling a ship moves blocks in a way Create does
not necessarily see as valid, and a broken belt is gone from the world before anything
tries to draw it. That happened during this investigation - three belts placed with
inconsistent `part` values had vanished by the time the mesher ran, which is why the first
survey had no belt in it at all.

Worth checking in game before assuming the map is at fault: if the belt is not there after
assembly, the map is right.
