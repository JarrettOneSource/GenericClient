local config = gc.require("witch_config")
local experiment = gc.require("witch_experiment")
local garden = gc.require("witch_garden")
local preparation = gc.require("shared_preparation")
local travel = gc.require("shared_travel")

local function quantity(container, id)
  if not container or not container.items then return 0 end
  local total = 0
  for _, item in ipairs(container.items) do
    if item.id == id then total = total + item.quantity end
  end
  return total
end

local function carried(id)
  return quantity(gc.read("inventory"), id) + quantity(gc.read("equipment"), id)
end

local function in_shed()
  local world = gc.read("player").world
  local zone = config.zones.shed
  return world.plane == zone.plane and world.x >= zone.x1 and world.x <= zone.x2 and
    world.y >= zone.y1 and world.y <= zone.y2
end

local function in_exposed_quest_area()
  local world = gc.read("player").world
  return world.plane == 0 and world.x >= 2900 and world.x <= 2937 and
    world.y >= 3459 and world.y <= 3475
end

local function preflight()
  local player = gc.read("player")
  local skills = gc.read("skills")
  local missing = {}
  if skills.magic.level < 13 then table.insert(missing, "Magic 13") end
  if player.max_hitpoints < 12 then table.insert(missing, "12 Hitpoints") end
  if player.current_hitpoints < player.max_hitpoints then table.insert(missing, "full Hitpoints") end
  if carried(1387) < 1 then table.insert(missing, "staff of fire") end
  if carried(556) < 300 then table.insert(missing, "300 air runes") end
  if carried(558) < 150 then table.insert(missing, "150 mind runes") end
  if carried(2550) < 4 then table.insert(missing, "four rings of recoil") end
  if carried(1993) * 11 < 60 then table.insert(missing, "60 Hitpoints of food") end
  if carried(2409) < 1 then table.insert(missing, "door key") end
  if carried(2411) < 1 then table.insert(missing, "shed key") end
  if not travel.has_necklace() then table.insert(missing, "charged games necklace") end
  if #missing > 0 then
    return nil, { status = "experiment_preflight_failed", missing = missing, player = player }
  end
  return true
end

local function equip(id, action, label)
  if quantity(gc.read("equipment"), id) > 0 then return true end
  if quantity(gc.read("inventory"), id) == 0 then
    return nil, { status = "experiment_equip_missing", item = label }
  end
  local receipt = gc.await {
    action = { type = "item.interact", id = id, action = action },
    breaks = false,
  }
  if receipt.status ~= "dispatched" then
    return nil, { status = "experiment_equip_failed", item = label, receipt = receipt }
  end
  for _ = 1, 6 do
    gc.await { event = "game.tick" }
    if quantity(gc.read("equipment"), id) > 0 then return true end
  end
  return nil, { status = "experiment_equip_unverified", item = label, receipt = receipt }
end

local function setup()
  local ok, failure = equip(1387, "Wield", "Staff of fire")
  if not ok then return nil, failure end
  ok, failure = equip(2550, "Wear", "Ring of recoil")
  if not ok then return nil, failure end
  local autocast = gc.await {
    action = { type = "combat.set_autocast", spell = "Fire Strike" },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if autocast.status ~= "set" and autocast.status ~= "unchanged" then
    return nil, { status = "experiment_autocast_failed", receipt = autocast }
  end
  local safety = gc.await {
    action = {
      type = "safety.configure",
      minimum_hitpoints = 3,
      consumables = { { id = 1993, action = "Drink", heal_amount = 11 } },
      continue_after_consumable = true,
      escape = {
        x = config.experiment.outside.x,
        y = config.experiment.outside.y,
        plane = config.experiment.outside.plane,
        within = 0,
      },
    },
    breaks = false,
  }
  if safety.status ~= "complete" then
    return nil, { status = "experiment_safety_failed", receipt = safety }
  end
  return true
end

local function prepare(restock)
  if in_exposed_quest_area() then
    local teleport = travel.teleport_to_burthorpe()
    if teleport.status ~= "complete" then return nil, teleport end
  end
  local loadout = {}
  for _, item in ipairs(config.combat_loadout) do table.insert(loadout, item) end
  local shed_keys = carried(2411) + quantity(gc.read("bank"), 2411)
  if shed_keys > 0 then
    table.insert(loadout, { id = 2411, name = "Key", quantity = 1, purchase = false })
  end
  return preparation.prepare_items("witchs_house", restock, loadout)
end

local function execute()
  local ready, failure = preflight()
  if not ready then return failure end
  local configured
  configured, failure = setup()
  if not configured then return failure end
  if not in_shed() then
    local route = garden.to_shed()
    if route.status == "garden_resume_requires_observed_route" then
      local teleport = travel.teleport_to_burthorpe()
      if teleport.status ~= "complete" then return teleport end
      route = garden.to_shed()
    end
    if route.status ~= "complete" then return route end
  end
  ready, failure = preflight()
  if not ready then return failure end
  return experiment.run_all_forms()
end

return { prepare = prepare, execute = execute }
