# Runs on world load and on /reload.
#
# `generator-settings` in server.properties pins the flat layers to bedrock, two dirt and
# grass, so the walkable surface - and therefore a turtle - is at y=-60.

scoreboard objectives add bm3d_boot dummy
scoreboard objectives add bm3d_init dummy
scoreboard players set #timer bm3d_boot 0

# Block entities only tick in a *ticking* chunk, and with no players connected nothing
# around spawn qualifies - the chunks are loaded, which is enough for the addon to find the
# turtles, but not enough for CC to run them. Without this they sit still forever and it
# looks like the mod is broken rather than the world.
forceload add -48 -48 80 80

# Spawn exactly once per world, tracked on a scoreboard because scoreboards persist.
#
# `setblock ... keep` is not enough: keep only declines to overwrite a non-air block, and by
# the second server start the turtles have wandered off their spawn points. Every restart
# then placed four more, and the map filled up with turtles.
execute unless score #done bm3d_init matches 1 run function bluemap3d_test:spawn
