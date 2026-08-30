local config = gc.require("grand_tree_config")

local function carried(state, id)
  for _, item in ipairs((state.inventory and state.inventory.items) or {}) do
    if item.id == id then return item.quantity end
  end
  return 0
end

local function in_glough_house(world)
  return world and world.plane == 1 and world.x >= 2474 and world.x <= 2488 and
    world.y >= 3461 and world.y <= 3466
end

local function in_cell(world)
  return world and world.plane == 3 and world.x == 2464 and world.y == 3496
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
  if state.varp > 70 then return "narnode_escape_checkpoint" end
  return "unknown_stage"
end

return { resolve = resolve }
