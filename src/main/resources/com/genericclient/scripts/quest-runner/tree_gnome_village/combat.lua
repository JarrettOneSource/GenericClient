local config = gc.require("tree_gnome_config")
local interact = gc.require("tree_gnome_interactions")
local navigation = gc.require("tree_gnome_navigation")

local function npc(id, within)
  return gc.read("npcs", {
    id = id,
    within = within or 20,
    limit = 5,
  })[1]
end

local function orbs_on_ground()
  return gc.read("ground_items", {
    id = config.items.remaining_orbs,
    within = 20,
    limit = 3,
  })[1]
end

local function equip_staff()
  if interact.quantity(gc.read("equipment"), config.items.staff_of_air) > 0 then return true end
  if interact.quantity(gc.read("inventory"), config.items.staff_of_air) == 0 then
    return nil, { status = "staff_missing" }
  end
  local equipped = gc.await {
    action = { type = "item.interact", id = config.items.staff_of_air, action = "Wield" },
    breaks = false,
    timeout = { game_ticks = 20 },
  }
  if equipped.status ~= "dispatched" then
    return nil, { status = "staff_equip_failed", receipt = equipped }
  end
  if not interact.wait_for(function()
    return interact.quantity(gc.read("equipment"), config.items.staff_of_air) > 0
  end, 10) then
    return nil, { status = "staff_equip_unverified", receipt = equipped }
  end
  return true
end

local function configure()
  local equipped, failure = equip_staff()
  if not equipped then return nil, failure end
  local autocast = gc.await {
    action = { type = "combat.set_autocast", spell = "Earth Bolt" },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if autocast.status ~= "set" and autocast.status ~= "unchanged" then
    return nil, { status = "earth_bolt_autocast_failed", receipt = autocast }
  end
  local safety = gc.await {
    action = {
      type = "safety.configure",
      minimum_hitpoints = 6,
      consumables = { { id = config.items.wine, action = "Drink", heal_amount = 11 } },
      continue_after_consumable = true,
      allow_overheal = false,
      escape = {
        x = config.points.warlord_cast.x,
        y = config.points.warlord_cast.y,
        plane = config.points.warlord_cast.plane,
        within = 0,
      },
    },
    breaks = false,
  }
  if safety.status ~= "complete" then
    return nil, { status = "warlord_safety_failed", receipt = safety }
  end
  return true
end

local function start_fight()
  if npc(config.npcs.warlord_combat, 20) then return true end
  local talked = interact.talk(
    config.npcs.warlord_chat,
    config.points.warlord,
    function()
      return npc(config.npcs.warlord_combat, 20) ~= nil or orbs_on_ground() ~= nil
    end,
    {},
    false)
  if talked.status ~= "complete" then return nil, talked end
  return true
end

local function attack()
  local target = npc(config.npcs.warlord_combat, 20)
  if not target then return nil, { status = "warlord_not_observed" } end
  local receipt = gc.await {
    action = {
      type = "npc.interact",
      id = config.npcs.warlord_combat,
      action = "Attack",
      within = 20,
    },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if receipt.status ~= "dispatched" then
    return nil, { status = "warlord_attack_failed", receipt = receipt, target = target }
  end
  return true
end

local function reset_safespot()
  local west = interact.walk(config.points.warlord_reset, 0, false, 120)
  if west.status ~= "arrived" then return nil, west end
  local south = interact.walk(config.points.warlord_cast, 0, false, 120)
  if south.status ~= "arrived" then return nil, south end
  return true
end

local function fight()
  local ready, failure = configure()
  if not ready then return failure end
  local reached = navigation.reach_warlord()
  if reached.status ~= "complete" then return reached end
  ready, failure = start_fight()
  if not ready then return failure end
  if orbs_on_ground() then return { status = "complete", result = "warlord_defeated" } end

  ready, failure = reset_safespot()
  if not ready then return failure end
  ready, failure = attack()
  if not ready then return failure end

  local missing_ticks = 0
  for _ = 1, 600 do
    gc.await { event = "game.tick" }
    local drop = orbs_on_ground()
    if drop then
      return { status = "complete", result = "warlord_defeated", drop = drop }
    end

    local target = npc(config.npcs.warlord_combat, 20)
    if not target then
      missing_ticks = missing_ticks + 1
      if missing_ticks >= 20 then
        return { status = "warlord_missing", messages = gc.read("messages", { limit = 20 }) }
      end
    else
      missing_ticks = 0
      if target.distance <= 3 then
        ready, failure = reset_safespot()
        if not ready then return failure end
        ready, failure = attack()
        if not ready then return failure end
      elseif not gc.read("player").interacting then
        ready, failure = attack()
        if not ready then return failure end
      end
    end
  end
  return { status = "warlord_timeout", messages = gc.read("messages", { limit = 20 }) }
end

local function take_orbs()
  local drop = orbs_on_ground()
  if not drop then return { status = "rejected", result = "orbs_not_observed" } end
  local taken = gc.await {
    action = {
      type = "ground_item.take",
      id = config.items.remaining_orbs,
      world = drop.world,
      within = 20,
    },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if taken.status ~= "dispatched" then return taken end
  if not interact.wait_for(function()
    return interact.carried(config.items.remaining_orbs) > 0
  end, 30) then
    return { status = "timed_out", result = "orbs_pickup_unverified", receipt = taken }
  end
  return { status = "complete", result = "orbs_obtained", receipt = taken }
end

return { fight = fight, take_orbs = take_orbs }
