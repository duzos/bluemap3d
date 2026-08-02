# Keeps the arena moving. Runs one tick in ten, forever.
#
# Real physics, not a scripted pose. Almost everything in here is a thrust; where anything
# ends up is Sable's business, and so is what happens when two of them arrive in the same
# place - which is the point. Objects rebound off each other and off the glass, tumble, and
# settle at whatever attitude the last collision left them in.
#
# An impulse is mass times the speed change it buys, so every thrust is banded by mass. A
# copper block is a four hundredth of the iron sphere; one number for both either flings the
# block through the wall or does nothing at all to the sphere. The bands come from what is
# actually in here - blocks at 1 to 1.5, the pips and the Gull at 130 to 190, the iron sphere
# at 492 - and each number is "roughly this much speed change" times a mass from the middle
# of its band.
#
# `x=-55..55` is how the Dry Dock is kept out of it: the arena runs to x=48 and the hut sits
# at x=-67. It is the control, and a control that gets shoved is not one. Not `distance`,
# which is the obvious way to write it and does not mean blocks - `distance=..60` from the
# middle of the arena picks out one object in forty-five.

# --- Lift ----------------------------------------------------------------------------------
# Half a second of gravity is about 4.9 m/s down, so this is a little over that: enough to
# get off the floor and keep climbing, not enough to look like a rocket. Ungated on speed,
# because a falling object is moving too fast to pass a speed gate and would never be caught.
#
# Gated on height instead. Below y=-42 an object is under thrust; above it, it is not, so it
# arcs over and comes back down. That is what stops the arena piling up against the sky.
sable physics impulse @e[x=-55..55,y=..-42,mass=..4] linear 0 8 0
sable physics impulse @e[x=-55..55,y=..-42,mass=4..300] linear 0 1050 0
sable physics impulse @e[x=-55..55,y=..-42,mass=300..] linear 0 2900 0

# --- Drift ---------------------------------------------------------------------------------
# Along each object's own axes, so a tumbling object is pushed somewhere new every time and
# no two end up on the same track. Gated on speed so nothing accelerates without limit and
# the arena settles into a steady bumbling rather than a blur.
sable physics impulse @e[x=-55..55,speed=..4,mass=..4] linear 3 0 1 local
sable physics impulse @e[x=-55..55,speed=..4,mass=4..300] linear 380 0 120 local
sable physics impulse @e[x=-55..55,speed=..4,mass=300..] linear 1100 0 350 local

# --- Spin ----------------------------------------------------------------------------------
# So nothing stays axis-aligned. A ship rotated about the wrong pivot swings instead of
# turning, and that only shows up when it is turning about more than one axis at once.
#
# The light band gets a fraction of the heavy one for the same reason the thrust does, only
# more so: a single copper block's moment of inertia is small enough that a whole unit of
# angular impulse leaves it spinning too fast to read.
sable physics impulse @e[x=-55..55,speed=..5,mass=..4] angular 0.05 0.1 0.05 local
sable physics impulse @e[x=-55..55,speed=..5,mass=4..] angular 45 100 35 local

# --- Waking the dead -----------------------------------------------------------------------
# Rapier puts a body that has come to rest to sleep, and a sleeping body ignores impulses -
# measured: three thousand units of thrust into a settled sphere does nothing at all. Left
# to itself the arena runs down over a couple of minutes until only whatever is still in the
# air is moving.
#
# So anything that has stopped gets its pose written instead, which always takes. It is a
# shove rather than a thrust, and it does two things: the object itself starts moving again,
# and shoving it into its neighbours makes contacts, which is what wakes them. From there the
# physics has it back.
#
# Mass does not come into it - a pose write moves a copper block and an iron sphere by the
# same amount - so this is the one section that needs no bands.
sable physics translation @e[x=-55..55,speed=..0.3] add 0.4 0.25 0 local
sable physics rotation @e[x=-55..55,speed=..0.3] add axis 0.4 1 0.2 4 local
