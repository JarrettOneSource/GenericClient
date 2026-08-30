local config = gc.require("grand_tree_config")
local preparation = gc.require("shared_preparation")
local travel = gc.require("shared_travel")

local function quantity(container, id)
  local total = 0
  for _, item in ipairs((container and container.items) or {}) do
    if item.id == id then total = total + item.quantity end
  end
  return total
end

local function distance(a, b)
  if not a or a.plane ~= b.plane then return 99999 end
  return math.max(math.abs(a.x - b.x), math.abs(a.y - b.y))
end

local function equip_staff()
  if quantity(gc.read("equipment"), config.combat.staff) > 0 then return true end
  local equipped = gc.await {
    action = { type = "item.interact", id = config.combat.staff, action = "Wield" },
    breaks = false,
    timeout = { game_ticks = 20 },
  }
  if equipped.status ~= "dispatched" then return nil, equipped end
  for _ = 1, 15 do
    gc.await { event = "game.tick" }
    if quantity(gc.read("equipment"), config.combat.staff) > 0 then return true end
  end
  return nil, { status = "black_demon_staff_equip_unverified", receipt = equipped }
end

local function prepare(restock)
  local loaded, loadout_error = preparation.prepare_items(
    config.id, restock or "bank_only", config.loadout)
  if not loaded then return loadout_error end
  local equipped, equip_error = equip_staff()
  if not equipped then return equip_error end
  return { status = "complete", result = "black_demon_loadout_prepared" }
end

local function npc(ids, within)
  for _, id in ipairs(ids) do
    local found = gc.read("npcs", { id = id, within = within or 24, limit = 1 })[1]
    if found then return found end
  end
  return nil
end

local function drain_continue_dialogue()
  for _ = 1, 12 do
    local dialogue = gc.read("dialogue")
    if dialogue.type ~= "continue" then return true end
    local continued = gc.await {
      action = { type = "dialogue.continue" },
      breaks = false,
      timeout = { game_ticks = 20 },
    }
    if continued.status ~= "dispatched" then return false, continued end
    gc.await { event = "game.tick" }
  end
  return gc.read("dialogue").type ~= "continue"
end

local function descend_watchtower()
  local player = gc.read("player").world
  if player.y >= 9800 or player.x >= 10000 or npc(config.npcs.black_demon, 30) then
    return { status = "complete", result = "demon_tunnel_already_reached" }
  end
  local trapdoor = gc.read("objects", {
    id = config.objects.watchtower_trapdoor,
    within = 16,
    limit = 3,
  })[1]
  if not trapdoor then
    return { status = "watchtower_trapdoor_not_observed", objects = gc.read("objects", { within = 16, limit = 60 }) }
  end
  local action = nil
  for _, candidate in ipairs(trapdoor.actions or {}) do
    if candidate == "Climb-down" or candidate == "Open" then action = candidate break end
  end
  if not action then
    return { status = "watchtower_trapdoor_action_missing", trapdoor = trapdoor }
  end
  local descended = gc.await {
    action = {
      type = "object.interact",
      id = trapdoor.id,
      action = action,
      world = trapdoor.world,
      within = 16,
    },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if descended.status ~= "dispatched" then return descended end
  for _ = 1, 40 do
    gc.await { event = "game.tick" }
    local current = gc.read("player").world
    if current.plane ~= player.plane or distance(current, player) > 32 or
      gc.read("dialogue").type ~= "closed" or npc(config.npcs.black_demon, 30) then
      return { status = "complete", result = "demon_tunnel_reached", receipt = descended }
    end
  end
  return { status = "demon_tunnel_unverified", receipt = descended }
end

local function configure_fight()
  local equipped, equip_error = equip_staff()
  if not equipped then return nil, equip_error end
  local autocast = gc.await {
    action = { type = "combat.set_autocast", spell = "Fire Strike" },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if autocast.status ~= "set" and autocast.status ~= "unchanged" then
    return nil, { status = "black_demon_autocast_failed", receipt = autocast }
  end
  local safety = gc.await {
    action = {
      type = "safety.configure",
      minimum_hitpoints = 18,
      consumables = {
        { id = config.combat.food, action = "Eat", heal_amount = 12 },
      },
      continue_after_consumable = true,
      allow_overheal = true,
    },
    breaks = false,
  }
  if safety.status ~= "complete" then
    return nil, { status = "black_demon_safety_failed", receipt = safety }
  end
  return true
end

local function position_safespot()
  local failures = {}
  for attempt = 1, 3 do
    local player
    local mapping
    for _ = 1, 20 do
      player = gc.read("player").world
      mapping = gc.read("instance", { template = config.points.black_demon_safespot })
      if player.x < 10000 and mapping.instance == false then break end
      gc.await { event = "game.tick" }
    end
    player = gc.read("player").world
    local safespot = config.points.black_demon_safespot
    mapping = gc.read("instance", { template = safespot })
    local nearest = nil
    local nearest_distance = nil
    for _, candidate in ipairs(mapping.matches or {}) do
      local candidate_distance = distance(player, candidate)
      if nearest_distance == nil or candidate_distance < nearest_distance then
        nearest = candidate
        nearest_distance = candidate_distance
      end
    end
    if nearest then safespot = nearest end

    local walked = { status = "arrived", result = "already_on_safespot" }
    if distance(player, safespot) > 0 then
      walked = gc.await {
        action = {
          type = "walk.to",
          destination = safespot,
          within = 0,
          run = true,
        },
        breaks = false,
        timeout = { game_ticks = 180 },
      }
    end
    local target = npc(config.npcs.black_demon, 24)
    if walked.status == "arrived" and target and target.line_of_sight and target.distance >= 4 then
      return true
    end
    failures[#failures + 1] = {
      attempt = attempt,
      player = gc.read("player"),
      target = target,
      mapping = mapping,
      receipt = walked,
    }
    gc.await { event = "game.tick" }
  end
  return nil, { status = "black_demon_safespot_failed", attempts = failures }
end

local function attack()
  local target = npc(config.npcs.black_demon, 24)
  if not target then return nil, { status = "black_demon_not_observed" } end
  local attacked = gc.await {
    action = { type = "npc.interact", id = target.id, action = "Attack", within = 24 },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if attacked.status ~= "dispatched" then
    return nil, { status = "black_demon_attack_failed", target = target, receipt = attacked }
  end
  return true, attacked
end

local function fight()
  gc.activity("combat")
  local configured, failure = configure_fight()
  if not configured then return failure end
  local descended = descend_watchtower()
  if descended.status ~= "complete" then return descended end

  local demon_observed = false
  for _ = 1, 80 do
    local drained, dialogue_failure = drain_continue_dialogue()
    if not drained then return { status = "black_demon_dialogue_failed", receipt = dialogue_failure } end
    if npc(config.npcs.black_demon, 30) then
      demon_observed = true
      break
    end
    gc.await { event = "game.tick" }
  end
  if not demon_observed then
    if not travel.has_dueling_ring() then
      return { status = "black_demon_respawn_transport_missing" }
    end
    local reset = travel.teleport_to_castle_wars(false)
    if reset.status ~= "complete" then
      return { status = "black_demon_respawn_reset_failed", receipt = reset }
    end
    return { status = "reset", result = "black_demon_encounter_reset", receipt = reset }
  end
  local positioned
  positioned, failure = position_safespot()
  if not positioned then return failure end
  local attacked, attack_receipt = attack()
  if not attacked then return attack_receipt end

  local missing_ticks = 0
  for _ = 1, 900 do
    gc.await { event = "game.tick" }
    local drained, dialogue_failure = drain_continue_dialogue()
    if not drained then return { status = "black_demon_dialogue_failed", receipt = dialogue_failure } end
    local vars = gc.read("vars", { varps = { config.varp } })
    if vars.varps[config.varp] >= 140 then
      return { status = "complete", result = "black_demon_defeated", receipt = attack_receipt }
    end
    local target = npc(config.npcs.black_demon, 24)
    if not target or target.dead then
      missing_ticks = missing_ticks + 1
      if missing_ticks >= 45 then
        return { status = "black_demon_stage_unverified", messages = gc.read("messages", { limit = 30 }) }
      end
    else
      missing_ticks = 0
      if not gc.read("player").interacting then
        local resumed, resume_error = attack()
        if not resumed then return resume_error end
      end
    end
  end
  return { status = "black_demon_combat_timeout", messages = gc.read("messages", { limit = 30 }) }
end

return { prepare = prepare, fight = fight }
