# Assemble once the level is up, then keep the arena moving for as long as the server runs.
#
# The thrust runs every ten ticks rather than every one. Every tick is both wasteful and
# worse to look at: the impulses stack faster than the hover pad's friction can bleed them
# off, and everything ends up pinned to the glass.

scoreboard players add #timer bm3d_step 1
execute if score #timer bm3d_step matches 60 unless score #assembled bm3d_init matches 1 run function bluemap3d_test:assemble
execute if score #timer bm3d_step matches 120.. if score #assembled bm3d_init matches 1 run function bluemap3d_test:pulse
