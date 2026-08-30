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
  return { status = "rejected", result = "grand_tree_phase_not_implemented:" .. tostring(phase) }
end

return { execute = execute }
