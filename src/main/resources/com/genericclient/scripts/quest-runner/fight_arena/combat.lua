local config = gc.require("fight_arena_config")
local interact = gc.require("fight_arena_interactions")

local encounters = {
  fight_ogre = { label = "ogre", ids = config.npcs.ogre, next_stage = 8 },
  fight_scorpion = {
    label = "scorpion",
    ids = config.npcs.scorpion,
    next_stage = 10,
    allow_close_instance = true,
  },
  fight_bouncer = { label = "bouncer", ids = config.npcs.bouncer, next_stage = 11 },
}

local function drain_continue_dialogue()
  for _ = 1, 6 do
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

local function equip_staff()
  if interact.quantity(gc.read("equipment"), config.items.staff_of_air) > 0 then return true end
  if interact.quantity(gc.read("inventory"), config.items.staff_of_air) == 0 then
    return nil, { status = "fight_arena_staff_missing" }
  end
  local equipped = gc.await {
    action = { type = "item.interact", id = config.items.staff_of_air, action = "Wield" },
    breaks = false,
    timeout = { game_ticks = 20 },
  }
  if equipped.status ~= "dispatched" then
    return nil, { status = "fight_arena_staff_equip_failed", receipt = equipped }
  end
  if not interact.wait_for(function()
    return interact.quantity(gc.read("equipment"), config.items.staff_of_air) > 0
  end, 12) then
    return nil, { status = "fight_arena_staff_equip_unverified", receipt = equipped }
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
    return nil, { status = "fight_arena_autocast_failed", receipt = autocast }
  end
  return true
end

local function attack(encounter)
  local target = interact.npc(encounter.ids, 24)
  if not target then
    return nil, {
      status = "fight_arena_target_missing",
      encounter = encounter.label,
      nearby = gc.read("npcs", { within = 24, limit = 30 }),
    }
  end
  local attacked = gc.await {
    action = { type = "npc.interact", id = target.id, action = "Attack", within = 24 },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if attacked.status ~= "dispatched" then
    return nil, {
      status = "fight_arena_attack_failed",
      encounter = encounter.label,
      target = target,
      receipt = attacked,
    }
  end
  return true, attacked
end

local function position_safespot(encounter)
  local target = interact.npc(encounter.ids, 24)
  if not target then
    return nil, { status = "fight_arena_target_missing", encounter = encounter.label }
  end
  local player = gc.read("player")
  if player.world.x >= 10000 then
    local mapping = gc.read("instance", { template = config.points.arena_safespot })
    local best = nil
    local best_distance = nil
    for _, candidate in ipairs(mapping.matches or {}) do
      local candidate_distance = interact.distance(player.world, candidate)
      if best_distance == nil or candidate_distance < best_distance then
        best = candidate
        best_distance = candidate_distance
      end
    end
    if best then
      local positioned = interact.walk(best, 0, false, 120)
      if positioned.status ~= "arrived" then
        return nil, {
          status = "fight_arena_instance_safespot_failed",
          encounter = encounter.label,
          mapping = mapping,
          receipt = positioned,
        }
      end
      target = interact.npc(encounter.ids, 24)
      if target and target.distance >= 4 and target.line_of_sight then
        return true, best
      end
    end
    if target and target.distance >= 4 and target.line_of_sight then
      return true, player.world
    end
    if target and encounter.allow_close_instance and target.line_of_sight then
      return true, player.world
    end
    return nil, {
      status = "fight_arena_instance_safespot_unresolved",
      encounter = encounter.label,
      mapping = mapping,
      player = player,
      target = target,
    }
  end

  local positioned = interact.walk(config.points.arena_safespot, 0, false, 120)
  if positioned.status ~= "arrived" then
    return nil, {
      status = "fight_arena_safespot_failed",
      encounter = encounter.label,
      receipt = positioned,
    }
  end
  return true, config.points.arena_safespot
end

local function quick_escape()
  local door = gc.read("objects", { action = "Quick-escape", within = 24, limit = 3 })[1]
  if not door then return { status = "rejected", result = "quick_escape_not_observed" } end
  local escaped = gc.await {
    action = {
      type = "object.interact",
      id = door.id,
      action = "Quick-escape",
      world = door.world,
      within = 12,
    },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if escaped.status ~= "dispatched" then return escaped end
  for _ = 1, 20 do
    gc.await { event = "game.tick" }
    if gc.read("player").world.x < 10000 then
      return { status = "complete", result = "arena_quick_escape_verified", receipt = escaped }
    end
  end
  return { status = "timed_out", result = "arena_quick_escape_unverified", receipt = escaped }
end

local function execute(phase)
  local encounter = encounters[phase]
  if not encounter then
    return { status = "rejected", result = "fight_arena_encounter_unknown:" .. tostring(phase) }
  end

  local configured, failure = configure()
  if not configured then return failure end
  local positioned
  positioned, failure = position_safespot(encounter)
  if not positioned then
    if encounter.label == "bouncer" then failure.escape = quick_escape() end
    return failure
  end
  local attacked, attack_receipt = attack(encounter)
  if not attacked then return attack_receipt end

  local missing_ticks = 0
  for _ = 1, 700 do
    gc.await { event = "game.tick" }
    local drained, dialogue_failure = drain_continue_dialogue()
    if not drained then
      return {
        status = "fight_arena_combat_dialogue_failed",
        encounter = encounter.label,
        receipt = dialogue_failure,
      }
    end
    if interact.varp() >= encounter.next_stage then
      return {
        status = "complete",
        result = "fight_arena_encounter_complete",
        encounter = encounter.label,
        receipt = attack_receipt,
      }
    end

    local target = interact.npc(encounter.ids, 24)
    if not target or target.dead then
      missing_ticks = missing_ticks + 1
      if missing_ticks >= 45 then
        return {
          status = "fight_arena_stage_unverified_after_combat",
          encounter = encounter.label,
          varp = interact.varp(),
          messages = gc.read("messages", { limit = 30 }),
        }
      end
    else
      missing_ticks = 0
      local player = gc.read("player")
      if not player.interacting then
        attacked, failure = attack(encounter)
        if not attacked then return failure end
      end
    end
  end
  return {
    status = "fight_arena_combat_timeout",
    encounter = encounter.label,
    varp = interact.varp(),
    messages = gc.read("messages", { limit = 30 }),
  }
end

return { execute = execute }
