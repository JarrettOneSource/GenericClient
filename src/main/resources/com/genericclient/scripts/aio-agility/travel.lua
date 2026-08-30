local config = gc.require("config")

local course = config.course

local function distance(a, b)
  if not a or a.plane ~= b.plane then return math.huge end
  return math.max(math.abs(a.x - b.x), math.abs(a.y - b.y))
end

local function in_course(world)
  return world and world.x >= course.zone.x1 and world.x <= course.zone.x2 and
    world.y >= course.zone.y1 and world.y <= course.zone.y2
end

local function walk(world, within)
  return gc.await {
    action = { type = "walk.to", destination = world, within = within or 4, run = true },
    breaks = true,
    timeout = { game_ticks = 600 },
  }
end

local function drain_femi()
  for _ = 1, 80 do
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      local receipt = gc.await {
        action = { type = "dialogue.continue" },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
      if receipt.status ~= "dispatched" then return nil, receipt end
      gc.await { event = "game.tick" }
    elseif dialogue.type == "choice" then
      local found = false
      for _, option in ipairs(dialogue.options or {}) do
        if option.text == "Okay then." then found = true end
      end
      if not found then return nil, { status = "unexpected_femi_dialogue", dialogue = dialogue } end
      local receipt = gc.await {
        action = { type = "dialogue.choose", text = "Okay then." },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
      if receipt.status ~= "dispatched" then return nil, receipt end
      gc.await { event = "game.tick" }
    else
      return true
    end
  end
  return nil, { status = "femi_dialogue_timeout", dialogue = gc.read("dialogue") }
end

local function open_gate()
  if gc.read("player").world.y >= 3384 then return true end
  for _ = 1, 2 do
    local gate = gc.read("objects", { id = course.gate.id, action = "Open", within = 10, limit = 1 })[1]
    if not gate then
      local entered = walk({ x = 2461, y = 3385, plane = 0 }, 1)
      if entered.status == "arrived" then return true end
      return nil, { status = "stronghold_gate_not_observed", receipt = entered }
    end
    local opened = gc.await {
      action = {
        type = "object.interact",
        id = gate.id,
        action = "Open",
        world = gate.world,
        within = 10,
      },
      breaks = true,
      timeout = { game_ticks = 40 },
    }
    if opened.status ~= "dispatched" then return nil, opened end
    for _ = 1, 8 do
      gc.await { event = "game.tick" }
      if gc.read("player").world.y >= 3384 then return true end
      if gc.read("dialogue").type ~= "closed" then
        local drained, failure = drain_femi()
        if not drained then return nil, failure end
        break
      end
    end
  end
  return nil, { status = "stronghold_gate_entry_unverified", player = gc.read("player") }
end

local function follow(route)
  local player = gc.read("player").world
  local start_index = 1
  local nearest = math.huge
  for index, waypoint in ipairs(route) do
    local candidate = distance(player, waypoint)
    if candidate < nearest then
      start_index = index
      nearest = candidate
    end
  end
  for index = start_index, #route do
    local receipt = walk(route[index], 4)
    if receipt.status ~= "arrived" then
      return nil, { status = "agility_travel_failed", waypoint = index, receipt = receipt }
    end
  end
  return true
end

local function ensure()
  local player = gc.read("player").world
  if in_course(player) then return true end
  gc.activity("travel")
  if player.y < 3384 then
    local reached, failure = follow(course.route)
    if not reached then return nil, failure end
    reached, failure = open_gate()
    if not reached then return nil, failure end
  end
  local reached, failure = follow(course.inside_route)
  if not reached then return nil, failure end
  if not in_course(gc.read("player").world) then
    return nil, { status = "agility_course_arrival_unverified", player = gc.read("player") }
  end
  return true
end

return { ensure = ensure, in_course = in_course }
