local config = gc.require("tree_gnome_config")
local shared = gc.require("shared_state")

local function in_zone(world, zone)
  return world and world.plane == zone.plane and world.x >= zone.x1 and world.x <= zone.x2 and
    world.y >= zone.y1 and world.y <= zone.y2
end

local function carried(state, id)
  return shared.quantity(state.inventory, id) + shared.quantity(state.equipment, id)
end

local function missing(state, loadout)
  return #shared.missing_carried_items(state, loadout) > 0
end

local function set_missing(state, loadout)
  state.missing = shared.missing_items(state, loadout)
end

local function orbs_on_ground()
  return #gc.read("ground_items", {
    id = config.items.remaining_orbs,
    within = 20,
    limit = 3,
  }) > 0
end

local function resolve(state)
  if state.quests.tree_gnome_village.state == "finished" then return "complete" end
  if state.skills.magic.level < 29 or not state.player or state.player.max_hitpoints < 20 then
    return "strict_stats_block"
  end

  local world = state.player.world
  if state.varp == 0 then
    if missing(state, config.initial_loadout) then
      set_missing(state, config.initial_loadout)
      return "loadout_required"
    end
    return "accept_quest"
  end
  if state.varp == 1 then return "talk_montai" end
  if state.varp == 2 then
    if carried(state, config.items.logs) < 6 then
      set_missing(state, config.initial_loadout)
      return "loadout_required"
    end
    return "give_logs"
  end
  if state.varp == 3 then return "talk_montai_again" end
  if state.varp == 4 then
    if state.varbits[config.varbits.tracker_height] == 0 then return "tracker_one" end
    if state.varbits[config.varbits.tracker_y] == 0 then return "tracker_two" end
    if state.varbits[config.varbits.tracker_x] == 0 then return "tracker_three" end
    return "fire_ballista"
  end
  if state.varp == 5 then
    if carried(state, config.items.first_orb) > 0 then return "return_first_orb" end
    if in_zone(world, config.zones.tower_upstairs) then return "search_orb_chest" end
    return "enter_orb_tower"
  end
  if state.varp == 6 then return "return_first_orb" end
  if state.varp == 7 then
    if carried(state, config.items.remaining_orbs) > 0 then return "return_orbs" end
    if orbs_on_ground() then return "take_orbs" end
    if missing(state, config.combat_minimum) then
      set_missing(state, config.combat_loadout)
      return "combat_loadout_required"
    end
    return "fight_warlord"
  end
  if state.varp == 8 then
    if carried(state, config.items.remaining_orbs) > 0 then return "return_orbs" end
    if orbs_on_ground() then return "take_orbs" end
    if in_zone(world, config.zones.village) and
      state.varbits[config.varbits.bolren_got_orbs] >= 1 then
      return "finish_dialogue"
    end
    return "fight_warlord"
  end
  return "unknown_stage"
end

return { resolve = resolve, in_zone = in_zone }
