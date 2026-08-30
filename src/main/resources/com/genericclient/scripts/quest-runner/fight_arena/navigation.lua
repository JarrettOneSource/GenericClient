local config = gc.require("fight_arena_config")
local interact = gc.require("fight_arena_interactions")
local travel = gc.require("shared_travel")

local function reach_lady()
  local reached = interact.approach(config.points.lady_servil, 3, true)
  if reached.status ~= "arrived" then return reached end
  return { status = "complete", result = "lady_servil_area_reached", receipt = reached }
end

local function obtain_armour()
  local stage = interact.varp()
  if stage ~= 1 and interact.carried(config.items.khazard_helmet) > 0 and
    interact.carried(config.items.khazard_armour) > 0 then
    return { status = "complete", result = "khazard_armour_already_owned" }
  end

  -- Stand inside the armoury; targeting the chest tile itself can stop outside
  -- its south wall even though the object is geometrically adjacent.
  local reached = interact.approach(config.points.armoury, 0, true)
  if reached.status ~= "arrived" then return reached end
  gc.await { event = "game.tick" }

  local searchable = interact.object(config.objects.armour_chest_closed, "Search", 20) or
    interact.object(config.objects.armour_chest_open, "Search", 20)
  local closed = interact.object(config.objects.armour_chest_closed, "Open", 20)
  if not searchable and closed then
    local opened = gc.await {
      action = {
        type = "object.interact",
        id = closed.id,
        action = "Open",
        world = closed.world,
        within = 20,
      },
      breaks = true,
      timeout = { game_ticks = 40 },
    }
    if opened.status ~= "dispatched" then return opened end
    if not interact.wait_for(function()
      return interact.object(config.objects.armour_chest_closed, "Search", 20) ~= nil or
        interact.object(config.objects.armour_chest_open, "Search", 20) ~= nil
    end, 30) then
      return { status = "timed_out", result = "armour_chest_open_unverified", receipt = opened }
    end
  end

  searchable = interact.object(config.objects.armour_chest_closed, "Search", 20) or
    interact.object(config.objects.armour_chest_open, "Search", 20)
  if not searchable then
    return {
      status = "rejected",
      result = "armour_chest_not_observed",
      nearby = gc.read("objects", { within = 20, limit = 50 }),
    }
  end
  local searched = gc.await {
    action = {
      type = "object.interact",
      id = searchable.id,
      action = "Search",
      world = searchable.world,
      within = 20,
    },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if searched.status ~= "dispatched" then return searched end
  if not interact.wait_for(function()
    if stage == 1 then return interact.varp() ~= stage end
    return interact.carried(config.items.khazard_helmet) > 0 and
      interact.carried(config.items.khazard_armour) > 0
  end, 40) then
    return { status = "timed_out", result = "khazard_armour_unverified", receipt = searched }
  end
  return { status = "complete", result = "khazard_armour_obtained", receipt = searched }
end

local function equip_one(id, label)
  if interact.quantity(gc.read("equipment"), id) > 0 then return true end
  if interact.quantity(gc.read("inventory"), id) == 0 then
    return nil, { status = "khazard_armour_missing", item = label }
  end
  local equipped = gc.await {
    action = { type = "item.interact", id = id, action = "Wear" },
    breaks = true,
    timeout = { game_ticks = 20 },
  }
  if equipped.status ~= "dispatched" then
    return nil, { status = "khazard_armour_equip_failed", item = label, receipt = equipped }
  end
  if not interact.wait_for(function()
    return interact.quantity(gc.read("equipment"), id) > 0
  end, 12) then
    return nil, { status = "khazard_armour_equip_unverified", item = label, receipt = equipped }
  end
  return true
end

local function equip_armour()
  local equipped, failure = equip_one(config.items.khazard_helmet, "helmet")
  if not equipped then return failure end
  equipped, failure = equip_one(config.items.khazard_armour, "platebody")
  if not equipped then return failure end
  return { status = "complete", result = "khazard_armour_equipped" }
end

local function reach_bar()
  if interact.distance(gc.read("player").world, config.points.bar) == 0 then
    return { status = "complete", result = "bar_customer_tile_reached" }
  end

  local near = interact.approach(config.points.bar_door, 1, true)
  if near.status ~= "arrived" then return near end
  gc.await { event = "game.tick" }
  local door = interact.object(config.objects.bar_door, "Open", 8)
  if door then
    local opened = gc.await {
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
    if opened.status ~= "dispatched" then return opened end
    gc.await { ticks = 2 }
  end

  local reached = interact.walk(config.points.bar, 0, true, 120)
  if reached.status ~= "arrived" then return reached end
  return { status = "complete", result = "bar_customer_tile_reached", receipt = reached }
end

local function reach_arena_area()
  local player = gc.read("player").world
  if player.x >= 10000 or interact.distance(player, config.points.sammy) <= 32 then
    return { status = "complete", result = "arena_area_already_reached" }
  end
  if travel.has_dueling_ring() then
    local teleported = travel.teleport_to_castle_wars(true)
    if teleported.status ~= "complete" then return teleported end
  end
  local reached = interact.walk(config.points.sammy, 5, true, 600)
  if reached.status ~= "arrived" then return reached end
  return { status = "complete", result = "arena_area_reached", receipt = reached }
end

return {
  reach_lady = reach_lady,
  obtain_armour = obtain_armour,
  equip_armour = equip_armour,
  reach_bar = reach_bar,
  reach_arena_area = reach_arena_area,
}
