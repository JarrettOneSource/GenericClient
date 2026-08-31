local config = gc.require("monkey_madness_config")
local preparation = gc.require("monkey_madness_preparation")

local antipoison_ids = { 2448, 181, 183, 185 }
local prayer_potion_ids = { 2434, 139, 141, 143 }
local protect_from_missiles_varbit = 4117

local function in_zone(world, zone)
  return world and world.plane == zone.plane and
    world.x >= zone.x1 and world.x <= zone.x2 and
    world.y >= zone.y1 and world.y <= zone.y2
end

local function in_south(world)
  return in_zone(world, config.zones.ape_atoll_south) or
    in_zone(world, config.zones.ape_atoll_south_corridor_wide) or
    in_zone(world, config.zones.ape_atoll_south_corridor_narrow)
end

local function in_north(world)
  return in_zone(world, config.zones.ape_atoll_north) or
    in_zone(world, config.zones.ape_atoll_north_west) or
    in_zone(world, config.zones.ape_atoll_north_east)
end

local function in_prison()
  return in_zone(gc.read("player").world, config.zones.ape_atoll_prison)
end

local function quantity(id)
  local total = 0
  for _, item in ipairs(gc.read("inventory").items or {}) do
    if item.id == id then total = total + item.quantity end
  end
  return total
end

local function current_antipoison()
  for _, id in ipairs(antipoison_ids) do
    if quantity(id) > 0 then return id end
  end
  return nil
end

local function current_prayer_potion()
  for _, id in ipairs(prayer_potion_ids) do
    if quantity(id) > 0 then return id end
  end
  return nil
end

local function protection_active()
  local vars = gc.read("vars", { varbits = { protect_from_missiles_varbit } })
  return vars.varbits[protect_from_missiles_varbit] ~= 0
end

local function set_protection(enabled)
  local receipt = gc.await {
    action = {
      type = "prayer.set",
      prayer = "protect_from_missiles",
      enabled = enabled,
    },
    breaks = false,
    timeout = { game_ticks = 20 },
  }
  if receipt.status ~= "set" and receipt.status ~= "unchanged" and
    not (not enabled and gc.read("skills").prayer.boosted_level == 0) then
    return nil, { status = "monkey_madness_ranged_protection_failed", receipt = receipt }
  end
  return true, receipt
end

local function maintain_prayer()
  local prayer = gc.read("skills").prayer
  if prayer.boosted_level <= 10 then
    local potion = current_prayer_potion()
    if not potion then
      return nil, { status = "monkey_madness_prayer_potion_exhausted", prayer = prayer }
    end
    local drank = gc.await {
      action = { type = "item.interact", id = potion, action = "Drink" },
      breaks = false,
      timeout = { game_ticks = 20 },
    }
    if drank.status ~= "dispatched" then
      return nil, { status = "monkey_madness_prayer_restore_failed", receipt = drank }
    end
    gc.await { event = "game.tick" }
  end
  if not protection_active() then return set_protection(true) end
  return true
end

local function disable_protection()
  if not protection_active() then return true end
  return set_protection(false)
end

local function garkor_stage()
  local vars = gc.read("vars", { varbits = { config.varbits.garkor } })
  return vars.varbits[config.varbits.garkor]
end

local function walk(destination, within, ticks)
  gc.activity("travel")
  return gc.await {
    action = { type = "walk.to", destination = destination, within = within or 2, run = true },
    breaks = false,
    timeout = { game_ticks = ticks or 500 },
  }
end

local function drink_antipoison()
  local id = current_antipoison()
  if not id then return nil, { status = "monkey_madness_antipoison_missing" } end
  local receipt = gc.await {
    action = { type = "item.interact", id = id, action = "Drink" },
    breaks = false,
    timeout = { game_ticks = 20 },
  }
  if receipt.status ~= "dispatched" then
    return nil, { status = "monkey_madness_antipoison_failed", receipt = receipt }
  end
  return receipt
end

local function reach_prison()
  if in_prison() then return { status = "complete", result = "ape_atoll_prison_reached" } end
  if not in_south(gc.read("player").world) then
    return { status = "monkey_madness_valley_start_unknown", player = gc.read("player") }
  end

  local antipoison, poison_failure = drink_antipoison()
  if not antipoison then return poison_failure end
  local receipts = { antipoison = antipoison, walks = {} }
  for _, point in ipairs(config.routes.ape_atoll_valley) do
    local maintained, prayer_failure = maintain_prayer()
    if not maintained then return prayer_failure end
    local walked = walk(point, 2, 500)
    receipts.walks[#receipts.walks + 1] = walked
    if in_prison() then
      return { status = "complete", result = "ape_atoll_prison_reached", receipts = receipts }
    end
    if walked.status ~= "arrived" then
      return {
        status = "monkey_madness_valley_walk_failed",
        destination = point,
        receipt = walked,
        player = gc.read("player"),
      }
    end
  end

  for _ = 1, 50 do
    local maintained, prayer_failure = maintain_prayer()
    if not maintained then return prayer_failure end
    if in_prison() then
      return { status = "complete", result = "ape_atoll_prison_reached", receipts = receipts }
    end
    gc.await { event = "game.tick" }
  end
  return {
    status = "monkey_madness_capture_not_observed",
    receipts = receipts,
    player = gc.read("player"),
    messages = gc.read("messages", { limit = 30 }),
  }
end

local function jail_door()
  return gc.read("objects", {
    id = config.objects.jail_door,
    action = "Pick-lock",
    within = 12,
    limit = 1,
  })[1]
end

local function guard_distance(door)
  local guard = gc.read("npcs", {
    id = config.npcs.prison_guard,
    within = 24,
    limit = 1,
  })[1]
  if not guard then return 99 end
  return math.max(
    math.abs(guard.world.x - door.world.x),
    math.abs(guard.world.y - door.world.y))
end

local function wait_for_guard_departure(door)
  local previous = guard_distance(door)
  for _ = 1, 160 do
    gc.await { event = "game.tick" }
    local current = guard_distance(door)
    if current > previous and current >= 2 then return current end
    previous = current
  end
  return nil
end

local function wait_for_lock_result(since_tick, door)
  for _ = 1, 12 do
    for _, message in ipairs(gc.read("messages", { since_tick = since_tick, limit = 12 })) do
      local text = string.lower(message.text or "")
      if string.find(text, "manage to pick the lock", 1, true) then return true, message end
      if string.find(text, "fail to pick the lock", 1, true) then return false, message end
    end
    local still_closed = gc.read("objects", {
      id = door.id,
      action = "Pick-lock",
      within = 12,
      limit = 6,
    })
    local same_door = false
    for _, candidate in ipairs(still_closed) do
      if candidate.world.x == door.world.x and candidate.world.y == door.world.y then
        same_door = true
        break
      end
    end
    if not same_door then return true, { text = "door state changed" } end
    gc.await { event = "game.tick" }
  end
  return nil, { text = "lock-pick result not observed" }
end

local function drain_capture_dialogue()
  if gc.read("dialogue").type == "closed" then
    return true
  end
  local quiet_ticks = 0
  for _ = 1, 120 do
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      quiet_ticks = 0
      local continued = gc.await {
        action = { type = "dialogue.continue" },
        breaks = false,
        timeout = { game_ticks = 30 },
      }
      if continued.status ~= "dispatched" and
        continued.result ~= "dialogue_continue_not_visible" then
        return nil, {
          status = "monkey_madness_capture_dialogue_failed",
          receipt = continued,
        }
      end
    elseif dialogue.type == "closed" then
      quiet_ticks = quiet_ticks + 1
      if quiet_ticks >= 12 then return true end
    else
      return nil, {
        status = "monkey_madness_capture_dialogue_unexpected",
        dialogue = dialogue,
      }
    end
    gc.await { event = "game.tick" }
  end
  return nil, {
    status = "monkey_madness_capture_dialogue_timeout",
    dialogue = gc.read("dialogue"),
  }
end

local function escape_prison()
  if not in_prison() then return { status = "complete", result = "ape_atoll_prison_already_exited" } end
  if quantity(config.items.lockpick) == 0 then
    return { status = "monkey_madness_lockpick_missing" }
  end

  local disabled, prayer_failure = disable_protection()
  if not disabled then return prayer_failure end
  local dialogue_closed, dialogue_failure = drain_capture_dialogue()
  if not dialogue_closed then return dialogue_failure end

  local attempts = {}
  for cycle = 1, 10 do
    local door = jail_door()
    if not door then
      return {
        status = "monkey_madness_jail_door_not_observed",
        objects = gc.read("objects", { within = 12, limit = 60 }),
      }
    end

    local departing_distance = wait_for_guard_departure(door)
    if not departing_distance then
      return {
        status = "monkey_madness_guard_departure_not_observed",
        player = gc.read("player"),
        nearby = gc.read("npcs", { within = 24, limit = 30 }),
      }
    end

    local previous_distance = departing_distance
    for attempt = 1, 4 do
      local since_tick = gc.read("runtime").game_tick
      local picked = gc.await {
        action = {
          type = "object.interact",
          id = door.id,
          action = "Pick-lock",
          world = door.world,
          within = 12,
        },
        breaks = false,
        timeout = { game_ticks = 30 },
      }
      if picked.status ~= "dispatched" then
        attempts[#attempts + 1] = { cycle = cycle, picked = picked }
        break
      end

      local unlocked, lock_message = wait_for_lock_result(since_tick, door)
      attempts[#attempts + 1] = {
        cycle = cycle,
        picked = picked,
        lock_message = lock_message,
      }
      if unlocked then
        gc.await { event = "game.tick" }
        local crossed = walk(config.points.ape_atoll_prison_exit, 2, 120)
        attempts[#attempts].crossed = crossed
        if not in_prison() and in_north(gc.read("player").world) then
          return { status = "complete", result = "ape_atoll_prison_exited", attempts = attempts }
        end
        break
      end

      local current_distance = guard_distance(door)
      if current_distance <= 1 or current_distance < previous_distance then break end
      previous_distance = current_distance
      door = jail_door()
      if not door then break end
    end

    if not in_prison() and in_north(gc.read("player").world) then
      return { status = "complete", result = "ape_atoll_prison_exited", attempts = attempts }
    end
  end
  return {
    status = "monkey_madness_prison_escape_failed",
    attempts = attempts,
    player = gc.read("player"),
    nearby = gc.read("npcs", { within = 24, limit = 30 }),
  }
end

local function route_to_garkor()
  local receipts = {}
  for _, point in ipairs(config.routes.prison_to_garkor) do
    local walked = walk(point, 2, 300)
    receipts[#receipts + 1] = walked
    if in_prison() then
      return nil, {
        status = "monkey_madness_recaptured_on_garkor_route",
        destination = point,
        receipts = receipts,
      }
    end
    if walked.status ~= "arrived" then
      return nil, {
        status = "monkey_madness_garkor_route_failed",
        destination = point,
        receipt = walked,
        player = gc.read("player"),
      }
    end
  end
  return receipts
end

local function talk_to_garkor()
  if garkor_stage() >= 2 then
    return { status = "complete", result = "garkor_already_briefed" }
  end
  local target = gc.read("npcs", {
    where = { name = "Garkor" },
    action = "Talk-to",
    within = 24,
    limit = 1,
  })[1]
  if not target then
    return {
      status = "monkey_madness_garkor_not_observed",
      player = gc.read("player"),
      nearby = gc.read("npcs", { within = 24, limit = 50 }),
    }
  end

  local talked = gc.await {
    action = { type = "npc.interact", id = target.id, action = "Talk-to", within = 24 },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if talked.status ~= "dispatched" then return talked end

  local opened = false
  local closed_ticks = 0
  for _ = 1, 180 do
    local progressed = garkor_stage() >= 2
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
        continued.result ~= "dialogue_continue_not_visible" and
        continued.result ~= "dialogue_is_choice" then
        return continued
      end
    elseif dialogue.type == "choice" then
      return {
        status = "monkey_madness_unexpected_garkor_choice",
        dialogue = dialogue,
      }
    elseif opened then
      closed_ticks = closed_ticks + 1
      if progressed and closed_ticks >= 2 then
        return { status = "complete", result = "garkor_briefing_complete", receipt = talked }
      end
      gc.await { event = "game.tick" }
    else
      gc.await { event = "game.tick" }
    end
  end
  return {
    status = "monkey_madness_garkor_dialogue_timeout",
    stage = garkor_stage(),
    dialogue = gc.read("dialogue"),
  }
end

local function execute()
  local armed, safety_failure = preparation.arm_safety()
  if not armed then return safety_failure end
  if garkor_stage() >= 2 then return { status = "complete", result = "garkor_already_briefed" } end

  local world = gc.read("player").world
  if in_south(world) then
    if gc.read("skills").prayer.level < 43 then
      return {
        status = "monkey_madness_protect_from_missiles_required",
        prayer = gc.read("skills").prayer,
      }
    end
    local maintained, prayer_failure = maintain_prayer()
    if not maintained then return prayer_failure end
    local captured = reach_prison()
    if captured.status ~= "complete" then return captured end
  end
  if in_prison() then
    local escaped = escape_prison()
    if escaped.status ~= "complete" then return escaped end
  end
  if not in_north(gc.read("player").world) then
    return { status = "monkey_madness_garkor_route_start_unknown", player = gc.read("player") }
  end
  local current = gc.read("player").world
  local distance_to_garkor = math.max(
    math.abs(current.x - config.points.garkor.x),
    math.abs(current.y - config.points.garkor.y))
  local route = {}
  if distance_to_garkor > 8 then
    local route_failure
    route, route_failure = route_to_garkor()
    if not route then return route_failure end
  end
  local disabled, prayer_failure = disable_protection()
  if not disabled then return prayer_failure end
  local briefing = talk_to_garkor()
  briefing.route = route
  return briefing
end

return { execute = execute }
