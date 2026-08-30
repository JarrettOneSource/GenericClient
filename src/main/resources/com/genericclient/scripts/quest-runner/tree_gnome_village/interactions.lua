local config = gc.require("tree_gnome_config")

local function distance(a, b)
  if not a or not b or a.plane ~= b.plane then return 99999 end
  return math.max(math.abs(a.x - b.x), math.abs(a.y - b.y))
end

local function wait_for(predicate, ticks)
  for _ = 1, ticks do
    gc.await { event = "game.tick" }
    if predicate() then return true end
  end
  return false
end

local function walk(world, within, breaks, ticks)
  return gc.await {
    action = {
      type = "walk.to",
      destination = world,
      within = within or 3,
      run = true,
    },
    breaks = breaks ~= false,
    timeout = { game_ticks = ticks or 900 },
  }
end

local function approach(world, within, breaks)
  if distance(gc.read("player").world, world) <= (within or 3) then
    return { status = "arrived", result = "already_near_target" }
  end
  return walk(world, within, breaks)
end

local function quantity(container, id)
  local total = 0
  for _, item in ipairs((container and container.items) or {}) do
    if item.id == id then total = total + item.quantity end
  end
  return total
end

local function carried(id)
  return quantity(gc.read("inventory"), id) + quantity(gc.read("equipment"), id)
end

local function vars()
  return gc.read("vars", {
    varps = { 111 },
    varbits = {
      config.varbits.bolren_got_orbs,
      config.varbits.tracker_height,
      config.varbits.tracker_y,
      config.varbits.tracker_x,
      config.varbits.ballista,
    },
  })
end

local function varp()
  return vars().varps[111]
end

local function varbit(id)
  return vars().varbits[id]
end

local function choose(dialogue, choices, breaks)
  for _, wanted in ipairs(choices or {}) do
    for _, option in ipairs(dialogue.options) do
      if option.text == wanted then
        return gc.await {
          action = { type = "dialogue.choose", text = option.text },
          breaks = breaks,
          timeout = { game_ticks = 20 },
        }
      end
    end
  end
  return {
    status = "rejected",
    result = "unexpected_dialogue_choice",
    dialogue = dialogue,
  }
end

local function finish_dialogue(predicate, choices, breaks, ticks)
  local progressed = false
  local closed_ticks = 0
  local receipts = {}
  for _ = 1, ticks or 80 do
    gc.await { event = "game.tick" }
    progressed = progressed or predicate()
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      closed_ticks = 0
      local receipt = gc.await {
        action = { type = "dialogue.continue" },
        breaks = breaks,
        timeout = { game_ticks = 20 },
      }
      receipts[#receipts + 1] = receipt
      if receipt.status ~= "dispatched" then return nil, receipt end
    elseif dialogue.type == "choice" then
      closed_ticks = 0
      local receipt = choose(dialogue, choices, breaks)
      receipts[#receipts + 1] = receipt
      if receipt.status ~= "dispatched" then return nil, receipt end
    elseif progressed then
      closed_ticks = closed_ticks + 1
      if closed_ticks >= 2 then return receipts end
    end
  end
  return nil, { status = "timed_out", result = "dialogue_progress_timeout", varp = varp() }
end

local function talk(id, world, predicate, choices, breaks)
  local allow_breaks = breaks ~= false
  local near = approach(world, 3, allow_breaks)
  if near.status ~= "arrived" then return near end
  local clicked = gc.await {
    action = { type = "npc.interact", id = id, action = "Talk-to", within = 12 },
    breaks = allow_breaks,
    timeout = { game_ticks = 40 },
  }
  if clicked.status ~= "dispatched" then return clicked end
  local dialogue, failure = finish_dialogue(predicate, choices, allow_breaks, 100)
  if not dialogue then return failure end
  return {
    status = "complete",
    result = "dialogue_progress_verified",
    receipt = clicked,
    dialogue = dialogue,
  }
end

local function object(id, action, within)
  local found = gc.read("objects", {
    id = id,
    action = action,
    within = within or 16,
    limit = 10,
  })
  return found[1]
end

local function object_action(id, action, point, predicate, breaks, within)
  local allow_breaks = breaks ~= false
  local near = approach(point, 3, allow_breaks)
  if near.status ~= "arrived" then return near end
  gc.await { event = "game.tick" }
  local target = object(id, action, within)
  if not target then
    return {
      status = "rejected",
      result = "object_not_observed",
      id = id,
      action = action,
      nearby = gc.read("objects", { within = within or 12, limit = 30 }),
    }
  end
  local clicked = gc.await {
    action = {
      type = "object.interact",
      id = id,
      action = action,
      world = target.world,
      within = within or 16,
    },
    breaks = allow_breaks,
    timeout = { game_ticks = 40 },
  }
  if clicked.status ~= "dispatched" then return clicked end
  if not wait_for(predicate, 40) then
    return { status = "timed_out", result = "object_result_unverified", receipt = clicked }
  end
  return { status = "complete", result = "object_result_verified", receipt = clicked }
end

return {
  distance = distance,
  wait_for = wait_for,
  walk = walk,
  approach = approach,
  quantity = quantity,
  carried = carried,
  varp = varp,
  varbit = varbit,
  finish_dialogue = finish_dialogue,
  talk = talk,
  object = object,
  object_action = object_action,
}
