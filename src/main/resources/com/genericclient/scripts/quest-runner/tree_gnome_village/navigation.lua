local config = gc.require("tree_gnome_config")
local interact = gc.require("tree_gnome_interactions")
local state = gc.require("tree_gnome_state")
local travel = gc.require("shared_travel")

local function walk_route(route, reverse, breaks)
  local receipts = {}
  local first = reverse and #route or 1
  local last = reverse and 1 or #route
  local step = reverse and -1 or 1
  local current = gc.read("player").world
  local start = first

  for index = first, last, step do
    if interact.distance(current, route[index]) <= 1 then
      start = index
      break
    end
  end
  for index = start, last, step do
    local walked = interact.walk(route[index], 0, breaks, 900)
    receipts[#receipts + 1] = walked
    if walked.status ~= "arrived" then
      return nil, { status = "maze_route_failed", index = index, receipt = walked }
    end
  end
  return receipts
end

local function near_village()
  return interact.distance(gc.read("player").world, config.points.maze_outside) <= 150
end

local function reach_village_area()
  if near_village() then return { status = "complete", result = "already_near_village" } end
  return travel.teleport_to_castle_wars()
end

local function enter_village_through_maze()
  if state.in_zone(gc.read("player").world, config.zones.village) then
    return { status = "complete", result = "already_inside_village" }
  end
  local reached = reach_village_area()
  if reached.status ~= "complete" then return reached end
  local route, failure = walk_route(config.maze_route, false, true)
  if not route then return failure end
  if not state.in_zone(gc.read("player").world, config.zones.village) then
    return { status = "timed_out", result = "village_entry_unverified", route = route }
  end
  return { status = "complete", result = "village_entered", route = route }
end

local function leave_village_through_maze()
  if not state.in_zone(gc.read("player").world, config.zones.village) then
    return { status = "complete", result = "already_outside_village" }
  end
  local near_end = interact.approach(config.points.maze_inside, 0, true)
  if near_end.status ~= "arrived" then return near_end end
  local route, failure = walk_route(config.maze_route, true, true)
  if not route then return failure end
  return { status = "complete", result = "village_left", route = route }
end

local function enter_village_with_elkoy()
  if state.in_zone(gc.read("player").world, config.zones.village) then
    return { status = "complete", result = "already_inside_village" }
  end
  local reached = reach_village_area()
  if reached.status ~= "complete" then return reached end
  return interact.talk(
    config.npcs.elkoy_outside,
    config.points.elkoy,
    function() return state.in_zone(gc.read("player").world, config.zones.village) end,
    { "Yes please." },
    true)
end

local function dynamic_object_action(name, action, predicate, breaks)
  local found = gc.read("objects", {
    name = name,
    action = action,
    within = 20,
    limit = 10,
  })
  local target = found[1]
  if not target then
    return {
      status = "rejected",
      result = "named_object_not_observed",
      name = name,
      action = action,
      nearby = gc.read("objects", { within = 20, limit = 30 }),
    }
  end
  local clicked = gc.await {
    action = {
      type = "object.interact",
      id = target.id,
      action = action,
      world = target.world,
      within = 20,
    },
    breaks = breaks ~= false,
    timeout = { game_ticks = 40 },
  }
  if clicked.status ~= "dispatched" then return clicked end
  if not interact.wait_for(predicate, 40) then
    return { status = "timed_out", result = "named_object_result_unverified", receipt = clicked }
  end
  return { status = "complete", result = "named_object_result_verified", receipt = clicked }
end

local function climb_tower_ladder()
  if gc.read("dialogue").type == "continue" then
    local drained, failure = interact.finish_dialogue(
      function() return true end, {}, true, 30)
    if not drained then return failure end
  end
  local ladder = interact.object(config.objects.tower_ladder, "Climb-up", 16)
  if not ladder then
    return {
      status = "rejected",
      result = "tower_ladder_not_observed",
      nearby = gc.read("objects", { within = 16, limit = 40 }),
    }
  end
  local clicked = gc.await {
    action = {
      type = "object.interact",
      id = config.objects.tower_ladder,
      action = "Climb-up",
      world = ladder.world,
      within = 16,
    },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if clicked.status ~= "dispatched" then return clicked end
  if not interact.wait_for(function()
    return gc.read("player").world.plane == 1
  end, 40) then
    return {
      status = "timed_out",
      result = "tower_ladder_result_unverified",
      receipt = clicked,
      messages = gc.read("messages", { limit = 20 }),
    }
  end
  return { status = "complete", result = "tower_ladder_climbed", receipt = clicked }
end

local function enter_orb_tower()
  local world = gc.read("player").world
  if state.in_zone(world, config.zones.tower_upstairs) then
    return { status = "complete", result = "already_upstairs" }
  end
  if state.in_zone(world, config.zones.tower_ground) then return climb_tower_ladder() end

  local reached = interact.walk(config.points.crumbled_wall, 2, true, 900)
  if reached.status ~= "arrived" then return reached end
  local crossed = interact.object_action(
    config.objects.crumbled_wall,
    "Climb-over",
    config.points.crumbled_wall,
    function() return state.in_zone(gc.read("player").world, config.zones.tower_ground) end,
    true,
    12)
  if crossed.status ~= "complete" then return crossed end
  return climb_tower_ladder()
end

local function search_orb_chest()
  if interact.carried(config.items.first_orb) > 0 then
    return { status = "complete", result = "orb_already_carried" }
  end

  local closed = interact.object(config.objects.chest_closed, "Open", 16)
  if closed then
    local opened = gc.await {
      action = {
        type = "object.interact",
        id = config.objects.chest_closed,
        action = "Open",
        world = closed.world,
        within = 16,
      },
      breaks = true,
      timeout = { game_ticks = 40 },
    }
    if opened.status ~= "dispatched" then return opened end
    if not interact.wait_for(function()
      return interact.object(config.objects.chest_open, "Search", 16) ~= nil
    end, 30) then
      return {
        status = "timed_out",
        result = "orb_chest_open_unverified",
        receipt = opened,
      }
    end
  end

  local open = interact.object(config.objects.chest_open, "Search", 16)
  if not open then
    return {
      status = "rejected",
      result = "open_orb_chest_not_observed",
      nearby = gc.read("objects", { within = 20, limit = 30 }),
    }
  end
  local searched = gc.await {
    action = {
      type = "object.interact",
      id = config.objects.chest_open,
      action = "Search",
      world = open.world,
      within = 16,
    },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if searched.status ~= "dispatched" then return searched end
  if not interact.wait_for(function()
    return interact.carried(config.items.first_orb) > 0
  end, 40) then
    return {
      status = "timed_out",
      result = "orb_chest_search_unverified",
      receipt = searched,
    }
  end
  return { status = "complete", result = "first_orb_obtained", receipt = searched }
end

local function leave_orb_tower()
  if gc.read("player").world.plane == 1 then
    local down = dynamic_object_action(
      "Ladder",
      "Climb-down",
      function() return gc.read("player").world.plane == 0 end,
      true)
    if down.status ~= "complete" then return down end
  end
  if state.in_zone(gc.read("player").world, config.zones.tower_ground) then
    local door = interact.object(config.objects.khazard_door, "Open", 20)
    if door then
      local opened = gc.await {
        action = {
          type = "object.interact",
          id = config.objects.khazard_door,
          action = "Open",
          world = door.world,
          within = 20,
        },
        breaks = true,
        timeout = { game_ticks = 40 },
      }
      if opened.status ~= "dispatched" then return opened end
    end
    local outside = interact.walk(config.points.tower_exit, 1, true, 120)
    if outside.status ~= "arrived" then return outside end
  end
  return { status = "complete", result = "orb_tower_left" }
end

local function return_to_village()
  local left = leave_orb_tower()
  if left.status ~= "complete" then return left end
  return enter_village_with_elkoy()
end

local function reach_warlord()
  local left = leave_village_through_maze()
  if left.status ~= "complete" then return left end
  if interact.distance(gc.read("player").world, config.points.warlord) > 400 and
    travel.has_dueling_ring() then
    local teleported = travel.teleport_to_castle_wars(true)
    if teleported.status ~= "complete" then
      return { status = "warlord_transport_failed", receipt = teleported }
    end
  end
  local walked = interact.walk(config.points.warlord, 2, true, 900)
  if walked.status ~= "arrived" then return walked end
  return { status = "complete", result = "warlord_area_reached", receipt = walked }
end

local function escape_hostile_area()
  local teleported = travel.teleport_to_castle_wars(false)
  if teleported.status == "complete" then return teleported end
  local necklace = travel.teleport_to_barbarian_outpost(false)
  if necklace.status == "complete" then return necklace end
  local walked = interact.walk(config.points.warlord_fallback, 2, false, 120)
  if walked.status == "arrived" then return walked end
  return {
    status = "escape_failed",
    ring = teleported,
    necklace = necklace,
    walk = walked,
  }
end

return {
  enter_village_through_maze = enter_village_through_maze,
  leave_village_through_maze = leave_village_through_maze,
  enter_village_with_elkoy = enter_village_with_elkoy,
  enter_orb_tower = enter_orb_tower,
  search_orb_chest = search_orb_chest,
  leave_orb_tower = leave_orb_tower,
  return_to_village = return_to_village,
  reach_warlord = reach_warlord,
  escape_hostile_area = escape_hostile_area,
}
