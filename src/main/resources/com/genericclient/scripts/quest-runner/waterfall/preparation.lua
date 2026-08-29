local config = gc.require("waterfall_config")
local shared = gc.require("shared_preparation")
local state_api = gc.require("shared_state")
local travel = gc.require("shared_travel")

local loadouts = {
  initial_loadout_required = config.initial_loadout,
  gnome_loadout_required = config.gnome_loadout,
  key_loadout_required = config.gnome_key_loadout,
  tomb_loadout_required = config.tomb_loadout,
  final_loadout_required = config.final_loadout,
}

local function execute(phase, restock)
  if phase == "bank_unknown" then
    local state = state_api.read("waterfall")
    local world = state.player.world
    local final_items_owned = state_api.total_owned(state, config.items.amulet) > 0 and
      state_api.total_owned(state, config.items.urn) > 0
    if final_items_owned and world.x < 2800 and world.y > 3300 and travel.has_necklace() then
      local teleported = travel.teleport_to_burthorpe()
      if teleported.status ~= "complete" then return teleported end
    end
    local refreshed, failure = shared.refresh_bank("waterfall")
    return refreshed and { status = "complete", result = "bank_cache_refreshed" } or failure
  end
  local loadout = loadouts[phase]
  if phase == "tomb_loadout_required" then
    local state = state_api.read("waterfall")
    if state_api.total_owned(state, config.items.amulet) > 0 then
      loadout = config.tomb_urn_loadout
    end
  end
  if not loadout then
    return { status = "rejected", result = "not_a_preparation_phase:" .. tostring(phase) }
  end
  if phase == "final_loadout_required" then
    local state = state_api.read("waterfall")
    if state_api.total_owned(state, config.items.baxtorian_key) > 0 then
      loadout = config.final_key_loadout
    end
    local world = gc.read("player").world
    if world.x < 2800 and world.y > 3300 and travel.has_necklace() then
      local teleported = travel.teleport_to_burthorpe()
      if teleported.status ~= "complete" then return teleported end
    end
  end
  local prepared, failure = shared.prepare_items("waterfall", restock, loadout, true)
  return prepared and { status = "complete", result = phase .. "_complete" } or failure
end

local function handles(phase)
  return phase == "bank_unknown" or loadouts[phase] ~= nil
end

return { execute = execute, handles = handles }
