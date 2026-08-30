local config = gc.require("fight_arena_config")
local shared = gc.require("shared_state")

local function carried(state, id)
  return shared.quantity(state.inventory, id) + shared.quantity(state.equipment, id)
end

local function missing(state, loadout)
  return #shared.missing_carried_items(state, loadout) > 0
end

local function in_zone(world, zone)
  return world and world.plane == zone.plane and world.x >= zone.x1 and world.x <= zone.x2 and
    world.y >= zone.y1 and world.y <= zone.y2
end

local function npc_present(ids)
  for _, id in ipairs(ids) do
    if #gc.read("npcs", { id = id, within = 24, limit = 3 }) > 0 then return true end
  end
  return false
end

local function owns_armour(state)
  return carried(state, config.items.khazard_helmet) > 0 and
    carried(state, config.items.khazard_armour) > 0
end

local function wears_armour(state)
  return shared.quantity(state.equipment, config.items.khazard_helmet) > 0 and
    shared.quantity(state.equipment, config.items.khazard_armour) > 0
end

local function armour_phase(state, next_phase)
  if not owns_armour(state) then return "obtain_khazard_armour" end
  if not wears_armour(state) then return "equip_khazard_armour" end
  return next_phase
end

local function needs_combat_restock(state)
  if state.player.world.x >= 10000 or not missing(state, config.combat_minimum) then
    return false
  end
  state.missing = shared.missing_items(state, config.loadout)
  return true
end

local function resolve(state)
  if state.quests.fight_arena.state == "finished" then return "complete" end
  if state.skills.magic.level < 29 or not state.player or state.player.max_hitpoints < 20 then
    return "strict_stats_block"
  end

  local stage = state.varp
  if stage == 0 then
    if missing(state, config.loadout) then
      state.missing = shared.missing_items(state, config.loadout)
      return "loadout_required"
    end
    return "accept_quest"
  end
  if stage == 1 then return "obtain_khazard_armour" end
  if stage == 2 then return armour_phase(state, "talk_head_guard") end
  if stage == 3 then
    local next_phase = carried(state, config.items.khali_brew) > 0 and
      "give_khali_brew" or "buy_khali_brew"
    return armour_phase(state, next_phase)
  end
  if stage == 4 or stage == 5 then
    local next_phase = carried(state, config.items.cell_keys) > 0 and
      "free_sammy" or "get_cell_keys"
    return armour_phase(state, next_phase)
  end
  if stage == 6 then
    if needs_combat_restock(state) then return "combat_loadout_required" end
    if npc_present(config.npcs.ogre) then return "fight_ogre" end
    return "talk_sammy_for_ogre"
  end
  if stage == 7 or stage == 8 then return "talk_general_khazard" end
  if stage == 9 then
    if needs_combat_restock(state) then return "combat_loadout_required" end
    if in_zone(state.player.world, config.zones.cell) then return "talk_hengrad" end
    if npc_present(config.npcs.scorpion) then return "fight_scorpion" end
    return "talk_sammy_for_scorpion"
  end
  if stage == 10 then
    if needs_combat_restock(state) then return "combat_loadout_required" end
    if npc_present(config.npcs.bouncer) then return "fight_bouncer" end
    return "talk_sammy_for_bouncer"
  end
  if stage >= 11 and stage <= 14 then
    if state.player.world.x >= 10000 or in_zone(state.player.world, config.zones.arena) then
      return "leave_arena"
    end
    return "finish_quest"
  end
  return "unknown_stage"
end

return { resolve = resolve, in_zone = in_zone }
