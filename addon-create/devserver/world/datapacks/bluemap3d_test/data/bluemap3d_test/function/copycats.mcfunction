# The Copycat rig. Run by hand, once: `function bluemap3d_test:copycats`.
#
# Not called from init or tick, because it is a test of one specific thing - that a copycat
# on a contraption is drawn in the material it is skinned in, with the right shape - and it
# needs Copycats+ on the server for most of its blocks. The Create copycats in it work
# without it. Running it twice stacks a second rig over the first
# one's leftovers, so kill the old contraption entity first.
#
# At x=-36, z=-20: clear grass, away from the Slow Bearing (x=0), the Windmill (x=24) and the
# test track, whose parked train sits right where an earlier spot at x=-26 had it. A creative motor
# turns a mechanical bearing, and the contraption it carries is a seven-wide, three-high wall
# of copycats glued together. The bearing's block is the one stone in the bottom row: a
# bearing only picks up the block on its axis, and the glue does the rest.
#
#            x=-39   -38    -37    -36    -35    -34    -33
#   y=-55 :  slope   slope  vslope layer2 layer6 pipe   glass
#   y=-56 :  board   slab2  bytes  cogwh  bare   block  (air)
#   y=-57 :  panel   step   slab   STONE  stairs beam   vstep
#
# Each one is there to tell something apart:
#   panel   oak planks            - a Create copycat, material and shape from the single tag
#   step    bricks                - the other Create copycat, half=bottom
#   slab    bottom: diamond       - Copycats+ multi-material, only one of its two parts exists
#   stairs  oak log, axis y       - a single-material Copycats+ copycat, with a rotated texture
#   beam    gold                  - a single-material beam along x
#   vstep   emerald               - a single-material vertical step
#   board   down: lapis           - six face plates, one skinned, five bare (frame texture)
#   slab2   top emerald, bottom iron - type=double, two materials in one block
#   bytes   four corners, four materials - a checker, parts in eight cells
#   cogwh   cogwheel bricks, shaft gold - a cog with teeth and a shaft in its own material
#   bare    no material at all    - should be the copycat frame texture, not a grey cube
#   block   grass block           - a tinted material on a full copycat
#   slope   north/bottom bricks, east/top planks - the high edge is on the side it faces, top is flipped
#   vslope  gold, facing south    - full faces on south and east, one diagonal
#   layer2  diamond, south        - a ramp 0 to 8px; layer6 emerald, west - a ramp on a slab, 8 to 16px
#   pipe    iron, north+south+up  - a three-way fluid pipe: core and three arms, no rims
#   glass   gold, axis z          - the open frame of a glass pipe
#
# The consumed item has to be set as well as the material, or the block entity treats the
# material as unset and resets it to the frame when it loads.

# ---------------------------------------------------------------------------------------
# The drive: motor under a bearing, as for the Slow Bearing. Outside init's forceload box,
# so load the chunks first or nothing here exists with no player nearby.
# ---------------------------------------------------------------------------------------

forceload add -48 -32 -16 -16
setblock -36 -59 -20 create:creative_motor[facing=up]{ScrollValue:4}
setblock -36 -58 -20 create:mechanical_bearing[facing=up]

# ---------------------------------------------------------------------------------------
# Bottom row, y=-57.
# ---------------------------------------------------------------------------------------

setblock -39 -57 -20 create:copycat_panel[facing=up]{Item:{id:"minecraft:oak_planks",count:1},Material:{Name:"minecraft:oak_planks"}}
setblock -38 -57 -20 create:copycat_step[facing=north,half=bottom]{Item:{id:"minecraft:bricks",count:1},Material:{Name:"minecraft:bricks"}}
setblock -37 -57 -20 copycats:copycat_slab[type=bottom,axis=y]{material_data:{bottom:{material:{Name:"minecraft:diamond_block"},consumedItem:{id:"minecraft:diamond_block",count:1},enableCT:1b}}}
setblock -36 -57 -20 minecraft:stone
setblock -35 -57 -20 copycats:copycat_stairs[facing=east,half=bottom,shape=straight]{Item:{id:"minecraft:oak_log",count:1},Material:{Name:"minecraft:oak_log",Properties:{axis:"y"}}}
setblock -34 -57 -20 copycats:copycat_beam[axis=x]{Item:{id:"minecraft:gold_block",count:1},Material:{Name:"minecraft:gold_block"}}
setblock -33 -57 -20 copycats:copycat_vertical_step[facing=north]{Item:{id:"minecraft:emerald_block",count:1},Material:{Name:"minecraft:emerald_block"}}

# ---------------------------------------------------------------------------------------
# Top row, y=-56.
# ---------------------------------------------------------------------------------------

setblock -39 -56 -20 copycats:copycat_board[down=true,up=false,north=false,south=false,east=false,west=false]{material_data:{down:{material:{Name:"minecraft:lapis_block"},consumedItem:{id:"minecraft:lapis_block",count:1},enableCT:1b}}}
setblock -38 -56 -20 copycats:copycat_slab[type=double,axis=y]{material_data:{top:{material:{Name:"minecraft:emerald_block"},consumedItem:{id:"minecraft:emerald_block",count:1},enableCT:1b},bottom:{material:{Name:"minecraft:iron_block"},consumedItem:{id:"minecraft:iron_block",count:1},enableCT:1b}}}
setblock -37 -56 -20 copycats:copycat_byte[top_northeast=true,top_northwest=false,top_southeast=false,top_southwest=true,bottom_northeast=false,bottom_northwest=true,bottom_southeast=true,bottom_southwest=false]{material_data:{top_northeast:{material:{Name:"minecraft:diamond_block"},consumedItem:{id:"minecraft:diamond_block",count:1},enableCT:1b},top_southwest:{material:{Name:"minecraft:gold_block"},consumedItem:{id:"minecraft:gold_block",count:1},enableCT:1b},bottom_northwest:{material:{Name:"minecraft:emerald_block"},consumedItem:{id:"minecraft:emerald_block",count:1},enableCT:1b},bottom_southeast:{material:{Name:"minecraft:lapis_block"},consumedItem:{id:"minecraft:lapis_block",count:1},enableCT:1b}}}
setblock -36 -56 -20 copycats:copycat_cogwheel[axis=y]{material_data:{cogwheel:{material:{Name:"minecraft:bricks"},consumedItem:{id:"minecraft:bricks",count:1},enableCT:1b},shaft:{material:{Name:"minecraft:gold_block"},consumedItem:{id:"minecraft:gold_block",count:1},enableCT:1b}}}
setblock -35 -56 -20 create:copycat_step[facing=east,half=top]
setblock -34 -56 -20 copycats:copycat_block{Item:{id:"minecraft:grass_block",count:1},Material:{Name:"minecraft:grass_block"}}

# ---------------------------------------------------------------------------------------
# Third row, y=-55: the shapes that are not boxes.
#
# The glass pipe goes in before the fluid pipe, not after. A pipe reshapes its connections
# when a neighbour changes, and placing a block beside it afterwards could rewrite the state
# set here.
# ---------------------------------------------------------------------------------------

setblock -39 -55 -20 copycats:copycat_slope[facing=north,half=bottom]{Item:{id:"minecraft:bricks",count:1},Material:{Name:"minecraft:bricks"}}
setblock -38 -55 -20 copycats:copycat_slope[facing=east,half=top]{Item:{id:"minecraft:oak_planks",count:1},Material:{Name:"minecraft:oak_planks"}}
setblock -37 -55 -20 copycats:copycat_vertical_slope[facing=south]{Item:{id:"minecraft:gold_block",count:1},Material:{Name:"minecraft:gold_block"}}
setblock -36 -55 -20 copycats:copycat_slope_layer[facing=south,half=bottom,layers=2]{Item:{id:"minecraft:diamond_block",count:1},Material:{Name:"minecraft:diamond_block"}}
setblock -35 -55 -20 copycats:copycat_slope_layer[facing=west,half=bottom,layers=6]{Item:{id:"minecraft:emerald_block",count:1},Material:{Name:"minecraft:emerald_block"}}
setblock -33 -55 -20 copycats:copycat_glass_fluid_pipe[axis=z]{Item:{id:"minecraft:gold_block",count:1},Material:{Name:"minecraft:gold_block"}}
setblock -34 -55 -20 copycats:copycat_fluid_pipe[north=true,south=true,east=false,west=false,up=true,down=false]{Item:{id:"minecraft:iron_block",count:1},Material:{Name:"minecraft:iron_block"}}

# ---------------------------------------------------------------------------------------
# Glue, then assembly.
#
# The super glue entity goes in before the bearing is triggered, or the bearing assembles
# on its own block and nothing else. Pos is the middle of the glued box and From/To are
# relative to it, so the box is x -39..-32, y -57..-54, z -20..-19, i.e. the seven by three by
# one block wall above.
# ---------------------------------------------------------------------------------------

summon create:super_glue -35.5 -57 -19.5 {From:[-3.5d,0.0d,-0.5d],To:[3.5d,3.0d,0.5d]}

# The bearing assembles on a redstone signal. A block placed beside it is one.
setblock -35 -58 -20 minecraft:redstone_block

say [BlueMap3D] built and assembled the Copycat rig at x=-36, z=-20
