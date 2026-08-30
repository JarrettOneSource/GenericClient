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

local function npc(id, within)
  return gc.read("npcs", {
    id = id,
    within = within or 20,
    limit = 5,
  })[1]
end

local function has_action(object, wanted)
  for _, action in ipairs(object.actions or {}) do
    if action == wanted then return true end
  end
  return false
end

local function door_entry(target_world)
  local closest = nil
  local closest_distance = 99999
  for _, object in ipairs(gc.read("objects", { within = 20, limit = 80 })) do
    if object.name == "Door" and
      (has_action(object, "Open") or has_action(object, "Close")) then
      local separation = distance(object.world, target_world)
      if separation < closest_distance then
        closest = object
        closest_distance = separation
      end
    end
  end
  if not closest then return nil, nil end

  local dx = target_world.x - closest.world.x
  local dy = target_world.y - closest.world.y
  local entry = {
    x = closest.world.x,
    y = closest.world.y,
    plane = closest.world.plane,
  }
  if math.abs(dx) > math.abs(dy) then
    entry.x = entry.x + (dx > 0 and 1 or -1)
  else
    entry.y = entry.y + (dy > 0 and 1 or -1)
  end
  return closest, entry
end

local function cross_door(target_world, breaks)
  local door, entry = door_entry(target_world)
  if not door then return nil end

  local opened = nil
  if has_action(door, "Open") then
    local near = walk(door.world, 1, breaks, 120)
    if near.status ~= "arrived" then
      return { status = "door_approach_failed", door = door, receipt = near }
    end
    opened = gc.await {
      action = {
        type = "object.interact",
        id = door.id,
        action = "Open",
        world = door.world,
        within = 8,
      },
      breaks = breaks ~= false,
      timeout = { game_ticks = 40 },
    }
    if opened.status ~= "dispatched" then
      return { status = "door_open_failed", door = door, receipt = opened }
    end
    gc.await { event = "game.tick" }
  end

  local crossed = walk(entry, 0, breaks, 180)
  if crossed.status ~= "arrived" then
    return {
      status = "door_crossing_failed",
      door = door,
      entry = entry,
      opened = opened,
      receipt = crossed,
    }
  end
  return {
    status = "complete",
    door = door,
    entry = entry,
    opened = opened,
    crossed = crossed,
  }
end

local function reach_npc(id, fallback, breaks)
  local target = npc(id, 20)
  if not target then
    local near = approach(fallback, 3, breaks)
    if near.status ~= "arrived" then return nil, near end
    gc.await { event = "game.tick" }
    target = npc(id, 20)
  end
  if not target then
    return nil, {
      status = "rejected",
      result = "npc_not_observed_after_approach",
      id = id,
      fallback = fallback,
      nearby = gc.read("npcs", { within = 20, limit = 30 }),
    }
  end

  if target.distance > 2 or not target.line_of_sight then
    local reached = nil
    local door_crossing = nil
    if not target.line_of_sight then
      door_crossing = cross_door(target.world, breaks)
    end
    if door_crossing then
      if door_crossing.status ~= "complete" then
        return nil, {
          status = "npc_door_crossing_failed",
          target = target,
          receipt = door_crossing,
        }
      end
      reached = door_crossing.crossed
    else
      reached = walk(target.world, target.line_of_sight and 2 or 0, breaks, 180)
    end
    if reached.status ~= "arrived" then
      return nil, {
        status = "npc_approach_failed",
        target = target,
        door_crossing = door_crossing,
        receipt = reached,
      }
    end
    gc.await { event = "game.tick" }
    target = npc(id, 20)
    if not target then
      return nil, {
        status = "rejected",
        result = "npc_moved_after_approach",
        id = id,
      }
    end
  end
  return target
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

local function reachability_failure(since_tick)
  if not since_tick then return nil end
  for _, message in ipairs(gc.read("messages", { since_tick = since_tick, limit = 20 })) do
    local text = string.lower(message.text or "")
    if string.find(text, "can't reach", 1, true) or
      string.find(text, "can't get there", 1, true) then
      return message
    end
  end
  return nil
end

local function finish_dialogue(predicate, choices, breaks, ticks, started_tick)
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
    else
      local failure = reachability_failure(started_tick)
      if failure then
        return nil, {
          status = "rejected",
          result = "interaction_unreachable",
          message = failure,
        }
      end
    end
  end
  return nil, { status = "timed_out", result = "dialogue_progress_timeout", varp = varp() }
end

local function talk(id, world, predicate, choices, breaks)
  local allow_breaks = breaks ~= false
  local target, approach_failure = reach_npc(id, world, allow_breaks)
  if not target then return approach_failure end
  local started_tick = gc.read("runtime").game_tick
  local clicked = gc.await {
    action = { type = "npc.interact", id = id, action = "Talk-to", within = 12 },
    breaks = allow_breaks,
    timeout = { game_ticks = 40 },
  }
  if clicked.status ~= "dispatched" then return clicked end
  local dialogue, failure = finish_dialogue(
    predicate, choices, allow_breaks, 100, started_tick)
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
  npc = npc,
  cross_door = cross_door,
  varp = varp,
  varbit = varbit,
  finish_dialogue = finish_dialogue,
  talk = talk,
  object = object,
  object_action = object_action,
}
