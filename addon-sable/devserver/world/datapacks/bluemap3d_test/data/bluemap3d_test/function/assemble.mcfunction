# Lift both structures out of the world into sub-levels, then fill the arena with things
# to bump into. Once per world, ever - see the note in init.mcfunction about what a second
# run does.
#
# Deferred rather than done in build.mcfunction: Sable builds a level's sub-level
# container while the level loads, and a `sable` command run before that reports
# "Couldn't find sub-level container for this level".
#
# `assemble area <from> <to>` rather than `assemble connected <from>`: the box is
# explicit, so a stray block outside it cannot be dragged along and a gap inside it
# cannot leave half the ship behind. Both boxes start at y=-60, which is above the floor,
# so neither ship sets sail with a slab of the world stuck to its keel.
#
# @l is Sable's sub-level selector for "most recently created", which is how each ship
# gets its name straight after assembly without anyone having to know its uuid.

scoreboard players set #assembled bm3d_init 1

sable assemble area 9 -60 -4 25 -50 4
sable name set @l "The Grey Gull"

sable assemble area -72 -60 -5 -62 -54 5
sable name set @l "The Dry Dock"

# Sable's own spawn commands, which is the quickest way to a pile of physics objects that
# are nothing like a ship: solid spheres, a flat slab, a stack that topples, a lattice
# with holes in it. Between them they cover dense and sparse geometry, one block and
# hundreds, and half a dozen different textures.
#
# `jenga` and `grid` each spawn one sub-level per block rather than one for the whole
# thing, so this ends up as several dozen objects. That is deliberate: it is the only
# thing here that says whether the addon copes with a crowd.

execute positioned -20 -46 -18 run sable spawn sphere 3 minecraft:iron_block
sable name set @l "Iron Bell"

execute positioned 18 -46 20 run sable spawn sphere 2 minecraft:gold_block
sable name set @l "Gold Pip"

execute positioned -22 -44 22 run sable spawn sphere 2 minecraft:redstone_block
sable name set @l "Red Pip"

execute positioned 24 -44 -22 run sable spawn platform 4 minecraft:prismarine
sable name set @l "The Raft"

execute positioned -6 -44 12 run sable spawn jenga 4
execute positioned 8 -44 -14 run sable spawn grid 3 minecraft:copper_block

say [BlueMap3D] assembled two ships and filled the arena
