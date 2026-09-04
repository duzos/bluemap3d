# One-time world build. Guarded by #built bm3d_init in init.mcfunction.
#
# Two contraptions, chosen for what they can tell apart rather than for looking good.

scoreboard players set #built bm3d_init 1

# ---------------------------------------------------------------------------------------
# The Slow Bearing, at x=0. A mechanical bearing driven by a creative motor.
#
# This is the transform test, and it is deliberately slow. A transposed rotation matrix
# and an under-sampled fast rotation look the same on a spinning object - both give you a
# structure facing the wrong way - so the thing that proves the matrix has to turn slowly
# enough that no sample can be ambiguous. At a few RPM each publish moves it a few degrees.
#
# The structure on top is deliberately asymmetric. A symmetrical one reads as correct even
# when the rotation is inverted, which is the whole failure this is here to catch.
#
# Only the block on the axis actually joins the contraption. Plain blocks do not propagate
# into one without super glue or a wrench-configured chassis, and neither can be set up from
# a command. The arms stay in the world as terrain, which is the static coloured L you see
# on the map beside the one block that really turns. See README.md - fixing this properly
# means building a structure by hand once and saving the world folder.
# ---------------------------------------------------------------------------------------

setblock 0 -59 0 create:creative_motor[facing=up]{ScrollValue:4}
setblock 0 -58 0 create:mechanical_bearing[facing=up]

# An L, one arm twice the length of the other, with a single block off one side. No axis
# of symmetry in any direction.
fill 0 -57 0 4 -57 0 minecraft:red_concrete
fill 0 -57 0 0 -57 2 minecraft:blue_concrete
setblock 1 -57 1 minecraft:yellow_concrete
setblock 4 -56 0 minecraft:lime_concrete

# ---------------------------------------------------------------------------------------
# The Windmill, at x=24. A windmill bearing with enough sail to turn itself.
#
# This is the aliasing case, not the correctness case. It turns fast enough that the
# default publishIntervalTicks of 10 undersamples it, which is worth seeing rather than
# being surprised by on someone's server.
# ---------------------------------------------------------------------------------------

setblock 24 -59 0 create:windmill_bearing[facing=up]
fill 24 -58 -3 24 -58 3 create:white_sail[facing=north]
fill 24 -57 -3 24 -57 3 create:white_sail[facing=north]
fill 24 -56 -3 24 -56 3 create:blue_sail[facing=north]

say [BlueMap3D] built the Slow Bearing and the Windmill
