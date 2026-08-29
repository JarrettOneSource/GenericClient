local config = gc.require("waterfall_config")
local travel = gc.require("shared_travel")

local function in_zone(world, zone)
  return world and world.plane == zone.plane and world.x >= zone.x1 and world.x <= zone.x2 and
    world.y >= zone.y1 and world.y <= zone.y2
end

local function quantity(id)
  local total = 0
  for _, item in ipairs(gc.read("inventory").items) do
    if item.id == id then total = total + item.quantity end
  end
  return total
end

local function varp()
  return gc.read("vars", { varps = { 65 } }).varps[65]
end

local function walk(world, within, breaks, ticks)
  return gc.await {
    action = {
      type = "walk.to",
      destination = world,
      within = within or 3,
      run = true,
    },
    breaks = breaks,
    timeout = { game_ticks = ticks or 900 },
  }
end

local function approach(world, within, breaks)
  local player = gc.read("player").world
  if player.plane == world.plane and
    math.max(math.abs(player.x - world.x), math.abs(player.y - world.y)) <= (within or 3) then
    return { status = "arrived", result = "already_near_target" }
  end
  return walk(world, within, breaks)
end

local function wait_for(predicate, ticks)
  for _ = 1, ticks do
    gc.await { event = "game.tick" }
    if predicate() then return true end
  end
  return false
end

local function drain_continue_dialogue(breaks)
  for _ = 1, 20 do
    local dialogue = gc.read("dialogue")
    if dialogue.type == "closed" then return true end
    if dialogue.type ~= "continue" then
      return nil, { status = "rejected", result = "unexpected_open_dialogue", dialogue = dialogue }
    end
    local receipt = gc.await {
      action = { type = "dialogue.continue" },
      breaks = breaks,
      timeout = { game_ticks = 20 },
    }
    if receipt.status ~= "dispatched" then return nil, receipt end
    gc.await { event = "game.tick" }
  end
  return nil, { status = "timed_out", result = "dialogue_close_timeout" }
end

local function interact_object(id, action, world, breaks, predicate, result, options)
  options = options or {}
  local drained, dialogue_failure = drain_continue_dialogue(breaks)
  if not drained then return dialogue_failure end
  gc.await { action = { type = "ui.close" }, breaks = breaks }
  if options.approach ~= false then
    local near = approach(world, options.approach_within or 3, breaks)
    if near.status ~= "arrived" then return near end
  end
  local clicked = gc.await {
    action = {
      type = "object.interact",
      id = id,
      action = action,
      world = world,
      within = options.within or 4,
    },
    breaks = breaks,
    timeout = { game_ticks = 40 },
  }
  if clicked.status ~= "dispatched" then return clicked end
  if not wait_for(predicate, 30) then
    return { status = "timed_out", result = result .. "_unverified", receipt = clicked }
  end
  return { status = "complete", result = result, receipt = clicked }
end

local function use_on_object(item_id, object_id, world, predicate, result, within)
  local breaks = true
  local drained, dialogue_failure = drain_continue_dialogue(breaks)
  if not drained then return dialogue_failure end
  local interaction_radius = within or 4
  local near = approach(world, interaction_radius, breaks)
  if near.status ~= "arrived" then return near end
  local clicked = gc.await {
    action = {
      type = "item.use_on_object",
      item_id = item_id,
      object_id = object_id,
      world = world,
      within = interaction_radius,
    },
    breaks = breaks,
    timeout = { game_ticks = 40 },
  }
  if clicked.status ~= "dispatched" then return clicked end
  if not wait_for(predicate, 30) then
    return { status = "timed_out", result = result .. "_unverified", receipt = clicked }
  end
  return { status = "complete", result = result, receipt = clicked }
end

local function open_and_cross(id, world, destination, predicate, result)
  local near = approach(world, 3, false)
  if near.status ~= "arrived" then return near end
  gc.await { event = "game.tick" }
  local matches = gc.read("objects", { id = id, action = "Open", within = 8, limit = 10 })
  local observed = nil
  local observed_distance = nil
  for _, object in ipairs(matches) do
    local candidate = math.max(
      math.abs(object.world.x - world.x),
      math.abs(object.world.y - world.y))
    if object.world.plane == world.plane and
      (observed_distance == nil or candidate < observed_distance) then
      observed = object
      observed_distance = candidate
    end
  end
  if not observed then
    return {
      status = "rejected",
      result = result .. "_object_unresolved",
      same_id = gc.read("objects", { id = id, within = 8, limit = 10 }),
      nearby = gc.read("objects", { within = 4, limit = 30 }),
    }
  end
  local opened = gc.await {
    action = {
      type = "object.interact",
      id = id,
      action = "Open",
      world = observed.world,
      within = 8,
    },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  opened.observed = observed
  if opened.status ~= "dispatched" then return opened end
  local crossed = walk(destination, 0, false, 120)
  if crossed.status ~= "arrived" or not predicate() then
    return { status = "timed_out", result = result .. "_unverified", open = opened, walk = crossed }
  end
  return { status = "complete", result = result, open = opened, walk = crossed }
end

local function talk(id, world, predicate, choice, breaks)
  local allow_breaks = breaks ~= false
  local near = approach(world, 3, allow_breaks)
  if near.status ~= "arrived" then return near end
  local clicked = gc.await {
    action = { type = "npc.interact", id = id, action = "Talk-to", within = 10 },
    breaks = allow_breaks,
    timeout = { game_ticks = 40 },
  }
  if clicked.status ~= "dispatched" then return clicked end
  local dialogue_receipts = {}
  local progressed = false
  local closed_ticks = 0
  for _ = 1, 80 do
    gc.await { event = "game.tick" }
    progressed = progressed or predicate()
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      closed_ticks = 0
      local receipt = gc.await {
        action = { type = "dialogue.continue" },
        breaks = allow_breaks,
      }
      table.insert(dialogue_receipts, receipt)
      if receipt.status ~= "dispatched" then return receipt end
    elseif dialogue.type == "choice" then
      closed_ticks = 0
      local selected = nil
      for _, option in ipairs(dialogue.options) do
        if option.text == choice then selected = option.text break end
      end
      if not selected then
        return { status = "rejected", result = "unexpected_dialogue_choice", dialogue = dialogue }
      end
      local receipt = gc.await {
        action = { type = "dialogue.choose", text = selected },
        breaks = allow_breaks,
      }
      table.insert(dialogue_receipts, receipt)
      if receipt.status ~= "dispatched" then return receipt end
    elseif progressed then
      closed_ticks = closed_ticks + 1
      if closed_ticks >= 2 then
        return {
          status = "complete",
          result = "dialogue_progress_verified",
          receipt = clicked,
          dialogue = dialogue_receipts,
        }
      end
    end
  end
  return { status = "timed_out", result = "dialogue_progress_timeout", varp = varp() }
end

local function reach_waterfall_area()
  local world = gc.read("player").world
  if math.max(math.abs(world.x - config.points.almera.x),
    math.abs(world.y - config.points.almera.y)) <= 150 then
    return { status = "complete", result = "already_near_waterfall" }
  end
  return travel.teleport_to_barbarian_outpost()
end

local function walk_gnome_route()
  local receipts = {}
  local current = gc.read("player").world
  local start_index = 1
  for index = #config.gnome_route, 1, -1 do
    local world = config.gnome_route[index]
    if current.plane == world.plane and
      math.max(math.abs(current.x - world.x), math.abs(current.y - world.y)) <= 1 then
      start_index = index
      break
    end
  end
  for index = start_index, #config.gnome_route do
    local receipt = walk(config.gnome_route[index], 0, true, 900)
    table.insert(receipts, receipt)
    if receipt.status ~= "arrived" then
      return nil, { status = "gnome_route_failed", index = index, receipt = receipt }
    end
  end
  return receipts
end

local function reach_gnome_area()
  local world = gc.read("player").world
  if math.max(math.abs(world.x - config.points.gnome_ladder.x),
    math.abs(world.y - config.points.gnome_ladder.y)) <= 150 then
    return { status = "complete", result = "already_near_gnome_village" }
  end
  return travel.teleport_to_castle_wars()
end

local function leave_gnome_dungeon()
  local teleported = travel.teleport_to_castle_wars(false)
  if teleported.status == "complete" then return teleported end
  return interact_object(
    config.objects.gnome_ladder_below,
    "Climb-up",
    config.points.gnome_ladder_below,
    false,
    function() return not in_zone(gc.read("player").world, config.zones.gnome_basement) end,
    "gnome_basement_left")
end

local function escape_hostile_area()
  local world = gc.read("player").world
  if in_zone(world, config.zones.gnome_basement) then
    return leave_gnome_dungeon()
  end
  if in_zone(world, config.zones.hudon_island) or
    in_zone(world, config.zones.dead_tree_island) or
    in_zone(world, config.zones.ledge) or
    in_zone(world, config.zones.falls) or
    in_zone(world, config.zones.pillar_room) or
    in_zone(world, config.zones.chalice_room) then
    local teleported = travel.teleport_to_burthorpe(false)
    if teleported.status == "complete" then return teleported end
    return {
      status = "escape_failed",
      result = "waterfall_final_area_escape_failed",
      world = world,
      teleport = teleported,
    }
  end
  return { status = "complete", result = "not_in_gnome_dungeon" }
end

local function read_book()
  local opened = gc.await {
    action = { type = "item.interact", id = config.items.book, action = "Read" },
  }
  if opened.status ~= "dispatched" then return opened end
  local closed = nil
  for tick = 1, 30 do
    gc.await { event = "game.tick" }
    if varp() >= 3 then
      if not closed then
        closed = gc.await { action = { type = "ui.close" }, breaks = true }
      end
      return { status = "complete", result = "book_read", receipt = opened, close = closed }
    end
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      gc.await { action = { type = "dialogue.continue" } }
    elseif tick == 2 then
      closed = gc.await { action = { type = "ui.close" }, breaks = true }
    end
  end
  return { status = "timed_out", result = "book_read_unverified", receipt = opened, varp = varp() }
end

local function execute(phase)
  if phase == "accept" then
    local arrived = reach_waterfall_area()
    if arrived.status ~= "complete" then return arrived end
    return talk(config.npcs.almera, config.points.almera, function() return varp() >= 1 end, "Yes.")
  elseif phase == "reach_hudon" or phase == "reach_falls" then
    local arrived = reach_waterfall_area()
    if arrived.status ~= "complete" then return arrived end
    return interact_object(
      config.objects.raft, "Board", config.points.raft, true,
      function() return in_zone(gc.read("player").world, config.zones.hudon_island) end,
      "hudon_island_reached",
      { approach_within = 1 })
  elseif phase == "talk_hudon" then
    return talk(config.npcs.hudon, config.points.hudon, function() return varp() >= 2 end, nil)
  elseif phase == "cross_to_tree" or phase == "cross_to_tree_final" then
    local edge = walk(config.points.crossing_rock_stand, 0, true, 120)
    if edge.status ~= "arrived" then return edge end
    return use_on_object(
      config.items.rope, config.objects.crossing_rock, config.points.crossing_rock,
      function() return in_zone(gc.read("player").world, config.zones.dead_tree_island) end,
      "dead_tree_island_reached",
      10)
  elseif phase == "descend_tree" or phase == "descend_tree_final" then
    return use_on_object(
      config.items.rope, config.objects.overhanging_tree, config.points.overhanging_tree,
      function() return in_zone(gc.read("player").world, config.zones.ledge) end,
      "waterfall_ledge_reached")
  elseif phase == "leave_ledge" then
    return interact_object(
      config.objects.barrel, "Get in", config.points.barrel, false,
      function()
        local world = gc.read("player").world
        return not in_zone(world, config.zones.ledge) and not in_zone(world, config.zones.dead_tree_island)
      end,
      "tourist_centre_reached")
  elseif phase == "reach_tourist_stairs" then
    return interact_object(
      config.objects.tourist_stairs, "Climb-up", config.points.tourist_stairs, true,
      function() return in_zone(gc.read("player").world, config.zones.tourist_upstairs) end,
      "tourist_upstairs_reached")
  elseif phase == "obtain_book" then
    return interact_object(
      config.objects.bookcase, "Search", config.points.bookcase, true,
      function() return quantity(config.items.book) > 0 end,
      "book_obtained")
  elseif phase == "read_book" then
    return read_book()
  elseif phase == "leave_tourist_house" then
    return interact_object(
      config.objects.tourist_stairs_top, "Climb-down", config.points.tourist_stairs_top, true,
      function() return gc.read("player").world.plane == 0 end,
      "tourist_house_left")
  elseif phase == "reach_gnome_dungeon" then
    local arrived = reach_gnome_area()
    if arrived.status ~= "complete" then return arrived end
    local player = gc.read("player").world
    local route = {}
    if math.max(math.abs(player.x - config.points.gnome_ladder.x),
      math.abs(player.y - config.points.gnome_ladder.y)) > 20 then
      local failure
      route, failure = walk_gnome_route()
      if not route then return failure end
    end
    local entered = interact_object(
      config.objects.gnome_ladder, "Climb-down", config.points.gnome_ladder, false,
      function() return in_zone(gc.read("player").world, config.zones.gnome_basement) end,
      "gnome_basement_entered",
      { approach = false, within = 20 })
    entered.route = route
    return entered
  elseif phase == "obtain_golrie_key" then
    return interact_object(
      config.objects.golrie_crate, "Search", config.points.golrie_crate, false,
      function() return quantity(config.items.golrie_key) > 0 end,
      "golrie_key_obtained")
  elseif phase == "open_golrie_gate" then
    return open_and_cross(
      config.objects.golrie_gate,
      config.points.golrie_gate,
      config.points.golrie,
      function() return in_zone(gc.read("player").world, config.zones.golrie_room) end,
      "golrie_room_entered")
  elseif phase == "obtain_pebble" then
    return talk(
      config.npcs.golrie,
      config.points.golrie,
      function() return quantity(config.items.pebble) > 0 end,
      nil,
      false)
  elseif phase == "leave_gnome_dungeon" then
    return leave_gnome_dungeon()
  elseif phase == "equip_amulet" then
    local equipped = gc.await {
      action = { type = "item.interact", id = config.items.amulet, action = "Wear" },
      breaks = false,
      timeout = { game_ticks = 40 },
    }
    if equipped.status ~= "dispatched" then return equipped end
    if not wait_for(function()
      for _, item in ipairs(gc.read("equipment").items) do
        if item.id == config.items.amulet then return true end
      end
      return false
    end, 12) then
      return { status = "timed_out", result = "glarial_amulet_equip_unverified", receipt = equipped }
    end
    return { status = "complete", result = "glarial_amulet_equipped", receipt = equipped }
  elseif phase == "enter_falls" then
    return interact_object(
      config.objects.falls_entrance, "Open", config.points.falls_entrance, false,
      function() return in_zone(gc.read("player").world, config.zones.falls) end,
      "waterfall_dungeon_entered")
  end
  return { status = "rejected", result = "navigation_phase_unknown:" .. tostring(phase) }
end

return { execute = execute, escape_hostile_area = escape_hostile_area }
