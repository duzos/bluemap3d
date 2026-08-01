# Switch the five turtles on, and give each one a different pair of upgrades.
#
# Not `computercraft turn-on`: that command only acts on computers CC has already
# registered, and CC registers a turtle's computer when it first boots. With never-booted
# turtles it reports "Turned on 0/0 computers" and does nothing - a chicken-and-egg the
# command cannot break.
#
# Writing On:1b works because `data merge block` reloads the block entity from nbt, which
# runs CC's own load path and creates the computer on the next tick. This is also what
# assigns each turtle its ComputerId, which is the identity the addon publishes - and the
# order of these lines is what makes the ids line up with computer/0..4/startup.lua.
#
# Every turtle gets a diamond pickaxe on the left, because a bare turtle cannot dig at all
# and the quarry would do nothing. The right side varies, to cover both ways CC models an
# upgrade:
#
#   - modem, speaker and crafting table are "sided" upgrades with real json geometry, so
#     they should render exactly as they do in game;
#   - a second tool is a "flat item" upgrade with no geometry at all, so it falls back to
#     CC's mount plate wearing the tool's sprite.
#
# LeftUpgrade/RightUpgrade take the upgrade id. For tools that is the item id itself.

# Rambler-01: pickaxe + wireless modem
data merge block 3 -60 0 {On:1b, Items:[{Slot:0b, id:"minecraft:diamond_pickaxe", count:1}], LeftUpgrade:{id:"minecraft:diamond_pickaxe"}, RightUpgrade:{id:"computercraft:wireless_modem_normal"}}

# Rambler-02: pickaxe + speaker
data merge block -3 -60 0 {On:1b, Items:[{Slot:0b, id:"minecraft:diamond_pickaxe", count:1}], LeftUpgrade:{id:"minecraft:diamond_pickaxe"}, RightUpgrade:{id:"computercraft:speaker"}}

# Hauler-03: pickaxe + crafting table
data merge block 0 -60 3 {On:1b, Items:[{Slot:0b, id:"minecraft:diamond_pickaxe", count:1}], LeftUpgrade:{id:"minecraft:diamond_pickaxe"}, RightUpgrade:{id:"minecraft:crafting_table"}}

# Scout-04: pickaxe + advanced modem
data merge block 0 -60 -3 {On:1b, Items:[{Slot:0b, id:"minecraft:diamond_pickaxe", count:1}], LeftUpgrade:{id:"minecraft:diamond_pickaxe"}, RightUpgrade:{id:"computercraft:wireless_modem_advanced"}}

# Quarry-05: pickaxe + sword, so one turtle shows the flat-item fallback on both sides.
data merge block 41 -49 41 {On:1b, Items:[{Slot:0b, id:"minecraft:diamond_pickaxe", count:1}], LeftUpgrade:{id:"minecraft:diamond_pickaxe"}, RightUpgrade:{id:"minecraft:diamond_sword"}}

say [BlueMap3D] booted 4 ramblers and 1 quarry, each with different upgrades
