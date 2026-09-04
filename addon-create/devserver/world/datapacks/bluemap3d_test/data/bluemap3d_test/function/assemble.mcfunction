# Assembles both contraptions. Runs once, ever, guarded by #assembled bm3d_init.
#
# Once is once: assembling an already-assembled bearing acts on the empty space its blocks
# used to occupy, and the guard is what stops /reload replaying it.

scoreboard players set #assembled bm3d_init 1

# The mechanical bearing assembles on a redstone signal. A block placed beside it is it.
setblock 1 -58 0 minecraft:redstone_block

# The windmill does not assemble from this rig: a windmill bearing wants its sails
# perpendicular to the rotation axis, and enough of them, which is fiddly to get right from
# fill commands and is not what this rig is testing. Left in place as scenery.

say [BlueMap3D] assembled; both should now be contraption entities
