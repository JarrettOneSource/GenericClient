local config = gc.require("grand_tree_config")
local interactions = gc.require("grand_tree_interactions")
local travel = gc.require("shared_travel")

local function distance(a, b)
  if not a or a.plane ~= b.plane then return 99999 end
  return math.max(math.abs(a.x - b.x), math.abs(a.y - b.y))
end

local function walk(world, within)
  return gc.await {
    action = { type = "walk.to", destination = world, within = within or 4, run = true },
    breaks = true,
    timeout = { game_ticks = 600 },
  }
end

local function reach_narnode()
  if gc.read("dialogue").open then return true end
  if interactions.npc(config.npcs.king_narnode, 20, true) then return true end
  gc.activity("travel")
  local current = gc.read("player").world
  local route
  if distance(current, config.points.king_narnode) <= 20 and current.y < 3492 then
    route = { config.points.grand_tree_door, config.points.king_narnode }
  elseif distance(current, config.points.king_narnode) <= 20 then
    route = { config.points.king_narnode }
  elseif distance(current, config.points.grand_tree_entrance) <= 24 then
    route = { config.points.grand_tree_entrance, config.points.king_narnode }
  else
    route = {
      config.points.grand_tree_south,
      config.points.grand_tree_entrance,
      config.points.king_narnode,
    }
  end
  for _, waypoint in ipairs(route) do
    local radius = waypoint == config.points.king_narnode and 2 or
      waypoint == config.points.grand_tree_door and 1 or 4
    if distance(gc.read("player").world, waypoint) > radius then
      local receipt = walk(waypoint, radius)
      if receipt.status ~= "arrived" then
        return nil, { status = "grand_tree_travel_failed", waypoint = waypoint, receipt = receipt }
      end
    end
    if interactions.npc(config.npcs.king_narnode, 20, true) then return true end
  end
  return nil, { status = "king_narnode_not_observed_after_travel", player = gc.read("player") }
end

local function reach_hazelmere()
  if gc.read("dialogue").open or interactions.npc(config.npcs.hazelmere, 16, true) then
    return true
  end
  gc.await { action = { type = "ui.close" }, breaks = false }
  gc.activity("travel")
  local current = gc.read("player").world
  if distance(current, config.points.hazelmere_ladder) > 120 then
    if not travel.has_dueling_ring() then
      return nil, { status = "hazelmere_transport_required", player = gc.read("player") }
    end
    local teleported = travel.teleport_to_castle_wars(true)
    if teleported.status ~= "complete" then
      return nil, { status = "hazelmere_transport_failed", receipt = teleported }
    end
  end
  current = gc.read("player").world
  local route = {}
  if current.x < 2500 then route[#route + 1] = config.points.castle_wars_exit end
  if current.x < 2570 then route[#route + 1] = config.points.yanille_west end
  if current.x < 2640 then route[#route + 1] = config.points.yanille_east end
  route[#route + 1] = config.points.hazelmere_ladder
  for _, waypoint in ipairs(route) do
    local radius = waypoint == config.points.hazelmere_ladder and 1 or 4
    if distance(gc.read("player").world, waypoint) > radius then
      local receipt = walk(waypoint, radius)
      if receipt.status ~= "arrived" then
        return nil, { status = "hazelmere_travel_failed", waypoint = waypoint, receipt = receipt }
      end
    end
  end
  local climbed = interactions.climb_hazelmere()
  if climbed.status ~= "complete" then return nil, climbed end
  if interactions.npc(config.npcs.hazelmere, 16, true) then return true end
  return nil, {
    status = "hazelmere_not_observed_after_travel",
    player = gc.read("player"),
    nearby = gc.read("npcs", { within = 20, limit = 30 }),
  }
end

local function return_to_narnode()
  if interactions.npc(config.npcs.king_narnode, 20, true) then return true end
  gc.activity("travel")
  local player = gc.read("player").world
  if distance(player, config.points.king_narnode) > 120 then
    if not travel.has_dueling_ring() then
      return nil, { status = "narnode_return_transport_required", player = gc.read("player") }
    end
    local teleported = travel.teleport_to_castle_wars(true)
    if teleported.status ~= "complete" then
      return nil, { status = "narnode_return_transport_failed", receipt = teleported }
    end
  end
  if gc.read("player").world.y < 3384 then
    for _, waypoint in ipairs(config.return_route) do
      if distance(gc.read("player").world, waypoint) > 4 then
        local receipt = walk(waypoint, 4)
        if receipt.status ~= "arrived" then
          return nil, { status = "narnode_return_failed", waypoint = waypoint, receipt = receipt }
        end
      end
    end
    if gc.read("player").world.y < 3384 then
      local stage = gc.read("vars", { varps = { config.varp } }).varps[config.varp]
      if stage >= 90 then
        local entered = interactions.enter_stronghold_with_femi()
        if entered.status ~= "complete" then return nil, entered end
      else
        local entered = walk(config.points.stronghold_gate_inside, 1)
        if entered.status ~= "arrived" then
          return nil, { status = "stronghold_reentry_failed", receipt = entered }
        end
      end
    end
  end
  return reach_narnode()
end

local function reach_glough()
  if interactions.npc(config.npcs.glough, 16, true) then return true end
  gc.activity("travel")
  local player = gc.read("player").world
  if player.plane == 0 then
    if player.y >= 3492 then
      local inside = walk(config.points.grand_tree_inside_door, 1)
      if inside.status ~= "arrived" then
        return nil, { status = "grand_tree_inside_door_failed", receipt = inside }
      end
    end
    local outside = walk(config.points.grand_tree_door, 1)
    if outside.status ~= "arrived" then
      return nil, { status = "grand_tree_exit_failed", receipt = outside }
    end
    local reached = walk(config.points.glough_ladder, 1)
    if reached.status ~= "arrived" then
      return nil, { status = "glough_house_travel_failed", receipt = reached }
    end
    local climbed = interactions.climb_glough()
    if climbed.status ~= "complete" then return nil, climbed end
  elseif player.plane ~= 1 or distance(player, config.points.glough_room) > 20 then
    return nil, { status = "glough_house_resume_location_unknown", player = player }
  end
  if not interactions.npc(config.npcs.glough, 16, true) then
    local approached = walk(config.points.glough_room, 2)
    if approached.status ~= "arrived" then
      return nil, { status = "glough_room_approach_failed", receipt = approached }
    end
  end
  if interactions.npc(config.npcs.glough, 16, true) then return true end
  return nil, {
    status = "glough_not_observed_after_travel",
    player = gc.read("player"),
    nearby = gc.read("npcs", { within = 20, limit = 30 }),
  }
end

local function return_after_glough()
  if interactions.npc(config.npcs.king_narnode, 20, true) then return true end
  gc.activity("travel")
  local player = gc.read("player").world
  if player.plane == 1 and distance(player, config.points.glough_room) <= 20 then
    local descended = interactions.descend_glough()
    if descended.status ~= "complete" then return nil, descended end
  elseif player.plane ~= 0 then
    return nil, { status = "post_glough_resume_location_unknown", player = player }
  end
  return reach_narnode()
end

local function reach_charlie()
  if interactions.npc(config.npcs.charlie, 20, true) then return true end
  gc.activity("travel")
  local climbed = interactions.climb_grand_tree_top()
  if climbed.status ~= "complete" then return nil, climbed end
  if not interactions.npc(config.npcs.charlie, 20, true) then
    local approached = walk(config.points.grand_tree_top, 3)
    if approached.status ~= "arrived" then
      return nil, { status = "charlie_approach_failed", receipt = approached }
    end
  end
  if interactions.npc(config.npcs.charlie, 20, true) then return true end
  return nil, {
    status = "charlie_not_observed_after_climb",
    player = gc.read("player"),
    nearby = gc.read("npcs", { within = 20, limit = 30 }),
  }
end

local function return_to_glough_for_journal()
  if gc.read("player").world.plane == 3 then
    local descended = interactions.descend_grand_tree_bottom()
    if descended.status ~= "complete" then return nil, descended end
  end
  return reach_glough()
end

local function reach_shipyard_foreman()
  if interactions.npc(config.npcs.shipyard_foreman, 20, true) then return true end
  local player = gc.read("player").world
  if player.plane ~= 0 or player.x < 2945 or player.x > 3007 or
    player.y < 3015 or player.y > 3070 then
    return nil, { status = "shipyard_not_entered", player = player }
  end
  gc.activity("travel")
  local reached = walk(config.points.shipyard_foreman, 3)
  if reached.status ~= "arrived" then
    return nil, { status = "shipyard_foreman_travel_failed", receipt = reached }
  end
  if interactions.npc(config.npcs.shipyard_foreman, 20, true) then return true end
  return nil, {
    status = "shipyard_foreman_not_observed_after_travel",
    player = gc.read("player"),
    nearby = gc.read("npcs", { within = 20, limit = 30 }),
  }
end

local function return_lumber_order_to_charlie()
  if interactions.npc(config.npcs.charlie, 20, true) then return true end
  local player = gc.read("player").world
  if player.plane ~= 3 or distance(player, config.points.grand_tree_top) > 20 then
    local returned, failure = return_to_narnode()
    if not returned then return nil, failure end
  end
  return reach_charlie()
end

return {
  reach_narnode = reach_narnode,
  reach_hazelmere = reach_hazelmere,
  return_to_narnode = return_to_narnode,
  reach_glough = reach_glough,
  return_after_glough = return_after_glough,
  reach_charlie = reach_charlie,
  return_to_glough_for_journal = return_to_glough_for_journal,
  reach_shipyard_foreman = reach_shipyard_foreman,
  return_lumber_order_to_charlie = return_lumber_order_to_charlie,
}
