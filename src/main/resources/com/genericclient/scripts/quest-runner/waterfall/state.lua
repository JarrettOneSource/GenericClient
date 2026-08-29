local config = gc.require("waterfall_config")
local shared = gc.require("shared_state")

local function in_zone(world, zone)
  return world and world.plane == zone.plane and world.x >= zone.x1 and world.x <= zone.x2 and
    world.y >= zone.y1 and world.y <= zone.y2
end

local function carried(state, id)
  return shared.quantity(state.inventory, id) + shared.quantity(state.equipment, id)
end

local function owned(state, id)
  return shared.total_owned(state, id) > 0
end

local function needs_loadout(state, loadout)
  return not shared.matches_carried_loadout(state, loadout)
end

local function needs_minimum_loadout(state, loadout)
  return #shared.missing_carried_items(state, loadout) > 0
end

local function missing_loadout(state, loadout)
  state.missing = shared.missing_items(state, loadout)
end

local function required_tomb_loadout(state)
  return owned(state, config.items.amulet) and config.tomb_urn_loadout or config.tomb_loadout
end

local function resolve_investigation(state, world)
  if state.varp == 1 then
    return in_zone(world, config.zones.hudon_island) and "talk_hudon" or "reach_hudon"
  end
  if carried(state, config.items.book) > 0 then return "read_book" end
  if in_zone(world, config.zones.tourist_upstairs) then return "obtain_book" end
  if in_zone(world, config.zones.ledge) then return "leave_ledge" end
  if in_zone(world, config.zones.dead_tree_island) then return "descend_tree" end
  if in_zone(world, config.zones.hudon_island) then return "cross_to_tree" end
  return "reach_tourist_stairs"
end

local function resolve_final(state, world)
  if in_zone(world, config.zones.chalice_room) then return "finish_quest" end
  if in_zone(world, config.zones.pillar_room) then
    return carried(state, config.items.amulet) == 0 and "amulet_missing_in_pillar_room" or
      shared.quantity(state.equipment, config.items.amulet) > 0 and "remove_amulet" or
      "charge_pillars"
  end
  if in_zone(world, config.zones.falls) then
    if carried(state, config.items.baxtorian_key) > 0 then return "open_inner_door" end
    local crate = gc.read("objects", {
      id = config.objects.falls_crate,
      within = 40,
      limit = 1,
    })
    return #crate > 0 and "obtain_baxtorian_key" or "open_inner_door"
  end
  if in_zone(world, config.zones.ledge) then
    return shared.quantity(state.equipment, config.items.amulet) > 0 and
      "enter_falls" or "equip_amulet"
  end
  if in_zone(world, config.zones.dead_tree_island) then return "descend_tree_final" end
  if in_zone(world, config.zones.hudon_island) then return "cross_to_tree_final" end
  if not state.bank.available then return "bank_unknown" end
  local required_loadout = owned(state, config.items.baxtorian_key) and
    config.final_key_loadout or config.final_loadout
  if needs_loadout(state, required_loadout) then
    missing_loadout(state, required_loadout)
    return "final_loadout_required"
  end
  return "reach_falls"
end

local function resolve_items(state, world)
  if in_zone(world, config.zones.chalice_room) then return "finish_quest" end
  local has_amulet = owned(state, config.items.amulet)
  local has_urn = owned(state, config.items.urn)
  if has_amulet and has_urn then
    if in_zone(world, config.zones.gnome_basement) then return "leave_gnome_dungeon" end
    if in_zone(world, config.zones.glarial_tomb) then return "leave_glarial_tomb" end
    return resolve_final(state, world)
  end
  if in_zone(world, config.zones.glarial_tomb) then
    return carried(state, config.items.amulet) == 0 and "obtain_amulet" or "obtain_urn"
  end
  if carried(state, config.items.pebble) > 0 then
    local loadout = required_tomb_loadout(state)
    if in_zone(world, config.zones.gnome_basement) then return "leave_gnome_dungeon" end
    if shared.distance(world, config.points.tombstone) <= 12 then
      return "enter_glarial_tomb"
    end
    if not state.bank.available and needs_loadout(state, loadout) then
      return "bank_unknown"
    end
    if needs_loadout(state, loadout) then
      missing_loadout(state, loadout)
      return "tomb_loadout_required"
    end
    return "enter_glarial_tomb"
  end
  if in_zone(world, config.zones.golrie_room) then return "obtain_pebble" end
  if in_zone(world, config.zones.gnome_basement) then
    return carried(state, config.items.golrie_key) > 0 and
      "open_golrie_gate" or "obtain_golrie_key"
  end
  if in_zone(world, config.zones.tourist_upstairs) then return "leave_tourist_house" end
  if state.varbits[config.varbits.golrie_chat] == 1 then
    if not state.bank.available then return "bank_unknown" end
    if owned(state, config.items.pebble) then
      missing_loadout(state, required_tomb_loadout(state))
      return "tomb_loadout_required"
    end
  end
  local gnome_loadout = owned(state, config.items.golrie_key) and
    config.gnome_key_loadout or config.gnome_loadout
  if not state.bank.available and needs_minimum_loadout(state, gnome_loadout) then
    return "bank_unknown"
  end
  if needs_minimum_loadout(state, gnome_loadout) then
    missing_loadout(state, gnome_loadout)
    return owned(state, config.items.golrie_key) and
      "key_loadout_required" or "gnome_loadout_required"
  end
  return "reach_gnome_dungeon"
end

local function resolve(state)
  if state.quests.waterfall_quest.state == "finished" then return "complete" end
  local player = state.player
  if not player or player.max_hitpoints < 15 then return "strict_hitpoints_block" end
  local world = player.world
  if state.varp == 0 then
    if not state.bank.available and needs_loadout(state, config.initial_loadout) then
      return "bank_unknown"
    end
    if needs_loadout(state, config.initial_loadout) then
      missing_loadout(state, config.initial_loadout)
      return "initial_loadout_required"
    end
    return "accept"
  end
  if state.varp == 1 or state.varp == 2 then return resolve_investigation(state, world) end
  if state.varp >= 3 and state.varp <= 8 then return resolve_items(state, world) end
  return "unknown_stage"
end

return { resolve = resolve }
