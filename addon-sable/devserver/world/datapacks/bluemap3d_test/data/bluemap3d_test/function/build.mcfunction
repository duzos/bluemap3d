# One-time world build. Guarded by #built bm3d_init in init.mcfunction.
#
# An arena, a ship to fly in it, and a hut outside it that never moves.

scoreboard players set #built bm3d_init 1

# ---------------------------------------------------------------------------------------
# The arena.
#
# The floor is end stone, which is a lift material in Sable: things above it float, and a
# sub-level's own end stone does not lift itself, so a floor of the stuff is a hover pad.
# It is not what does the work here - what actually keeps everything up is the thrust in
# sail.mcfunction, and a heavy object settles onto the pad regardless - but it takes the
# edge off, and it is Sable's own mechanic rather than something scripted.
#
# Glass walls, because the thrust eventually carries things over anything shorter and a
# physics object that leaves the pad drops out of the show. They top out at y=-30, well
# above the height the lift stops at, so nothing coasts over. Glass, so you can still see in.
# ---------------------------------------------------------------------------------------

fill -48 -61 -48 48 -61 48 minecraft:end_stone
fill -49 -60 -49 -49 -30 49 minecraft:glass
fill 49 -60 -49 49 -30 49 minecraft:glass
fill -49 -60 -49 49 -30 -49 minecraft:glass
fill -49 -60 49 49 -30 49 minecraft:glass

# ---------------------------------------------------------------------------------------
# The Grey Gull, x 10..24, z -3..3, inside the arena so she joins in.
# ---------------------------------------------------------------------------------------

# Hull: a hollow box, so the top face at y=-57 becomes the deck.
fill 10 -59 -3 22 -57 3 minecraft:oak_planks hollow

# Bow, tapering out past the hull.
fill 23 -59 -1 23 -57 1 minecraft:oak_planks
setblock 24 -58 0 minecraft:oak_planks
setblock 24 -57 0 minecraft:lantern[hanging=false]

# Gunwale.
fill 10 -56 -3 22 -56 3 minecraft:oak_fence hollow

# Stern cabin, with windows down both sides.
fill 11 -56 -2 14 -54 2 minecraft:spruce_planks hollow
fill 11 -55 -2 14 -55 -2 minecraft:glass replace minecraft:spruce_planks
fill 11 -55 2 14 -55 2 minecraft:glass replace minecraft:spruce_planks

# Mast, sail and crow's nest.
fill 17 -56 0 17 -52 0 minecraft:oak_fence
fill 17 -55 -2 17 -52 2 minecraft:white_wool
fill 16 -51 -1 18 -51 1 minecraft:oak_planks

# ---------------------------------------------------------------------------------------
# The Dry Dock, x -70..-64, out on the grass well clear of the arena. Nothing thrusts it
# and nothing can hit it, so it is the control: a ship that draws while standing still.
# ---------------------------------------------------------------------------------------

fill -70 -60 -3 -64 -57 3 minecraft:stone_bricks hollow
fill -70 -59 -3 -64 -58 -3 minecraft:glass replace minecraft:stone_bricks
fill -70 -59 3 -64 -58 3 minecraft:glass replace minecraft:stone_bricks
fill -71 -56 -4 -63 -56 4 minecraft:dark_oak_planks
setblock -67 -55 0 minecraft:sea_lantern

say [BlueMap3D] built the arena, the Grey Gull and the Dry Dock
