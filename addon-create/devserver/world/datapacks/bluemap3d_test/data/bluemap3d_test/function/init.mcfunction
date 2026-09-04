# Runs on world load and on /reload.
#
# `generator-settings` in server.properties pins the flat layers to bedrock, two dirt and
# grass, so the surface everything is built on is y=-60.

scoreboard objectives add bm3d_step dummy
scoreboard objectives add bm3d_init dummy

# Create only ticks a contraption whose chunk is ticking, and with no players connected
# none are. Without this both bearings assemble and then sit perfectly still, which looks
# exactly like the addon publishing a stale transform.
forceload add -32 -32 48 32

# Build once per world. Tracked on a scoreboard because scoreboards persist and blocks do
# not stay where you put them: the moment a contraption assembles its blocks leave the
# world, so `setblock ... keep` would build a second one into the hole left by the first.
execute unless score #built bm3d_init matches 1 run function bluemap3d_test:build

execute unless score #assembled bm3d_init matches 1 run scoreboard players set #timer bm3d_step 0
