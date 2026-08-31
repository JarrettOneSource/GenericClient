local config = gc.require("monkey_madness_config")

local function distance(a, b)
  if not a or not b or a.plane ~= b.plane then return 99999 end
  return math.max(math.abs(a.x - b.x), math.abs(a.y - b.y))
end

local function npc(ids, within)
  for _, id in ipairs(ids) do
    local target = gc.read("npcs", {
      id = id,
      within = within or 24,
      limit = 1,
    })[1]
    if target then return target end
  end
  return nil
end

local function walk(destination, within)
  gc.activity("travel")
  return gc.await {
    action = { type = "walk.to", destination = destination, within = within or 4, run = true },
    breaks = true,
    timeout = { game_ticks = 600 },
  }
end

local function in_zone(world, zone)
  return world and (zone.plane == nil or world.plane == zone.plane) and
    world.x >= zone.x1 and world.x <= zone.x2 and
    world.y >= zone.y1 and world.y <= zone.y2
end

local function object(id, action, within)
  return gc.read("objects", {
    id = id,
    action = action,
    within = within or 12,
    limit = 1,
  })[1]
end

local function wait_for(predicate, ticks)
  for _ = 1, ticks do
    gc.await { event = "game.tick" }
    if predicate() then return true end
  end
  return false
end

local function enter_grand_tree()
  local world = gc.read("player").world
  if world.y >= config.points.grand_tree_ladder_staging.y then
    return { status = "complete", result = "grand_tree_already_entered" }
  end
  local reached_door = walk(config.points.grand_tree_door, 1)
  if reached_door.status ~= "arrived" then return reached_door end
  local opened
  for _ = 1, 3 do
    local door
    for _, id in ipairs(config.objects.grand_tree_doors) do
      door = object(id, "Open", 8)
      if door then break end
    end
    if not door then break end
    gc.activity("travel")
    opened = gc.await {
      action = {
        type = "object.interact",
        id = door.id,
        action = "Open",
        world = door.world,
        within = 8,
      },
      breaks = true,
      timeout = { game_ticks = 40 },
    }
    if opened.status == "dispatched" then
      gc.await { event = "game.tick" }
      break
    end
    if opened.result ~= "menu_event_timeout" then return opened end
    gc.await { event = "game.tick" }
  end
  if opened and opened.status ~= "dispatched" then
    for _, id in ipairs(config.objects.grand_tree_doors) do
      if object(id, "Open", 8) then return opened end
    end
  end
  local crossed = walk(config.points.grand_tree_ladder_staging, 0)
  if crossed.status ~= "arrived" then
    return {
      status = "monkey_madness_grand_tree_entry_failed",
      opened = opened,
      crossed = crossed,
      player = gc.read("player"),
    }
  end
  return { status = "complete", result = "grand_tree_entered", opened = opened }
end

local function climb_to_top()
  for _ = 1, 4 do
    local world = gc.read("player").world
    if world.plane == 3 then return { status = "complete", result = "grand_tree_top_reached" } end
    if not in_zone(world, config.zones.grand_tree) or world.plane < 0 or world.plane > 2 then
      return { status = "monkey_madness_grand_tree_floor_unknown", player = world }
    end
    local ladder_id
    local action
    local expected_plane
    if world.plane == 0 then
      ladder_id = config.objects.grand_tree_ladder_bottom
      action = "Top-Floor"
      expected_plane = 3
    elseif world.plane == 1 then
      ladder_id = config.objects.grand_tree_ladder_first
      action = "Climb-up"
      expected_plane = 2
    else
      ladder_id = config.objects.grand_tree_ladder_second
      action = "Climb-up"
      expected_plane = 3
    end
    local ladder = object(ladder_id, action, 14)
    if not ladder then
      return {
        status = "monkey_madness_grand_tree_ladder_not_observed",
        plane = world.plane,
        objects = gc.read("objects", { within = 14, limit = 40 }),
      }
    end
    gc.activity("travel")
    local climbed = gc.await {
      action = {
        type = "object.interact",
        id = ladder.id,
        action = action,
        world = ladder.world,
        within = 14,
      },
      breaks = true,
      timeout = { game_ticks = 40 },
    }
    if climbed.status ~= "dispatched" then return climbed end
    if not wait_for(function() return gc.read("player").world.plane == expected_plane end, 30) then
      return { status = "monkey_madness_grand_tree_climb_unverified", receipt = climbed }
    end
  end
  return { status = "monkey_madness_grand_tree_top_unverified", player = gc.read("player") }
end

local function fly_to_gandius()
  if in_zone(gc.read("player").world, config.zones.gandius) then
    return { status = "complete", result = "gandius_already_reached" }
  end
  local climbed = climb_to_top()
  if climbed.status ~= "complete" then return climbed end
  local pilot = npc(config.npcs.captain_errdo, 24)
  if not pilot then
    return {
      status = "monkey_madness_glider_pilot_not_observed",
      nearby = gc.read("npcs", { within = 24, limit = 40 }),
    }
  end
  gc.activity("travel")
  local opened = gc.await {
    action = { type = "npc.interact", id = pilot.id, action = "Glider", within = 24 },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if opened.status ~= "dispatched" then return opened end
  if not wait_for(function()
    return #gc.read("widgets", { ids = { config.widgets.glider_karamja }, limit = 1 }) > 0
  end, 30) then
    return { status = "monkey_madness_glider_map_not_observed", receipt = opened }
  end
  local selected = gc.await {
    action = { type = "ui.click", widget_id = config.widgets.glider_karamja },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if selected.status ~= "dispatched" then return selected end
  if not wait_for(function()
    return in_zone(gc.read("player").world, config.zones.gandius)
  end, 50) then
    return {
      status = "monkey_madness_gandius_arrival_unverified",
      receipt = selected,
      player = gc.read("player"),
    }
  end
  return { status = "complete", result = "gandius_reached", receipt = selected }
end

local function travel_to_gnome_stronghold()
  if in_zone(gc.read("player").world, config.zones.grand_tree) or
    in_zone(gc.read("player").world, config.zones.stronghold_transport) then
    return { status = "complete", result = "grand_tree_already_reached" }
  end
  local tree = object(config.objects.spirit_tree, "Travel", 14)
  if not tree then
    local reached = walk(config.points.ge_spirit_tree, 5)
    if reached.status ~= "arrived" then return reached end
    tree = object(config.objects.spirit_tree, "Travel", 14)
  end
  if not tree then
    return {
      status = "monkey_madness_spirit_tree_not_observed",
      player = gc.read("player"),
      objects = gc.read("objects", { within = 16, limit = 40 }),
    }
  end
  gc.activity("travel")
  local opened = gc.await {
    action = {
      type = "object.interact",
      id = tree.id,
      action = "Travel",
      world = tree.world,
      within = 14,
    },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if opened.status ~= "dispatched" then return opened end
  gc.await { event = "game.tick" }
  local selected = gc.await {
    action = { type = "ui.key", key = "2" },
    breaks = true,
    timeout = { game_ticks = 20 },
  }
  if selected.status ~= "dispatched" then return selected end
  if not wait_for(function()
    local world = gc.read("player").world
    return in_zone(world, config.zones.grand_tree) or
      in_zone(world, config.zones.stronghold_transport)
  end, 80) then
    return {
      status = "monkey_madness_spirit_tree_arrival_unverified",
      receipt = selected,
      player = gc.read("player"),
    }
  end
  return { status = "complete", result = "grand_tree_reached", receipt = selected }
end

local function leave_shipyard()
  if not in_zone(gc.read("player").world, config.zones.shipyard) then
    return { status = "complete", result = "already_outside_shipyard" }
  end
  local reached = walk(config.points.shipyard_gate_inside, 1)
  if reached.status ~= "arrived" then return reached end
  local gate = object(config.objects.shipyard_gate, "Open", 10)
  if not gate then
    return {
      status = "monkey_madness_shipyard_exit_gate_not_observed",
      objects = gc.read("objects", { within = 12, limit = 40 }),
    }
  end
  gc.activity("travel")
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
  if opened.status ~= "dispatched" then return opened end
  local crossed = walk(config.points.shipyard_gate_outside, 0)
  if crossed.status ~= "arrived" then return crossed end
  if in_zone(gc.read("player").world, config.zones.shipyard) then
    return {
      status = "monkey_madness_shipyard_exit_unverified",
      receipt = opened,
      player = gc.read("player"),
    }
  end
  return { status = "complete", result = "shipyard_exited", receipt = opened }
end

local function fly_to_grand_tree()
  if in_zone(gc.read("player").world, config.zones.grand_tree) then
    return { status = "complete", result = "grand_tree_already_reached" }
  end
  if not in_zone(gc.read("player").world, config.zones.gandius) then
    return {
      status = "monkey_madness_gandius_resume_location_unknown",
      player = gc.read("player"),
    }
  end
  local exited = leave_shipyard()
  if exited.status ~= "complete" then return exited end
  local reached = walk(config.points.gandius_glider, 6)
  if reached.status ~= "arrived" then return reached end
  local pilot = npc(config.npcs.gandius_pilot, 24)
  if not pilot then
    return {
      status = "monkey_madness_gandius_pilot_not_observed",
      nearby = gc.read("npcs", { within = 24, limit = 40 }),
    }
  end
  gc.activity("travel")
  local opened = gc.await {
    action = { type = "npc.interact", id = pilot.id, action = "Glider", within = 24 },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if opened.status ~= "dispatched" then return opened end
  if not wait_for(function()
    return #gc.read("widgets", { ids = { config.widgets.glider_grand_tree }, limit = 1 }) > 0
  end, 30) then
    return { status = "monkey_madness_return_glider_map_not_observed", receipt = opened }
  end
  local selected = gc.await {
    action = { type = "ui.click", widget_id = config.widgets.glider_grand_tree },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if selected.status ~= "dispatched" then return selected end
  if not wait_for(function()
    return in_zone(gc.read("player").world, config.zones.grand_tree)
  end, 50) then
    return {
      status = "monkey_madness_grand_tree_arrival_unverified",
      receipt = selected,
      player = gc.read("player"),
    }
  end
  return { status = "complete", result = "grand_tree_reached", receipt = selected }
end

local function descend_to_ground()
  local world = gc.read("player").world
  if world.plane == 0 then return { status = "complete", result = "grand_tree_ground_reached" } end
  if not in_zone(world, config.zones.grand_tree) or world.plane < 1 or world.plane > 3 then
    return { status = "monkey_madness_grand_tree_descent_location_unknown", player = world }
  end
  local ladder_ids = {
    [1] = config.objects.grand_tree_ladder_first,
    [2] = config.objects.grand_tree_ladder_second,
    [3] = config.objects.grand_tree_ladder_top,
  }
  local ladder = object(ladder_ids[world.plane], "Bottom-Floor", 14)
  if not ladder then
    return {
      status = "monkey_madness_grand_tree_descent_ladder_not_observed",
      plane = world.plane,
      objects = gc.read("objects", { within = 14, limit = 40 }),
    }
  end
  gc.activity("travel")
  local descended = gc.await {
    action = {
      type = "object.interact",
      id = ladder.id,
      action = "Bottom-Floor",
      world = ladder.world,
      within = 14,
    },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if descended.status ~= "dispatched" then return descended end
  if not wait_for(function() return gc.read("player").world.plane == 0 end, 30) then
    return { status = "monkey_madness_grand_tree_descent_unverified", receipt = descended }
  end
  return { status = "complete", result = "grand_tree_ground_reached", receipt = descended }
end

local function reach_shipyard_gate()
  local flown = fly_to_gandius()
  if flown.status ~= "complete" then return flown end
  local reached = walk(config.points.shipyard_gate, 3)
  if reached.status ~= "arrived" then return reached end
  return { status = "complete", result = "shipyard_gate_reached", receipt = reached }
end

local function reach_caranock()
  local reached = walk(config.points.caranock, 4)
  if reached.status ~= "arrived" then return nil, reached end
  local target = npc(config.npcs.caranock, 20)
  if target then return target end
  return nil, {
    status = "monkey_madness_caranock_not_observed",
    player = gc.read("player"),
    nearby = gc.read("npcs", { within = 24, limit = 40 }),
  }
end

local function reach_daero()
  local target = npc(config.npcs.daero, 24)
  if target then return target end
  local world = gc.read("player").world
  if not in_zone(world, config.zones.grand_tree) and
    not in_zone(world, config.zones.stronghold_transport) then
    return nil, { status = "monkey_madness_daero_resume_location_unknown", player = world }
  end
  if world.plane > 1 then
    local descended = descend_to_ground()
    if descended.status ~= "complete" then return nil, descended end
    world = gc.read("player").world
  end
  if world.plane == 0 then
    if world.y < config.points.grand_tree_entrance.y then
      local entered = walk(config.points.grand_tree_entrance, 0)
      if entered.status ~= "arrived" then return nil, entered end
      world = gc.read("player").world
    end
    local entered = enter_grand_tree()
    if entered.status ~= "complete" then return nil, entered end
    world = gc.read("player").world
    local ladder = object(config.objects.grand_tree_ladder_bottom, "Climb-up", 14)
    if not ladder then
      return nil, {
        status = "monkey_madness_daero_ladder_not_observed",
        objects = gc.read("objects", { within = 14, limit = 40 }),
      }
    end
    gc.activity("travel")
    local climbed = gc.await {
      action = {
        type = "object.interact",
        id = ladder.id,
        action = "Climb-up",
        world = ladder.world,
        within = 14,
      },
      breaks = true,
      timeout = { game_ticks = 40 },
    }
    if climbed.status ~= "dispatched" then return nil, climbed end
    if not wait_for(function() return gc.read("player").world.plane == 1 end, 30) then
      return nil, { status = "monkey_madness_daero_floor_unverified", receipt = climbed }
    end
  elseif world.plane ~= 1 then
    return nil, { status = "monkey_madness_daero_floor_unknown", player = world }
  end
  local reached = walk(config.points.daero, 5)
  if reached.status ~= "arrived" then return nil, reached end
  target = npc(config.npcs.daero, 24)
  if target then return target end
  return nil, {
    status = "monkey_madness_daero_not_observed",
    player = gc.read("player"),
    nearby = gc.read("npcs", { within = 24, limit = 40 }),
  }
end

local function reach_post_puzzle_daero()
  local target = npc(config.npcs.daero, 30)
  if target then return target end
  local world = gc.read("player").world
  if not in_zone(world, config.zones.post_puzzle_hangar) then
    return nil, { status = "monkey_madness_post_puzzle_resume_location_unknown", player = world }
  end
  local reached = walk(config.points.post_puzzle_daero, 6)
  if reached.status ~= "arrived" then return nil, reached end
  target = npc(config.npcs.daero, 30)
  if target then return target end
  return nil, {
    status = "monkey_madness_post_puzzle_daero_not_observed",
    player = gc.read("player"),
    nearby = gc.read("npcs", { within = 30, limit = 50 }),
  }
end

local function reach_narnode()
  local target = npc(config.npcs.king_narnode, 24)
  if target then return target end
  local player = gc.read("player").world
  if in_zone(player, config.zones.gandius) then
    local returned = fly_to_grand_tree()
    if returned.status ~= "complete" then return nil, returned end
    player = gc.read("player").world
  end
  if in_zone(player, config.zones.grand_tree) and player.plane > 0 then
    local descended = descend_to_ground()
    if descended.status ~= "complete" then return nil, descended end
    player = gc.read("player").world
  end
  if player.plane ~= 0 then
    return nil, { status = "monkey_madness_narnode_resume_location_unknown", player = player }
  end
  if player.y < config.points.grand_tree_entrance.y then
    local entered = walk(config.points.grand_tree_entrance, 0)
    if entered.status ~= "arrived" then return nil, entered end
  end
  if gc.read("player").world.y < config.points.grand_tree_door.y then
    local reached_door = walk(config.points.grand_tree_door, 1)
    if reached_door.status ~= "arrived" then return nil, reached_door end
  end
  local reached = walk(config.points.king_narnode, 3)
  if reached.status ~= "arrived" then return nil, reached end
  target = npc(config.npcs.king_narnode, 20)
  if target then return target end
  return nil, {
    status = "monkey_madness_narnode_not_observed",
    player = gc.read("player"),
    nearby = gc.read("npcs", { within = 24, limit = 40 }),
  }
end

return {
  distance = distance,
  npc = npc,
  walk = walk,
  reach_narnode = reach_narnode,
  travel_to_gnome_stronghold = travel_to_gnome_stronghold,
  fly_to_grand_tree = fly_to_grand_tree,
  leave_shipyard = leave_shipyard,
  descend_to_ground = descend_to_ground,
  reach_shipyard_gate = reach_shipyard_gate,
  reach_caranock = reach_caranock,
  reach_daero = reach_daero,
  reach_post_puzzle_daero = reach_post_puzzle_daero,
}
