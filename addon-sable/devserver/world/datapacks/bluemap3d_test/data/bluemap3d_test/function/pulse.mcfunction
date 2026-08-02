# One tick in ten runs the thrust; the rest of the time the physics is left alone to get
# on with it. The counter rolls at 130 so the arithmetic stays in a range a scoreboard
# reads at a glance.

scoreboard players add #beat bm3d_step 1
execute if score #beat bm3d_step matches 10.. run function bluemap3d_test:sail
execute if score #beat bm3d_step matches 10.. run scoreboard players set #beat bm3d_step 0
