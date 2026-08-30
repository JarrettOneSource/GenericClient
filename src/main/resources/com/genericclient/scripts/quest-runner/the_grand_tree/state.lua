local config = gc.require("grand_tree_config")

local function carried(state, id)
  local total = 0
  for _, item in ipairs((state.inventory and state.inventory.items) or {}) do
    if item.id == id then total = total + item.quantity end
  end
  return total
end

local function owned(state, id)
  local total = carried(state, id)
  for _, item in ipairs((state.equipment and state.equipment.items) or {}) do
    if item.id == id then total = total + item.quantity end
  end
  return total
end

local function black_demon_loadout(state)
  local world = state.player and state.player.world
  local transport_complete = world and (
    (world.plane == 0 and world.x >= 2380 and world.x <= 2520 and
      world.y >= 3350 and world.y <= 3530) or
    (world.plane >= 1 and world.x >= 2470 and world.x <= 2495 and
      world.y >= 3455 and world.y <= 3475) or
    world.y >= 9800 or world.x >= 10000)
  for _, requirement in ipairs(config.loadout) do
    if not requirement.transport or not transport_complete then
      local quantity = owned(state, requirement.id)
      for _, id in ipairs(requirement.alternative_ids or {}) do
        quantity = quantity + owned(state, id)
      end
      if quantity < requirement.quantity then return false end
    end
  end
  return true
end

local function in_glough_house(world)
  return world and world.plane == 1 and world.x >= 2474 and world.x <= 2488 and
    world.y >= 3461 and world.y <= 3466
end

local function in_cell(world)
  return world and world.plane == 3 and world.x == 2464 and world.y == 3496
end

local function in_shipyard(world)
  return world and world.plane == 0 and world.x >= 2945 and world.x <= 3007 and
    world.y >= 3015 and world.y <= 3070
end

local function resolve(state)
  if state.quests.the_grand_tree and state.quests.the_grand_tree.state == "finished" then
    return "complete"
  end
  if not state.skills.agility or state.skills.agility.level < 25 then
    return "strict_stats_block"
  end
  if state.varp == 0 then
    local occupied = state.inventory and state.inventory.occupied_slots or 28
    if occupied > 26 then return "inventory_space_block" end
    return "start_quest"
  end
  if state.varp == 10 then return "talk_hazelmere" end
  if state.varp == 20 then return "return_translation" end
  if state.varp == 30 then return "talk_glough" end
  if state.varp == 40 then return "return_after_glough" end
  if state.varp == 50 then return "talk_charlie" end
  if state.varp == 60 and carried(state, config.items.glough_journal) == 0 then
    return "search_glough_journal"
  end
  if state.varp == 60 then return "confront_glough" end
  if state.varp == 70 then
    if in_glough_house(state.player.world) then return "confront_glough" end
    if in_cell(state.player.world) then return "talk_charlie_cell" end
    if state.player.world.plane == 3 then return "narnode_escape_checkpoint" end
    return "unknown_stage"
  end
  if state.varp == 80 then
    if state.player.world.plane == 3 then return "escape_by_glider" end
    if state.player.world.plane == 0 and state.player.world.x >= 2900 and
      state.player.world.x <= 3010 and state.player.world.y >= 2950 and
      state.player.world.y <= 3070 then
      if in_shipyard(state.player.world) then return "talk_foreman" end
      return "enter_shipyard"
    end
    return "unknown_stage"
  end
  if state.varp == 90 then return "return_lumber_order" end
  if state.varp == 100 and carried(state, config.items.glough_key) == 0 then
    return "talk_anita"
  end
  if state.varp == 100 and carried(state, config.items.invasion_plans) == 0 then
    return "find_invasion_plans"
  end
  if state.varp == 100 then return "invasion_plans_checkpoint" end
  if state.varp == 110 then return "return_invasion_plans" end
  if state.varp == 120 then return "place_tuzo_twigs" end
  if state.varp == 130 and not black_demon_loadout(state) then return "prepare_black_demon" end
  if state.varp == 130 then return "fight_black_demon" end
  if state.varp == 140 then return "talk_king_after_demon" end
  if state.varp == 150 and carried(state, config.items.daconia_rock) == 0 then
    return "find_daconia_rock"
  end
  if state.varp >= 150 then return "return_daconia_rock" end
  return "unknown_stage"
end

return { resolve = resolve }
