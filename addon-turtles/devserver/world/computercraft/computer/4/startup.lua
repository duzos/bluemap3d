-- BlueMap3D quarry. Runs automatically when the turtle boots.
--
-- The other four wander and poke at the terrain; this one does the thing that actually
-- stresses tile refreshing: it strip-mines a solid block of stone, changing hundreds of
-- blocks inside a handful of BlueMap tiles. If the map keeps up with this, the coalescing in
-- core's TileRefreshQueue is doing its job. If it falls behind or floods the render queue,
-- this is where it shows.
os.setComputerLabel("Quarry-05")

-- Kept small on purpose. A turtle move has a server-side cooldown of about 0.4s, so a
-- 16-wide layer takes minutes and it is not obvious whether the quarry is working or stuck.
local WIDTH = 6   -- blocks per row
local ROWS = 6    -- rows per layer
local LAYERS = 8  -- layers down

-- A bare turtle cannot dig at all - turtle.dig() just returns false - so without this the
-- quarry does precisely nothing. The datapack leaves a diamond pickaxe in slot 1.
if turtle.getItemDetail(1) then
  turtle.select(1)
  turtle.equipLeft()
end
turtle.select(1)

local function dump()
  -- Cobblestone piles up fast. Drop it rather than stopping when full, since a full turtle
  -- stops mining and the test quietly turns into a stationary turtle.
  for slot = 1, 16 do
    if turtle.getItemCount(slot) > 0 then
      turtle.select(slot)
      turtle.dropDown()
    end
  end
  turtle.select(1)
end

local function forward()
  -- Dig first rather than moving and only digging on failure: the turtle starts embedded in
  -- stone, so every step is blocked, and detect-then-dig also handles gravel or sand falling
  -- into the space just cleared.
  local attempts = 0
  while turtle.detect() and attempts < 8 do
    if not turtle.dig() then
      break
    end
    attempts = attempts + 1
  end
  return turtle.forward()
end

local function row()
  for _ = 1, WIDTH - 1 do
    forward()
  end
end

local function layer(turnRight)
  for r = 1, ROWS do
    row()
    if r < ROWS then
      if turnRight then
        turtle.turnRight(); forward(); turtle.turnRight()
      else
        turtle.turnLeft(); forward(); turtle.turnLeft()
      end
      turnRight = not turnRight
    end
  end
  return turnRight
end

print("BlueMap3D quarry starting")
local turnRight = true
for l = 1, LAYERS do
  turnRight = layer(turnRight)
  turtle.digDown()
  turtle.down()
  dump()
  -- Turn around to start the next layer back across the cleared area.
  turtle.turnRight()
  turtle.turnRight()
  sleep(0.5)
end

print("BlueMap3D quarry finished; idling")
while true do
  sleep(30)
end
