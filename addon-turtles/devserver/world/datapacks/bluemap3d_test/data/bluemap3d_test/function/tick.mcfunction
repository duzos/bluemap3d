# A freshly placed turtle is off, and its startup.lua only runs when it boots. Booting is
# deferred rather than done in init: the block entities have to exist and have been
# assigned computer ids first, which does not happen within the tick that places them.

execute if score #timer bm3d_boot matches ..60 run scoreboard players add #timer bm3d_boot 1
execute if score #timer bm3d_boot matches 40 run function bluemap3d_test:boot
