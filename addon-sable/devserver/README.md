# addon-sable dev server

A hover arena with forty-odd Sable sub-levels flying around inside it, colliding with each
other and with the glass, plus a ship parked outside that never moves. All of it found
through the same code path that runs in production.

```bash
./gradlew :addon-sable:prepareDevServer -Paccept_licences
./gradlew :addon-sable:runServer
```

Then open <http://localhost:8100>. Give BlueMap a minute to render the flat world; the
objects appear as soon as the addon publishes them, which is well before that.

`-Paccept_licences` writes `eula=true` and BlueMap's `accept-download: true`. Drop it and
the task tells you which two lines to edit instead.

## Sable itself

Sable is on RyanHCode's own maven at <https://maven.ryanhcode.dev/releases>, so it is
pulled down by coordinate like CC:Tweaked and nothing has to be dropped in by hand. The
version is `sable_version` in `gradle.properties`.

`build.gradle` takes it twice: `compileOnly` for the API, alongside `sable-companion` for
`Pose3d` and `BoundingBox3i`, and `devMods`, which is what puts the jar in this server's
`mods/`.

## What's in here

Everything under `devserver/` is copied verbatim over `run/server`.

| Path | Why |
|---|---|
| `config/bluemap3d-server.toml` | `tileReloadMinSeconds = 5`, so terrain the ships invalidate is re-downloaded while you watch. Off in the shipped defaults because it acts on viewers unasked |
| `world/datapacks/bluemap3d_test/` | Builds the arena, assembles two ships, spawns the debris and keeps it all moving |

The config is a byte-for-byte copy of what NeoForge writes, with one value changed. That
is deliberate: hand-write it and NeoForge rewrites the file on first load to add the
comments back, and the reload that follows can land in the window where BlueMap enables
and BlueMap3D reads its config, which fails the whole mod with "Cannot get config value
before config is loaded".

## What is in the arena

A 97-block end-stone floor with glass walls up to y=-30, and in it:

**The Grey Gull** - oak hull, spruce cabin with glass windows, a fence gunwale, a mast
with a wool sail, a crow's nest and a lantern on the bow. Enough different blocks that a
grey cube is obvious, and enough shape that a wrong pivot reads as a swing rather than a
turn.

**Iron Bell, Gold Pip, Red Pip, The Raft** - Sable's own `spawn sphere` and
`spawn platform`. Dense solids and a flat slab, so the mesher is not only ever handed
ship-shaped things.

**A jenga stack and a copper lattice** - `spawn jenga` and `spawn grid` make one sub-level
*per block* rather than one for the whole thing, which is where most of the forty-odd
objects come from. That is deliberate: it is the only thing here that says whether the
addon copes with a crowd.

**The Dry Dock** - a stone-brick hut out on the grass at x=-67, outside the arena and
outside everything that thrusts. It is the control: a ship that draws while standing
still, and is never re-meshed for it.

## How it gets going

1. **`init`** runs on world load. It force-loads the area - Sable only steps the physics
   for a sub-level whose plot is loaded, and with nobody connected nothing is - and calls
   `build` once per world.
2. **`build`** puts the arena and both structures in the world with `fill` and `setblock`.
3. **`tick`** counts to 60, then calls `assemble` **once, ever**, and after that runs
   `pulse` every tick.
4. **`assemble`** runs `sable assemble area <from> <to>` on each ship, names it with
   `sable name set @l` (`@l` being Sable's selector for the most recently created
   sub-level), then spawns the debris.
5. **`pulse`** counts to ten and calls **`sail`**, which is the thrust.

Both one-shots are guarded by scoreboard flags rather than by `setblock ... keep`, because
the moment a ship is assembled its blocks leave the world and every guard that looks at
the world is looking at a hole. Assembly in particular **must not** run twice: the second
run assembles the now-empty box, and a body with no blocks has no centre of mass, which
takes Sable's physics thread down with a native Rapier panic rather than an exception.
`/reload` re-runs `init`, so that guard is load-bearing.

## The three things that make the thrust work

All of them cost time to find, and all of them are commented in `sail.mcfunction`.

**Impulses are mass times speed change.** A copper block is a four hundredth of the iron
sphere. One number for both flings the block through the wall and does nothing at all to
the sphere, so every thrust is banded by mass.

**A sleeping body ignores impulses.** Rapier puts a body that has come to rest to sleep,
and three thousand units of thrust into a settled sphere does nothing measurable. Left to
itself the arena runs down over a couple of minutes. Anything at a standstill therefore
gets its *pose* written instead, which always takes - and shoving it into its neighbours
makes the contacts that wake them.

**`distance` is not blocks.** `@e[distance=..60]` centred on an arena 97 blocks across
picks out one object in forty-five. The arena is selected with `x=-55..55` instead, which
also happens to be what keeps the Dry Dock out of it.

Drive it by hand over RCON if you want - port 25575, password `bluemap3d`:

```bash
sable physics impulse @e[x=-55..55] angular 0 200 0 local
```

## What to look for

| Check | What it proves |
|---|---|
| Anything at all | Sub-levels enumerated, and their blocks read out of plots BlueMap cannot see |
| They look like ships and spheres, not grey cubes | Plot blocks resolved through the model and texture pipeline |
| Fence rails read as posts, glass as panes | Real block models, not one cube per block |
| Objects bank and tumble rather than swinging | The pivot is the centre of mass, which is what Sable rotates about |
| Motion is smooth, not half-second steps | Browser-side interpolation between published samples |
| Two objects meeting bounce | Real Sable physics; nothing here scripts a path |
| `meshes/sable_ships/` holds one file per object after ten minutes | `geometryVersion()` is not moving when the object is |
| Everything still there after a restart | Ids are the sub-level uuids, so they survive save and load |

## The ghost on the grass

There is a flat copy of each ship baked into the terrain where it was built, and it does
not go away. That is Sable, not this addon.

Assembling a ship does clear the blocks from the live level - `/fill 9 -60 -4 25 -50 4
minecraft:air replace minecraft:oak_planks` finds nothing to fill - but the chunk is never
marked unsaved, so the data BlueMap reads still has the ship in it. The provider queues
those tiles for re-render on assembly and BlueMap obliges; it just renders the ship back.
Deleting `bluemap/web/maps` and letting it render the world again from scratch reproduces
it, which is how you can tell it is the source data and not a stale tile.

The other direction works. `sable assemble shatter sub_level @e[name="The Dry Dock"]`
writes the blocks back through the ordinary path, and the hut reappears in the terrain
within a few seconds - which is the same refresh doing its job. (It also shatters into 228
one-block sub-levels, so it doubles as a load test.)
