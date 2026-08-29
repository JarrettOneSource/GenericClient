local config = gc.require("waterfall_config")

local function in_zone(world, zone)
  return world and world.plane == zone.plane and world.x >= zone.x1 and world.x <= zone.x2 and
    world.y >= zone.y1 and world.y <= zone.y2
end

local function quantity(id, subject)
  local total = 0
  for _, item in ipairs(gc.read(subject or "inventory").items) do
    if item.id == id then total = total + item.quantity end
  end
  return total
end

local function walk(world, within, ticks)
  return gc.await {
    action = {
      type = "walk.to",
      destination = world,
      within = within or 3,
      run = true,
    },
    breaks = false,
    timeout = { game_ticks = ticks or 600 },
  }
end

local function wait_for(predicate, ticks)
  for _ = 1, ticks do
    gc.await { event = "game.tick" }
    if predicate() then return true end
  end
  return false
end

local function obtain_key()
  local approach = walk(config.points.falls_crate, 3, 600)
  if approach.status ~= "arrived" then return approach end
  local searched = gc.await {
    action = {
      type = "object.interact",
      id = config.objects.falls_crate,
      action = "Search",
      world = config.points.falls_crate,
      within = 4,
    },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if searched.status ~= "dispatched" then return searched end
  if not wait_for(function() return quantity(config.items.baxtorian_key) > 0 end, 20) then
    return { status = "timed_out", result = "baxtorian_key_unverified", receipt = searched }
  end
  return { status = "complete", result = "baxtorian_key_obtained", receipt = searched }
end

local function open_inner_door()
  local staged = walk(config.points.inner_door_staging, 0, 600)
  if staged.status ~= "arrived" then
    return { status = "timed_out", result = "inner_door_staging_unverified", walk = staged }
  end
  if in_zone(gc.read("player").world, config.zones.pillar_room) then
    return { status = "complete", result = "pillar_room_entered", walk = staged }
  end
  local closed = gc.read("objects", {
    id = config.objects.inner_door_closed,
    action = "Open",
    within = 8,
    limit = 1,
  })
  local opened = nil
  if #closed > 0 then
    opened = gc.await {
      action = {
        type = "object.interact",
        id = config.objects.inner_door_closed,
        action = "Open",
        world = closed[1].world,
        within = 8,
      },
      breaks = false,
      timeout = { game_ticks = 40 },
    }
    if opened.status ~= "dispatched" then return opened end
    gc.await { event = "game.tick" }
  end
  local crossed = walk({ x = 2566, y = 9903, plane = 0 }, 0, 180)
  local destination = gc.read("player").world
  local pillar_room = in_zone(destination, config.zones.pillar_room)
  local chalice_room = in_zone(destination, config.zones.chalice_room)
  if not pillar_room and not chalice_room then
    return {
      status = "timed_out",
      result = "inner_door_destination_unverified",
      staging = staged,
      open = opened,
      walk = crossed,
      doors = {
        closed = gc.read("objects", { id = config.objects.inner_door_closed, within = 8, limit = 4 }),
        open = gc.read("objects", { id = config.objects.inner_door_open, within = 8, limit = 4 }),
      },
      nearby = gc.read("objects", { within = 8, limit = 30 }),
    }
  end
  return {
    status = "complete",
    result = chalice_room and "chalice_room_entered" or "pillar_room_entered",
    staging = staged,
    open = opened,
    walk = crossed,
  }
end

local function remove_amulet()
  local removed = gc.await {
    action = { type = "equipment.interact", id = config.items.amulet, action = "Remove" },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if removed.status ~= "dispatched" then return removed end
  if not wait_for(function()
    return quantity(config.items.amulet) > 0 and quantity(config.items.amulet, "equipment") == 0
  end, 12) then
    return { status = "timed_out", result = "glarial_amulet_remove_unverified", receipt = removed }
  end
  return { status = "complete", result = "glarial_amulet_removed", receipt = removed }
end

local function pillars()
  local observed = gc.read("objects", { id = config.objects.pillar, within = 20, limit = 20 })
  local unique = {}
  local result = {}
  for _, pillar in ipairs(observed) do
    local key = pillar.world.x .. ":" .. pillar.world.y .. ":" .. pillar.world.plane
    if not unique[key] then
      unique[key] = true
      table.insert(result, pillar)
    end
  end
  table.sort(result, function(a, b)
    if a.world.x ~= b.world.x then return a.world.x < b.world.x end
    return a.world.y < b.world.y
  end)
  return result
end

local function already_placed(since_tick)
  for _, message in ipairs(gc.read("messages", { since_tick = since_tick, limit = 12 })) do
    if tostring(message.text):lower():find("already", 1, true) then return true, message end
  end
  return false, nil
end

local function place_rune(item_id, pillar)
  local before_quantity = quantity(item_id)
  if before_quantity < 1 then
    return nil, { status = "ritual_rune_missing", item_id = item_id, pillar = pillar.world }
  end
  local since_tick = gc.read("runtime").game_tick
  local placed = gc.await {
    action = {
      type = "item.use_on_object",
      item_id = item_id,
      object_id = config.objects.pillar,
      world = pillar.world,
      within = 4,
    },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if placed.status ~= "dispatched" then
    return nil, { status = "ritual_rune_dispatch_failed", receipt = placed, pillar = pillar.world }
  end
  for _ = 1, 8 do
    gc.await { event = "game.tick" }
    if quantity(item_id) < before_quantity then
      return { status = "complete", result = "rune_consumed", receipt = placed }
    end
    local existing, message = already_placed(since_tick)
    if existing then
      return { status = "complete", result = "rune_already_placed", receipt = placed, message = message }
    end
  end
  return nil, {
    status = "ritual_rune_unverified",
    item_id = item_id,
    pillar = pillar.world,
    receipt = placed,
    messages = gc.read("messages", { since_tick = since_tick, limit = 12 }),
  }
end

local function charge_pillars()
  local observed = pillars()
  if #observed ~= 6 then
    return { status = "pillar_count_unexpected", count = #observed, pillars = observed }
  end
  local receipts = {}
  local runes = { config.items.air_rune, config.items.water_rune, config.items.earth_rune }
  for pillar_index, pillar in ipairs(observed) do
    local approach = walk(pillar.world, 3, 120)
    if approach.status ~= "arrived" then
      return {
        status = "pillar_approach_failed",
        pillar = pillar.world,
        receipt = approach,
        ritual = receipts,
      }
    end
    for _, item_id in ipairs(runes) do
      local latest = pillars()
      if #latest ~= 6 then
        return { status = "pillar_rescan_failed", count = #latest, receipts = receipts }
      end
      local charged, failure = place_rune(item_id, latest[pillar_index])
      if not charged then return failure end
      table.insert(receipts, {
        pillar = latest[pillar_index].world,
        item_id = item_id,
        approach = approach,
        result = charged,
      })
    end
  end
  local approach = walk(config.points.statue, 3, 600)
  if approach.status ~= "arrived" then
    return { status = "statue_approach_failed", receipt = approach, ritual = receipts }
  end
  local raised = gc.await {
    action = {
      type = "item.use_on_object",
      item_id = config.items.amulet,
      object_id = config.objects.statue,
      world = config.points.statue,
      within = 4,
    },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if raised.status ~= "dispatched" then
    return { status = "statue_amulet_failed", receipt = raised, ritual = receipts }
  end
  if not wait_for(function()
    return in_zone(gc.read("player").world, config.zones.chalice_room)
  end, 40) then
    return {
      status = "chalice_room_unverified",
      receipt = raised,
      ritual = receipts,
      world = gc.read("player").world,
    }
  end
  return { status = "complete", result = "chalice_room_reached", ritual = receipts, receipt = raised }
end

local function close_continue_dialogues(limit)
  for _ = 1, limit do
    local dialogue = gc.read("dialogue")
    if dialogue.type == "closed" then return true end
    if dialogue.type ~= "continue" then
      return nil, { status = "unexpected_completion_dialogue", dialogue = dialogue }
    end
    local continued = gc.await {
      action = { type = "dialogue.continue" },
      breaks = false,
      timeout = { game_ticks = 20 },
    }
    if continued.status ~= "dispatched" then
      return nil, { status = "completion_dialogue_failed", receipt = continued }
    end
    gc.await { event = "game.tick" }
  end
  return nil, { status = "completion_dialogue_timeout", dialogue = gc.read("dialogue") }
end

local function finish_quest()
  if quantity(config.items.empty_urn) > 0 then
    return { status = "empty_urn_detected", item_id = config.items.empty_urn }
  end
  local dialogue_closed, dialogue_failure = close_continue_dialogues(6)
  if not dialogue_closed then return dialogue_failure end
  local chalices = gc.read("objects", {
    id = config.objects.chalice,
    within = 20,
    limit = 2,
  })
  if #chalices == 0 then
    return {
      status = "chalice_not_observed",
      nearby = gc.read("objects", { within = 20, limit = 40 }),
    }
  end
  local chalice = chalices[1]
  local approach = walk(chalice.world, 3, 120)
  if approach.status ~= "arrived" then return approach end
  local finished = gc.await {
    action = {
      type = "item.use_on_object",
      item_id = config.items.urn,
      object_id = config.objects.chalice,
      world = chalice.world,
      within = 4,
    },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if finished.status ~= "dispatched" then return finished end
  for _ = 1, 50 do
    gc.await { event = "game.tick" }
    if gc.read("quests").waterfall_quest.state == "finished" then
      return { status = "complete", result = "waterfall_quest_complete", receipt = finished }
    end
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      local continued = gc.await {
        action = { type = "dialogue.continue" },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
      if continued.status ~= "dispatched" then
        return { status = "completion_dialogue_failed", receipt = continued }
      end
    end
  end
  return {
    status = "timed_out",
    result = "waterfall_completion_unverified",
    receipt = finished,
    quest = gc.read("quests").waterfall_quest,
  }
end

local function execute(phase)
  if phase == "obtain_baxtorian_key" then return obtain_key() end
  if phase == "open_inner_door" then return open_inner_door() end
  if phase == "remove_amulet" then return remove_amulet() end
  if phase == "charge_pillars" then return charge_pillars() end
  if phase == "finish_quest" then return finish_quest() end
  return { status = "rejected", result = "ritual_phase_unknown:" .. tostring(phase) }
end

return { execute = execute }
