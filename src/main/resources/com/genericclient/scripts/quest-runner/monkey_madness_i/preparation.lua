local config = gc.require("monkey_madness_config")
local preparation = gc.require("shared_preparation")
local travel = gc.require("shared_travel")

local ge = { x = 3165, y = 3491, plane = 0 }
local dueling_ring_ids = { 2552, 2554, 2556, 2558, 2560, 2562, 2564, 2566 }

local function current_ring_id()
  local inventory = gc.read("inventory")
  for _, id in ipairs(dueling_ring_ids) do
    for _, item in ipairs(inventory.items or {}) do
      if item.id == id and item.quantity > 0 then return id end
    end
  end
  return nil
end

local function arm_safety()
  local ring = current_ring_id()
  if not ring then
    return nil, { status = "monkey_madness_escape_ring_not_carried" }
  end
  local receipt = gc.await {
    action = {
      type = "safety.configure",
      minimum_hitpoints = 4,
      consumables = { { id = 379, action = "Eat", heal_amount = 12 } },
      continue_after_consumable = true,
      allow_overheal = true,
      escape = {
        type = "inventory_dialogue",
        item_id = ring,
        action = "Rub",
        choice = "Castle Wars Arena",
        x = 2440,
        y = 3089,
        plane = 0,
        within = 10,
      },
    },
    breaks = false,
  }
  if receipt.status ~= "complete" then
    return nil, { status = "monkey_madness_safety_failed", receipt = receipt }
  end
  return true, receipt
end

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
  local world = gc.read("player").world
  if current_ring_id() then
    local armed, safety_error = arm_safety()
    if not armed then return nil, safety_error end
  elseif not at_castle_wars(world) and not at_ge(world) and not at_ferox(world) then
    return nil, { status = "monkey_madness_escape_ring_not_carried" }
  end
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
  local prepared, failure = preparation.prepare_items(
    config.id,
    restock,
    config.ape_atoll_loadout,
    true,
    config.ape_atoll_minimum_free_slots)
  if not prepared then return nil, failure end
  local rearmed, rearm_error = arm_safety()
  if not rearmed then return nil, rearm_error end
  return true
end

return {
  prepare = prepare,
  arm_safety = arm_safety,
}
