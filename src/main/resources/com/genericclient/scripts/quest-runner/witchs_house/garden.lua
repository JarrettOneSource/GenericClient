local function walk(x, y, timeout, run)
  return gc.await {
    action = {
      type = "walk.to",
      destination = { x = x, y = y, plane = 0 },
      within = 0,
      run = run,
    },
    breaks = false,
    timeout = { game_ticks = timeout or 300 },
  }
end

local function witch()
  return gc.read("npcs", { id = 3995, within = 100, limit = 1 })[1]
end

local function wait_absent(ticks, timeout)
  local absent = 0
  for _ = 1, timeout do
    gc.await { event = "game.tick" }
    absent = witch() and 0 or absent + 1
    if absent >= ticks then
      return true
    end
  end
  return false
end

local function wait_moving_west(maximum_x, timeout)
  local previous = nil
  for _ = 1, timeout do
    gc.await { event = "game.tick" }
    local npc = witch()
    if npc then
      local x = npc.world.x
      if previous and x <= maximum_x and x < previous then
        return true
      end
      previous = x
    end
  end
  return false
end

local function wait_moving_east(minimum_x, timeout)
  local previous = nil
  for _ = 1, timeout do
    gc.await { event = "game.tick" }
    local npc = witch()
    if npc then
      local x = npc.world.x
      if previous and x >= minimum_x and x > previous then
        return true
      end
      previous = x
    end
  end
  return false
end

local function wait_at_most(maximum_x, timeout)
  for _ = 1, timeout do
    gc.await { event = "game.tick" }
    local npc = witch()
    if npc and npc.world.x <= maximum_x then
      return true
    end
  end
  return false
end

local function quantity(id)
  local total = 0
  for _, item in ipairs(gc.read("inventory").items) do
    if item.id == id then
      total = total + item.quantity
    end
  end
  return total
end

local function move(receipts, x, y, timeout, run)
  local receipt = walk(x, y, timeout, run)
  table.insert(receipts, receipt)
  if receipt.status ~= "arrived" then
    return nil, { status = "garden_walk_failed", destination = { x = x, y = y }, receipt = receipt }
  end
  local world = gc.read("player").world
  if world.x < 2900 or world.x > 2933 or world.y < 3459 or world.y > 3475 then
    return nil, { status = "garden_caught", reached = world, receipts = receipts }
  end
  return true
end

local function require_run_reserve(minimum, timeout)
  for _ = 1, timeout do
    if gc.read("player").run_energy >= minimum then return true end
    gc.await { event = "game.tick" }
  end
  return nil, {
    status = "garden_run_reserve_timeout",
    required = minimum,
    run_energy = gc.read("player").run_energy,
  }
end

local function require_window(observed, name, receipts)
  if observed then
    return true
  end
  return nil, { status = "garden_window_timeout", window = name, receipts = receipts }
end

local function reach_east_cover(receipts)
  local player = gc.read("player").world
  if math.max(math.abs(player.x - 2901), math.abs(player.y - 3464)) > 20 then
    local staged = gc.await {
      action = {
        type = "walk.to",
        destination = { x = 2928, y = 3456, plane = 0 },
        within = 0,
        run = false,
      },
      breaks = false,
      timeout = { game_ticks = 1200 },
    }
    table.insert(receipts, staged)
    if staged.status ~= "arrived" then
      return nil, { status = "garden_stage_failed", receipt = staged, receipts = receipts }
    end
  end
  local ok, failure = move(receipts, 2901, 3464, 1200, false)
  if not ok then return nil, failure end
  ok, failure = require_run_reserve(2000, 100)
  if not ok then return nil, failure end
  ok, failure = move(receipts, 2901, 3460)
  if not ok then return nil, failure end

  ok, failure = require_window(wait_absent(3, 140), "west_entry_east_absent", receipts)
  if not ok then return nil, failure end
  ok, failure = move(receipts, 2908, 3460)
  if not ok then return nil, failure end

  ok, failure = require_window(wait_moving_west(2908, 140), "south_first_west", receipts)
  if not ok then return nil, failure end
  ok, failure = move(receipts, 2916, 3460)
  if not ok then return nil, failure end

  ok, failure = require_window(wait_at_most(2910, 50), "south_second_west", receipts)
  if not ok then return nil, failure end
  ok, failure = move(receipts, 2924, 3460)
  if not ok then return nil, failure end

  ok, failure = require_window(wait_at_most(2918, 60), "south_third_west", receipts)
  if not ok then return nil, failure end
  ok, failure = move(receipts, 2931, 3460)
  if not ok then return nil, failure end

  ok, failure = require_window(wait_moving_west(2918, 140), "east_turn_west", receipts)
  if not ok then return nil, failure end
  ok, failure = move(receipts, 2933, 3466)
  if not ok then return nil, failure end
  return true
end

local function execute()
  local receipts = {}
  local ok, failure = reach_east_cover(receipts)
  if not ok then return failure end
  ok, failure = move(receipts, 2927, 3466)
  if not ok then return failure end

  ok, failure = require_window(wait_absent(3, 140), "north_first_west_absent", receipts)
  if not ok then return failure end
  ok, failure = move(receipts, 2920, 3466)
  if not ok then return failure end

  ok, failure = require_window(wait_moving_east(2928, 140), "north_second_east", receipts)
  if not ok then return failure end
  ok, failure = move(receipts, 2913, 3466)
  if not ok then return failure end
  ok, failure = move(receipts, 2912, 3466)
  if not ok then return failure end
  ok, failure = move(receipts, 2911, 3467)
  if not ok then return failure end
  ok, failure = move(receipts, 2911, 3468)
  if not ok then return failure end

  local fountain = gc.await {
    action = {
      type = "object.interact",
      id = 2864,
      action = "Check",
      world = { x = 2909, y = 3470, plane = 0 },
      within = 4,
    },
    breaks = false,
  }
  table.insert(receipts, fountain)
  if fountain.status ~= "dispatched" then
    return { status = "garden_fountain_failed", receipt = fountain, receipts = receipts }
  end
  for _ = 1, 12 do
    gc.await { event = "game.tick" }
    if quantity(2411) > 0 then
      return { status = "complete", result = "garden_key_obtained", receipts = receipts }
    end
  end
  return { status = "garden_key_unverified", receipts = receipts }
end

local function to_shed()
  local receipts = {}
  local player = gc.read("player").world
  if player.x >= 2934 and player.x <= 2937 and player.y >= 3459 and player.y <= 3467 then
    return { status = "complete", result = "already_in_shed", receipts = receipts }
  end
  if player.x == 2933 and player.y >= 3459 and player.y <= 3466 then
    return { status = "complete", result = "shed_door_reached", receipts = receipts }
  end
  if player.x >= 2900 and player.x <= 2933 and player.y >= 3459 and player.y <= 3475 and
    not (player.x <= 2902 and player.y <= 3465) then
    return {
      status = "garden_resume_requires_observed_route",
      world = player,
      receipts = receipts,
    }
  end
  local ok, failure = reach_east_cover(receipts)
  if not ok then return failure end
  ok, failure = move(receipts, 2933, 3463)
  if not ok then return failure end
  return { status = "complete", result = "shed_door_reached", receipts = receipts }
end

return { execute = execute, to_shed = to_shed }
