local config = gc.require("monkey_madness_config")
local navigation = gc.require("monkey_madness_navigation")
local preparation = gc.require("monkey_madness_preparation")

local function in_zone(world, zone)
  return world and world.plane == zone.plane and
    world.x >= zone.x1 and world.x <= zone.x2 and
    world.y >= zone.y1 and world.y <= zone.y2
end

local function npc(ids, within)
  for _, id in ipairs(ids) do
    local target = gc.read("npcs", { id = id, within = within or 24, limit = 1 })[1]
    if target then return target end
  end
  return nil
end

local function lumdo_stage()
  local vars = gc.read("vars", { varbits = { config.varbits.lumdo } })
  return vars.varbits[config.varbits.lumdo]
end

local function wait_for_zone(zone, ticks)
  for _ = 1, ticks do
    gc.await { event = "game.tick" }
    if in_zone(gc.read("player").world, zone) then return true end
  end
  return false
end

local function choose(dialogue, wanted)
  for _, option in ipairs(dialogue.options or {}) do
    if option.text == wanted or option.text == wanted .. "." then
      return gc.await {
        action = { type = "dialogue.choose", text = option.text },
        breaks = false,
        timeout = { game_ticks = 30 },
      }
    end
  end
  return nil
end

local function conversation(target, completed, result, wanted_choice, ticks)
  local initial_dialogue = gc.read("dialogue")
  local talked = { status = "dispatched", result = "existing_dialogue" }
  local target_talk_dispatched = false
  if initial_dialogue.type == "closed" then
    talked = gc.await {
      action = { type = "npc.interact", id = target.id, action = "Talk-to", within = 24 },
      breaks = true,
      timeout = { game_ticks = 40 },
    }
    if talked.status ~= "dispatched" then return nil, talked end
    target_talk_dispatched = true
  end
  local opened = initial_dialogue.type ~= "closed"
  local closed_ticks = 0
  local progressed = false
  for _ = 1, ticks or 160 do
    gc.await { event = "game.tick" }
    progressed = progressed or completed()
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      opened = true
      closed_ticks = 0
      local continued = gc.await {
        action = { type = "dialogue.continue" },
        breaks = false,
        timeout = { game_ticks = 30 },
      }
      if continued.status ~= "dispatched" and
        continued.result ~= "dialogue_is_choice" and
        continued.result ~= "dialogue_continue_not_visible" then
        return nil, continued
      end
    elseif dialogue.type == "choice" then
      opened = true
      closed_ticks = 0
      local selected = wanted_choice and choose(dialogue, wanted_choice) or nil
      if not selected then
        return nil, {
          status = "monkey_madness_unexpected_dialogue_choice",
          wanted = wanted_choice,
          dialogue = dialogue,
        }
      end
      if selected.status ~= "dispatched" then return nil, selected end
    elseif opened then
      closed_ticks = closed_ticks + 1
      if progressed and closed_ticks >= 2 then
        return { status = "complete", result = result, receipt = talked }
      end
      if not progressed and closed_ticks >= 20 then
        if not target_talk_dispatched then
          talked = gc.await {
            action = { type = "npc.interact", id = target.id, action = "Talk-to", within = 24 },
            breaks = true,
            timeout = { game_ticks = 40 },
          }
          if talked.status ~= "dispatched" then return nil, talked end
          target_talk_dispatched = true
          opened = false
          closed_ticks = 0
        else
          return nil, {
            status = "monkey_madness_conversation_closed_without_progress",
            result = result,
            receipt = talked,
          }
        end
      end
    end
  end
  return nil, {
    status = "monkey_madness_conversation_timeout",
    result = result,
    dialogue = gc.read("dialogue"),
  }
end

local function travel_to_hangar()
  if in_zone(gc.read("player").world, config.zones.post_puzzle_hangar) then
    return { status = "complete", result = "post_puzzle_hangar_already_reached" }
  end
  local stronghold = navigation.travel_to_gnome_stronghold()
  if stronghold.status ~= "complete" then return stronghold end
  local target, failure = navigation.reach_daero()
  if not target then return failure end
  gc.activity("travel")
  local traveled = gc.await {
    action = { type = "npc.interact", id = target.id, action = "Travel", within = 24 },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if traveled.status ~= "dispatched" then return traveled end
  if not wait_for_zone(config.zones.post_puzzle_hangar, 50) then
    return {
      status = "monkey_madness_hangar_return_unverified",
      receipt = traveled,
      player = gc.read("player"),
    }
  end
  return { status = "complete", result = "post_puzzle_hangar_reached", receipt = traveled }
end

local function fly_to_crash_island()
  if in_zone(gc.read("player").world, config.zones.crash_island) then
    return { status = "complete", result = "crash_island_already_reached" }
  end
  local target = npc(config.npcs.waydar, 30)
  if not target then
    return {
      status = "monkey_madness_hangar_waydar_not_observed",
      nearby = gc.read("npcs", { within = 30, limit = 50 }),
    }
  end
  local completed, failure = conversation(
    target,
    function() return in_zone(gc.read("player").world, config.zones.crash_island) end,
    "crash_island_reached",
    "Yes",
    120)
  if not completed then return failure end
  return completed
end

local function talk_lumdo_initial()
  if lumdo_stage() >= 2 then
    return { status = "complete", result = "lumdo_initial_already_complete" }
  end
  local target = npc(config.npcs.lumdo, 24)
  if not target then
    return { status = "monkey_madness_lumdo_not_observed" }
  end
  local completed, failure = conversation(
    target,
    function() return lumdo_stage() >= 2 end,
    "lumdo_refusal_complete",
    nil,
    240)
  return completed or failure
end

local function ask_waydar_to_intervene()
  if lumdo_stage() >= 3 then
    return { status = "complete", result = "waydar_intervention_already_complete" }
  end
  local last_failure
  for _ = 1, 3 do
    local target = npc(config.npcs.waydar, 24)
    if not target then
      return { status = "monkey_madness_crash_waydar_not_observed" }
    end
    local completed, failure = conversation(
      target,
      function() return lumdo_stage() >= 3 end,
      "waydar_intervention_complete",
      "I cannot convince Lumdo to take us to the island...",
      120)
    if completed then return completed end
    last_failure = failure
    if failure.status ~= "monkey_madness_conversation_closed_without_progress" then
      return failure
    end
  end
  return last_failure or { status = "monkey_madness_waydar_intervention_unverified" }
end

local function sail_to_ape_atoll()
  if in_zone(gc.read("player").world, config.zones.ape_atoll_south) then
    return { status = "complete", result = "ape_atoll_already_reached" }
  end
  local target = npc(config.npcs.lumdo, 24)
  if not target then
    return { status = "monkey_madness_return_lumdo_not_observed" }
  end
  local completed, failure = conversation(
    target,
    function() return in_zone(gc.read("player").world, config.zones.ape_atoll_south) end,
    "ape_atoll_reached",
    nil,
    120)
  return completed or failure
end

local function execute()
  local armed, safety_error = preparation.arm_safety()
  if not armed then return safety_error end
  if in_zone(gc.read("player").world, config.zones.ape_atoll_south) then
    return { status = "complete", result = "ape_atoll_already_reached" }
  end
  if not in_zone(gc.read("player").world, config.zones.crash_island) then
    local hangar = travel_to_hangar()
    if hangar.status ~= "complete" then return hangar end
    local crash = fly_to_crash_island()
    if crash.status ~= "complete" then return crash end
  end
  local initial = talk_lumdo_initial()
  if initial.status ~= "complete" then return initial end
  local intervention = ask_waydar_to_intervene()
  if intervention.status ~= "complete" then return intervention end
  return sail_to_ape_atoll()
end

return { execute = execute }
