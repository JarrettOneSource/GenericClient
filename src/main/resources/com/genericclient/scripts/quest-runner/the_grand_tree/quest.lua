local interactions = gc.require("grand_tree_interactions")
local navigation = gc.require("grand_tree_navigation")
local combat = gc.require("grand_tree_combat")
local completion = gc.require("grand_tree_completion")

local function execute(phase, input)
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
  if phase == "escape_by_glider" then
    return interactions.take_glider_to_karamja()
  end
  if phase == "enter_shipyard" then
    return interactions.enter_shipyard()
  end
  if phase == "talk_foreman" then
    local reached, failure = navigation.reach_shipyard_foreman()
    if not reached then return failure end
    return interactions.talk_shipyard_foreman()
  end
  if phase == "return_lumber_order" then
    local reached, failure = navigation.return_lumber_order_to_charlie()
    if not reached then return failure end
    return interactions.return_lumber_order_to_charlie()
  end
  if phase == "talk_anita" then
    local reached, failure = navigation.reach_anita()
    if not reached then return failure end
    return interactions.talk_anita()
  end
  if phase == "find_invasion_plans" then
    local reached, failure = navigation.reach_glough_for_invasion_plans()
    if not reached then return failure end
    return interactions.obtain_invasion_plans()
  end
  if phase == "return_invasion_plans" then
    local reached, failure = navigation.return_invasion_plans_to_narnode()
    if not reached then return failure end
    return interactions.return_invasion_plans_to_narnode()
  end
  if phase == "place_tuzo_twigs" then
    local reached, failure = navigation.reach_watchtower()
    if not reached then return failure end
    return interactions.place_tuzo_twigs()
  end
  if phase == "prepare_black_demon" then
    return combat.prepare(input and input.restock)
  end
  if phase == "fight_black_demon" then
    for _ = 1, 2 do
      local reached, failure = navigation.reach_watchtower()
      if not reached then return failure end
      local result = combat.fight()
      if result.status ~= "reset" then return result end
    end
    return { status = "black_demon_encounter_reset_exhausted" }
  end
  if phase == "talk_king_after_demon" then
    return completion.talk_to_king_after_demon()
  end
  if phase == "find_daconia_rock" then
    return completion.find_daconia_rock()
  end
  if phase == "return_daconia_rock" then
    return completion.return_daconia_rock()
  end
  return { status = "rejected", result = "grand_tree_phase_not_implemented:" .. tostring(phase) }
end

return { execute = execute }
