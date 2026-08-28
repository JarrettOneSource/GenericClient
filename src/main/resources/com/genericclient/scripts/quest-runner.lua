-- genericclient-interface: 2

local shared_state = gc.require("shared_state")
local preparation = gc.require("shared_preparation")
local witch_config = gc.require("witch_config")
local witch_state = gc.require("witch_state")
local witch_quest = gc.require("witch_quest")
local witch_garden = gc.require("witch_garden")
local witch_combat = gc.require("witch_combat")
local witch_completion = gc.require("witch_completion")
local waterfall_config = gc.require("waterfall_config")
local waterfall_state = gc.require("waterfall_state")
local waterfall_quest = gc.require("waterfall_quest")

local quest_labels = {
  witchs_house = witch_config.label,
  waterfall = waterfall_config.label,
}
local state_modules = {
  witchs_house = witch_state,
  waterfall = waterfall_state,
}
local read_state = shared_state.read
local function resolve(state) return state_modules[state.quest].resolve(state) end

local function overlay(quest, phase)
  gc.overlay {
    { label = "Quest", value = quest_labels[quest] },
    { label = "Phase", value = phase:gsub("_", " ") },
    { label = "State", value = "Observed" },
  }
end

local function wait_for_phase_change(quest, previous, ticks)
  for _ = 1, ticks do
    gc.await { event = "game.tick" }
    local state = read_state(quest)
    local phase = resolve(state)
    if phase ~= previous then
      return state, phase
    end
  end
  return nil, previous
end

return {
  inputs = {
    {
      id = "quest",
      label = "Quest",
      type = "choice",
      default = "witchs_house",
      choices = {
        { value = "witchs_house", label = "Witch's House" },
        { value = "waterfall", label = "Waterfall Quest" },
      },
    },
    {
      id = "restock",
      label = "Restock",
      type = "choice",
      default = "ge",
      choices = {
        { value = "ge", label = "Grand Exchange" },
        { value = "bank_only", label = "Bank only" },
      },
    },
    {
      id = "combat",
      label = "Combat",
      type = "choice",
      default = "checkpoint",
      choices = {
        { value = "checkpoint", label = "Stop at shed" },
        { value = "continue", label = "Continue" },
      },
    },
  },

  actions = {
    { id = "stop_safely", label = "Stop safely" },
  },

  run = function(input)
    assert(quest_labels[input.quest], "Unknown quest")
    gc.await { event = "game.tick" }

    local initial = read_state(input.quest)
    local initial_phase = resolve(initial)
    if initial_phase ~= "complete" and initial_phase ~= "strict_stats_block" and
      initial_phase ~= "strict_hitpoints_block" then
      local retaliate = gc.await {
        action = { type = "combat.set_auto_retaliate", enabled = false },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
      if retaliate.status ~= "set" and retaliate.status ~= "unchanged" then
        return { status = "auto_retaliate_failed", receipt = retaliate }
      end

      local threshold = input.quest == "witchs_house" and 6 or 12
      local safety = gc.await {
        action = {
          type = "safety.configure",
          minimum_hitpoints = threshold,
          consumables = {
            { id = 1993, action = "Drink", heal_amount = 11 },
          },
        },
        breaks = false,
      }
      if safety.status ~= "complete" then
        return { status = "safety_guard_failed", receipt = safety }
      end

      if initial_phase == "experiment_loadout_required" then
        local prepared, prepare_error = witch_combat.prepare(input.restock)
        if not prepared then
          gc.log("error", "quest-combat-preparation-failed", prepare_error)
          return prepare_error
        end
      elseif input.quest == "waterfall" or
        (input.quest == "witchs_house" and initial.varp <= 2) then
        local prepared, prepare_error = preparation.prepare(input.quest, input.restock)
        if not prepared then
          gc.log("error", "quest-preparation-failed", prepare_error)
          return prepare_error
        end
      end
    end

    while true do
      local state = read_state(input.quest)
      local phase = resolve(state)
      overlay(input.quest, phase)
      gc.log("info", "quest-phase", { quest = input.quest, phase = phase, varp = state.varp })

      if phase == "complete" then
        gc.await { action = { type = "safety.clear" }, breaks = false }
        gc.await { action = { type = "mouse.offscreen" }, breaks = false }
        return { status = "complete", quest = input.quest, varp = state.varp }
      end
      if phase == "shed_ready_checkpoint" and input.combat == "continue" then
        gc.phase("quest.witchs_house.experiment")
        overlay(input.quest, "experiment")
        local result = witch_combat.execute()
        if result.status == "experiment_complete" then
          gc.phase("quest.witchs_house.return_ball")
          overlay(input.quest, "return_ball")
          local completed = witch_completion.execute()
          completed.combat = result
          completed.quest = input.quest
          completed.varp = gc.read("vars", { varps = { 226 } }).varps[226]
          return completed
        end
        result.quest = input.quest
        result.varp = gc.read("vars", { varps = { 226 } }).varps[226]
        return result
      end
      if phase == "experiment_complete_checkpoint" and input.combat == "continue" then
        gc.phase("quest.witchs_house.return_ball")
        overlay(input.quest, "return_ball")
        local completed = witch_completion.execute()
        completed.quest = input.quest
        completed.varp = gc.read("vars", { varps = { 226 } }).varps[226]
        return completed
      end
      if phase == "strict_stats_block" or phase == "strict_hitpoints_block" or
        phase == "bank_unknown" or phase == "loadout_required" or
        phase == "experiment_loadout_required" or phase == "shed_ready_checkpoint" or
        phase == "experiment_complete_checkpoint" or
        phase == "waterfall_ready_checkpoint" or phase == "unknown_stage" then
        return {
          status = phase,
          quest = input.quest,
          varp = state.varp,
          missing = state.missing,
          magic = state.skills.magic.level,
          current_hitpoints = state.player and state.player.current_hitpoints,
          maximum_hitpoints = state.player and state.player.max_hitpoints,
        }
      end
      if gc.next_action() == "stop_safely" then
        return { status = "stopped", quest = input.quest, phase = phase }
      end

      local receipt = phase == "garden_fountain" and witch_garden.execute() or
        input.quest == "witchs_house" and witch_quest.execute(phase) or
        waterfall_quest.execute(phase)
      if not receipt or (receipt.status ~= "dispatched" and receipt.status ~= "complete") then
        gc.log("error", "quest-action-failed", { phase = phase, receipt = receipt })
        return { status = "action_failed", quest = input.quest, phase = phase, receipt = receipt }
      end
      local next_state, next_phase = wait_for_phase_change(input.quest, phase, 30)
      if not next_state then
        return { status = "phase_timeout", quest = input.quest, phase = next_phase, receipt = receipt }
      end
      gc.phase("quest." .. input.quest .. "." .. next_phase)
    end
  end,
}
