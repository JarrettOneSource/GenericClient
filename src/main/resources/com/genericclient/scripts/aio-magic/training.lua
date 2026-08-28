local progress = gc.require("progress")
local supplies = gc.require("supplies")

local function distance(a, b)
  if a.plane ~= b.plane then
    return 99999
  end
  return math.max(math.abs(a.x - b.x), math.abs(a.y - b.y))
end

local function wait_ticks(count)
  return gc.await { ticks = count }
end

local function recover_hitpoints(target)
  local player = gc.read("player")
  if player.current_hitpoints > 4 then
    return true
  end
  if supplies.quantity(gc.read("inventory"), 1993) < 1 then
    return nil, { status = "low_hitpoints_no_food", hitpoints = player.current_hitpoints }
  end
  progress.show(target, "Recovering hitpoints")
  local drink = gc.await {
    action = { type = "item.interact", id = 1993, action = "Drink" },
    breaks = false,
    timeout = { game_ticks = 20 },
  }
  if drink.status ~= "dispatched" then
    return nil, { status = "food_action_failed", receipt = drink }
  end
  wait_ticks(2)
  return true
end

local function park_mouse()
  return gc.await { action = { type = "mouse.offscreen" }, breaks = false }
end

local function disengage(method)
  if not gc.read("player").interacting then
    return { status = "unchanged", result = "not_in_combat" }
  end
  return gc.await {
    action = { type = "walk.to", destination = method.disengage, within = 0 },
    breaks = false,
    timeout = { game_ticks = 60 },
  }
end

local function available_target(method)
  local player = gc.read("player")
  for _, name in ipairs(method.npc_names) do
    local npcs = gc.read("npcs", {
      where = {
        name = name,
        clickable = true,
        line_of_sight = true,
        dead = false,
      },
      action = "Attack",
      within = method.npc_radius,
      limit = 10,
    })
    for _, npc in ipairs(npcs) do
      if not npc.interacting or npc.interacting == player.name then
        return name
      end
    end
  end
  return nil
end

local function wait_for_target(method, ticks)
  for _ = 1, ticks do
    local name = available_target(method)
    if name then
      return name
    end
    gc.await { event = "game.tick" }
  end
  return nil
end

local function travel_to_method(method, target)
  local player = gc.read("player")
  local closest = 1
  local closest_distance = 99999
  for index, waypoint in ipairs(method.route) do
    local candidate_distance = distance(player.world, waypoint)
    if candidate_distance < closest_distance then
      closest = index
      closest_distance = candidate_distance
    end
  end
  for index = closest, #method.route do
    local waypoint = method.route[index]
    local within = index == #method.route and method.within or 6
    if distance(gc.read("player").world, waypoint) > within then
      progress.show(target, "Travelling")
      local receipt = gc.await {
        action = { type = "walk.to", destination = waypoint, within = within },
        timeout = { game_ticks = 600 },
      }
      if receipt.status ~= "arrived" then
        return nil, receipt
      end
    end
  end
  return true
end

return {
  recover_hitpoints = recover_hitpoints,
  park_mouse = park_mouse,
  disengage = disengage,
  available_target = available_target,
  wait_for_target = wait_for_target,
  travel_to_method = travel_to_method,
}
