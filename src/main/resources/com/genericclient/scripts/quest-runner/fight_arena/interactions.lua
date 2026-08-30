local config = gc.require("fight_arena_config")

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

local function id_list(ids)
  return type(ids) == "table" and ids or { ids }
end

local function npc(ids, within)
  for _, id in ipairs(id_list(ids)) do
    local found = gc.read("npcs", { id = id, within = within or 20, limit = 5 })[1]
    if found then return found end
  end
  return nil
end

local function reach_npc(ids, fallback, breaks)
  local target = npc(ids, 20)
  if not target then
    local near = approach(fallback, 3, breaks)
    if near.status ~= "arrived" then return nil, near end
    gc.await { event = "game.tick" }
    target = npc(ids, 20)
  end
  if not target then
    return nil, {
      status = "rejected",
      result = "npc_not_observed_after_approach",
      ids = id_list(ids),
      fallback = fallback,
      nearby = gc.read("npcs", { within = 20, limit = 30 }),
    }
  end
  if target.distance > 2 or not target.line_of_sight or not target.clickable then
    local reached = walk(target.world, 2, breaks, 180)
    if reached.status ~= "arrived" then
      return nil, { status = "npc_approach_failed", target = target, receipt = reached }
    end
    gc.await { event = "game.tick" }
    target = npc(ids, 20)
  end
  if not target then
    return nil, { status = "rejected", result = "npc_moved_after_approach" }
  end
  return target
end

local function vars()
  return gc.read("vars", {
    varps = { config.varp, config.secondary_varp },
    varbits = {
      config.varbits.met_sammy,
      config.varbits.scorpion_cutscene,
      config.varbits.bouncer_cutscene,
      config.varbits.khazard_cutscene,
      config.varbits.attempted_entry,
    },
  })
end

local function varp()
  return vars().varps[config.varp]
end

local function quest_finished()
  local quests = gc.read("quests")
  return quests.fight_arena and quests.fight_arena.state == "finished"
end

local function choose(dialogue, choices, breaks)
  for _, wanted in ipairs(choices or {}) do
    for _, option in ipairs(dialogue.options or {}) do
      if option.text == wanted then
        return gc.await {
          action = { type = "dialogue.choose", text = option.text },
          breaks = breaks,
          timeout = { game_ticks = 20 },
        }
      end
    end
  end
  return { status = "rejected", result = "unexpected_dialogue_choice", dialogue = dialogue }
end

local function finish_dialogue(predicate, choices, breaks, ticks)
  local progressed = false
  local closed_ticks = 0
  local receipts = {}
  for _ = 1, ticks or 100 do
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
  return nil, {
    status = "timed_out",
    result = "dialogue_progress_timeout",
    varp = varp(),
    dialogue = gc.read("dialogue"),
  }
end

local function talk(ids, world, predicate, choices, breaks)
  local allow_breaks = breaks ~= false
  local target, failure = reach_npc(ids, world, allow_breaks)
  if not target then return failure end
  local clicked = gc.await {
    action = { type = "npc.interact", id = target.id, action = "Talk-to", within = 12 },
    breaks = allow_breaks,
    timeout = { game_ticks = 40 },
  }
  if clicked.status ~= "dispatched" then return clicked end
  local dialogue, dialogue_failure = finish_dialogue(
    predicate, choices, allow_breaks, 100)
  if not dialogue then return dialogue_failure end
  return {
    status = "complete",
    result = "dialogue_progress_verified",
    receipt = clicked,
    dialogue = dialogue,
  }
end

local function object(id, action, within)
  return gc.read("objects", {
    id = id,
    action = action,
    within = within or 16,
    limit = 10,
  })[1]
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
      nearby = gc.read("objects", { within = within or 16, limit = 50 }),
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

local function use_on_object(item_id, object_id, point, predicate, breaks, within)
  local allow_breaks = breaks ~= false
  local radius = within or 8
  local near = approach(point, 3, allow_breaks)
  if near.status ~= "arrived" then return near end
  gc.await { event = "game.tick" }
  local target = gc.read("objects", { id = object_id, within = radius, limit = 10 })[1]
  if not target then
    return {
      status = "rejected",
      result = "use_target_not_observed",
      object_id = object_id,
      nearby = gc.read("objects", { within = radius, limit = 50 }),
    }
  end
  local clicked = gc.await {
    action = {
      type = "item.use_on_object",
      item_id = item_id,
      object_id = object_id,
      world = target.world,
      within = radius,
    },
    breaks = allow_breaks,
    timeout = { game_ticks = 40 },
  }
  if clicked.status ~= "dispatched" then return clicked end
  local dialogue, failure = finish_dialogue(predicate, {}, allow_breaks, 100)
  if not dialogue then return failure end
  return {
    status = "complete",
    result = "item_use_result_verified",
    receipt = clicked,
    dialogue = dialogue,
  }
end

return {
  distance = distance,
  wait_for = wait_for,
  walk = walk,
  approach = approach,
  quantity = quantity,
  carried = carried,
  npc = npc,
  vars = vars,
  varp = varp,
  quest_finished = quest_finished,
  finish_dialogue = finish_dialogue,
  talk = talk,
  object = object,
  object_action = object_action,
  use_on_object = use_on_object,
}
