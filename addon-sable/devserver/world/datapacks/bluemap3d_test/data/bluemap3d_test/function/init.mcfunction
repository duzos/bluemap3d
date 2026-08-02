# Runs on world load and on /reload.
#
# `generator-settings` in server.properties pins the flat layers to bedrock, two dirt and
# grass, so the surface the ships are built on is y=-60.

scoreboard objectives add bm3d_step dummy
scoreboard objectives add bm3d_init dummy

# Sable only steps the physics for a sub-level whose plot is loaded, and with no players
# connected nothing is. Without this everything assembles and then sits perfectly still,
# which looks exactly like the addon publishing a stale transform. Wide enough to cover
# the arena and the Dry Dock out on the grass beyond it.
forceload add -80 -56 56 56

# Build once per world, tracked on a scoreboard because scoreboards persist and blocks do
# not stay where you put them - the moment a ship is assembled its blocks leave the world
# for a sub-level, so `setblock ... keep` would happily build a second ship on top of the
# hole left by the first.
execute unless score #built bm3d_init matches 1 run function bluemap3d_test:build

# The step counter is only rewound for a world that has not been assembled yet. On
# /reload this function runs again, and resetting it here would replay the assembly
# against a region whose blocks are already in a sub-level: Sable assembles the empty box,
# and a body with no blocks has no centre of mass, which takes the physics thread down
# with a native panic rather than an exception. Once is once, per world.
execute unless score #assembled bm3d_init matches 1 run scoreboard players set #timer bm3d_step 0
