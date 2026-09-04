# Give the level a moment to come up, then assemble. Once, ever.

scoreboard players add #timer bm3d_step 1
execute if score #timer bm3d_step matches 60 unless score #assembled bm3d_init matches 1 run function bluemap3d_test:assemble
