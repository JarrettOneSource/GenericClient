local navigation = gc.require("monkey_madness_navigation")
local interactions = gc.require("monkey_madness_interactions")
local puzzle = gc.require("monkey_madness_puzzle")
local preparation = gc.require("monkey_madness_preparation")
local ape_atoll = gc.require("monkey_madness_ape_atoll")

local function execute(phase, input)
  if phase == "start_quest" then
    local target, failure = navigation.reach_narnode()
    if not target then return failure end
    return interactions.start_quest(target)
  end
  if phase == "investigate_shipyard" then
    local target = navigation.npc({ 1460 }, 20)
    if not target then
      local reached = navigation.reach_shipyard_gate()
      if reached.status ~= "complete" then return reached end
      local entered = interactions.enter_shipyard()
      if entered.status ~= "complete" then return entered end
      local failure
      target, failure = navigation.reach_caranock()
      if not target then return failure end
    end
    return interactions.talk_caranock(target)
  end
  if phase == "report_shipyard" then
    local target, failure = navigation.reach_narnode()
    if not target then return failure end
    return interactions.report_to_narnode(target)
  end
  if phase == "meet_daero" then
    local target, failure = navigation.reach_daero()
    if not target then return failure end
    return interactions.talk_daero(target)
  end
  if phase == "enter_hangar" then
    return interactions.enter_hangar()
  end
  if phase == "solve_reinitialization" then
    return puzzle.solve()
  end
  if phase == "confirm_reinitialization" then
    local target, failure = navigation.reach_post_puzzle_daero()
    if not target then return failure end
    return interactions.confirm_reinitialization(target)
  end
  if phase == "ape_atoll_loadout_required" then
    local prepared, failure = preparation.prepare(input and input.restock or "bank_only")
    if not prepared then return failure end
    return { status = "complete", result = "ape_atoll_loadout_prepared" }
  end
  if phase == "reach_ape_atoll" then
    return ape_atoll.execute()
  end
  return { status = "rejected", result = "monkey_madness_phase_not_implemented:" .. tostring(phase) }
end

return { execute = execute }
