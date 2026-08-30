local config = gc.require("monkey_madness_config")
local preparation = gc.require("shared_preparation")
local travel = gc.require("shared_travel")

local ge = { x = 3165, y = 3491, plane = 0 }

local function at_castle_wars(world)
  return world and world.plane == 0 and
    world.x >= 2425 and world.x <= 2455 and
    world.y >= 3075 and world.y <= 3105
end

local function at_ferox(world)
  return world and world.plane == 0 and
    world.x >= 3120 and world.x <= 3165 and
    world.y >= 3600 and world.y <= 3650
end

local function at_ge(world)
  return world and world.plane == 0 and
    math.max(math.abs(world.x - ge.x), math.abs(world.y - ge.y)) <= 8
end

local function reach_ge_from_ferox()
  gc.activity("travel")
  local teleported = travel.teleport_to_emirs_arena(true)
  if teleported.status ~= "complete" then
    return nil, { status = "monkey_madness_emirs_teleport_failed", receipt = teleported }
  end
  local walked = gc.await {
    action = { type = "walk.to", destination = ge, within = 8, run = true },
    breaks = true,
    timeout = { game_ticks = 600 },
  }
  if walked.status ~= "arrived" then
    return nil, { status = "monkey_madness_ge_travel_failed", receipt = walked }
  end
  return true
end

local function prepare(restock)
  local safety = gc.await {
    action = {
      type = "safety.configure",
      minimum_hitpoints = 4,
      consumables = { { id = 379, action = "Eat", heal_amount = 12 } },
      continue_after_consumable = true,
      allow_overheal = true,
    },
    breaks = false,
  }
  if safety.status ~= "complete" then
    return nil, { status = "monkey_madness_preparation_safety_failed", receipt = safety }
  end
  local world = gc.read("player").world
  if at_ferox(world) then
    local reached, failure = reach_ge_from_ferox()
    if not reached then return nil, failure end
  elseif not at_castle_wars(world) and not at_ge(world) then
    if not travel.has_dueling_ring() then
      return nil, { status = "monkey_madness_escape_ring_not_carried" }
    end
    gc.activity("travel")
    local teleported = travel.teleport_to_castle_wars(true)
    if teleported.status ~= "complete" then
      return nil, { status = "monkey_madness_bank_teleport_failed", receipt = teleported }
    end
  end
  return preparation.prepare_items(
    config.id,
    restock,
    config.ape_atoll_loadout,
    true)
end

return { prepare = prepare }
