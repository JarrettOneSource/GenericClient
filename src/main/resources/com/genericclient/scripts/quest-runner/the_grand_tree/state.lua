local config = gc.require("grand_tree_config")

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
  if state.varp >= 40 then return "narnode_after_glough_checkpoint" end
  return "unknown_stage"
end

return { resolve = resolve }
