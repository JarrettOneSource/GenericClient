local config = gc.require("monkey_madness_config")
local shared = gc.require("shared_state")

local function in_zone(world, zone)
  return world and world.plane == zone.plane and
    world.x >= zone.x1 and world.x <= zone.x2 and
    world.y >= zone.y1 and world.y <= zone.y2
end

local function resolve(state)
  if state.quests.monkey_madness_i and state.quests.monkey_madness_i.state == "finished" then
    return "complete"
  end
  if state.varp == 0 then return "start_quest" end
  if state.varp <= 2 then
    if state.varbits[config.varbits.caranock] < 3 then return "investigate_shipyard" end
    if state.varbits[config.varbits.narnode] < 7 then return "report_shipyard" end
    if state.varbits[config.varbits.daero] < 1 then return "meet_daero" end
    if state.varbits[config.varbits.daero] < 5 then return "enter_hangar" end
    if state.varbits[config.varbits.daero] < 6 then return "solve_reinitialization" end
    if state.varbits[config.varbits.daero] < 7 then return "confirm_reinitialization" end
    if not shared.matches_carried_loadout(state, config.ape_atoll_loadout) then
      return "ape_atoll_loadout_required"
    end
    if not in_zone(state.player and state.player.world, config.zones.ape_atoll_south) then
      return "reach_ape_atoll"
    end
    return "find_garkor"
  end
  if state.varp == 3 then return "infiltrate_ape_atoll" end
  if state.varp == 4 then return "bring_monkey_to_awowogei" end
  if state.varp == 5 then return "defeat_jungle_demon" end
  if state.varp >= 6 then return "return_to_narnode" end
  return "unknown_stage"
end

return { resolve = resolve }
