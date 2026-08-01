# One-time world setup. Guarded by #done bm3d_init in init.mcfunction.

scoreboard players set #done bm3d_init 1

# Four ramblers around spawn. They wander, dig down and place blocks back, so terrain
# changes in small amounts all over the place.
setblock 3 -60 0 computercraft:turtle_normal[facing=west]
setblock -3 -60 0 computercraft:turtle_normal[facing=east]
setblock 0 -60 3 computercraft:turtle_normal[facing=north]
setblock 0 -60 -3 computercraft:turtle_normal[facing=south]

# A stone mass for the fifth turtle to quarry. Two chunks over from spawn so its work is
# clearly separable from the ramblers' mess.
fill 40 -60 40 55 -49 55 minecraft:stone keep
# A cap of a different stone so the quarry's progress reads at a glance from overhead.
fill 40 -49 40 55 -49 55 minecraft:andesite replace minecraft:stone

# The quarry turtle, embedded in the top layer rather than standing on it. On top, its first
# whole layer is spent walking across open air digging nothing, which looks exactly like a
# broken quarry.
setblock 41 -49 41 computercraft:turtle_normal[facing=east]
