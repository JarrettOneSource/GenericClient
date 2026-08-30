local interactions = gc.require("grand_tree_interactions")
local navigation = gc.require("grand_tree_navigation")

local function execute(phase)
  if phase == "start_quest" then
    local reached, failure = navigation.reach_narnode()
    if not reached then return failure end
    return interactions.talk_narnode()
  end
  if phase == "talk_hazelmere" then
    local reached, failure = navigation.reach_hazelmere()
    if not reached then return failure end
    return interactions.talk_hazelmere()
  end
  if phase == "return_translation" then
    local reached, failure = navigation.return_to_narnode()
    if not reached then return failure end
    return interactions.talk_narnode_translation()
  end
  if phase == "talk_glough" then
    local reached, failure = navigation.reach_glough()
    if not reached then return failure end
    return interactions.talk_glough()
  end
  if phase == "return_after_glough" then
    local reached, failure = navigation.return_after_glough()
    if not reached then return failure end
    return interactions.talk_narnode_after_glough()
  end
  if phase == "talk_charlie" then
    local reached, failure = navigation.reach_charlie()
    if not reached then return failure end
    return interactions.talk_charlie()
  end
  if phase == "search_glough_journal" then
    local reached, failure = navigation.return_to_glough_for_journal()
    if not reached then return failure end
    return interactions.search_glough_cupboard()
  end
  if phase == "confront_glough" then
    local reached, failure = navigation.reach_glough()
    if not reached then return failure end
    return interactions.talk_glough_again()
  end
  if phase == "talk_charlie_cell" then
    return interactions.talk_charlie_from_cell()
  end
  return { status = "rejected", result = "grand_tree_phase_not_implemented:" .. tostring(phase) }
end

return { execute = execute }
