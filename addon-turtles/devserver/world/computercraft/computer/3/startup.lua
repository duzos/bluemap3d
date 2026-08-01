-- BlueMap3D wanderer. Runs automatically when the turtle boots.
--
-- setComputerLabel is not decoration: the addon reads the label off the block entity and
-- publishes it, so a label appearing in the browser proves that path end to end.
os.setComputerLabel("Scout-04")

-- Seed per turtle, or all of them walk in lockstep and a stuck one is hard to spot.
math.randomseed(os.epoch("utc") + os.getComputerID() * 7919)

-- A bare turtle has no tool, so turtle.dig() just returns false and it never changes a
-- block. The datapack puts a diamond pickaxe in slot 1; equip it before doing anything.
local function equipTool()
  if turtle.getItemDetail(1) then
    turtle.select(1)
    turtle.equipLeft()
  end
  turtle.select(1)
end
equipTool()

-- Height is tracked by counting successful moves rather than read with gps.locate: that
-- needs a GPS network of four computers, which this world does not have, and it returns nil
-- without one.
local dy = 0

local function goUp()
  if dy < 4 and turtle.up() then
    dy = dy + 1
    return true
  end
  return false
end

local function goDown()
  if dy > -2 and turtle.down() then
    dy = dy - 1
    return true
  end
  return false
end

local function step()
  local roll = math.random(12)
  if roll == 1 then
    turtle.turnLeft()
  elseif roll == 2 then
    turtle.turnRight()
  elseif roll == 3 then
    if not goUp() then goDown() end
  elseif roll == 4 then
    if not goDown() then goUp() end
  elseif roll == 5 then
    -- Dig the block below, a real terrain change the map has to re-render.
    turtle.digDown()
  elseif roll == 6 then
    -- Place what it mined back down, another real terrain change.
    turtle.select(1)
    if turtle.getItemCount(1) > 0 then
      turtle.placeDown()
    end
  elseif not turtle.forward() then
    -- Blocked, usually by another turtle or by something it placed.
    if not turtle.dig() then
      turtle.turnRight()
    end
  end
end

print("BlueMap3D wanderer " .. os.getComputerID() .. " (" .. (os.getComputerLabel() or "?") .. ")")
while true do
  step()
  -- Slower than core's publish interval on purpose. A turtle that turns faster than it is
  -- sampled can show two 90 degree turns as one 180 degree turn, and a 180 degree rotation
  -- has no shorter way round - so it always resolves the same direction and reads as the
  -- turtle spinning the long way. Sampling has to be faster than turning; this is the other
  -- half of that.
  sleep(1.2)
end
