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
local tree_gnome_config = gc.require("tree_gnome_config")
local tree_gnome_state = gc.require("tree_gnome_state")
local tree_gnome_runner = gc.require("tree_gnome_runner")

local quest_labels = {
  witchs_house = witch_config.label,
  waterfall = waterfall_config.label,
  tree_gnome_village = tree_gnome_config.label,
}
local state_modules = {
  witchs_house = witch_state,
  waterfall = waterfall_state,
  tree_gnome_village = tree_gnome_state,
}
local waterfall_checkpoints = {
  accept = 1,
  reach_gnome_dungeon = 2,
  enter_glarial_tomb = 3,
  final_loadout_required = 4,
  reach_falls = 5,
  finish_quest = 6,
}
local waterfall_tomb_phases = {
  enter_glarial_tomb = true,
  obtain_amulet = true,
  obtain_urn = true,
  leave_glarial_tomb = true,
}
local waterfall_final_phases = {
  final_loadout_required = true,
  reach_falls = true,
  cross_to_tree_final = true,
  descend_tree_final = true,
  equip_amulet = true,
  enter_falls = true,
  obtain_baxtorian_key = true,
  open_inner_door = true,
  remove_amulet = true,
  charge_pillars = true,
  finish_quest = true,
  amulet_missing_in_pillar_room = true,
}
local waterfall_break_bypass_phases = {
  obtain_golrie_key = true,
  open_golrie_gate = true,
  obtain_pebble = true,
  leave_gnome_dungeon = true,
  enter_glarial_tomb = true,
  obtain_amulet = true,
  obtain_urn = true,
  leave_glarial_tomb = true,
  enter_falls = true,
  obtain_baxtorian_key = true,
  open_inner_door = true,
  remove_amulet = true,
  charge_pillars = true,
  finish_quest = true,
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

local function waterfall_checkpoint_rank(state, phase)
  if phase == "complete" then return 7 end
  if waterfall_checkpoints[phase] then return waterfall_checkpoints[phase] end
  if state.varp == 0 then return 0 end
  if state.varp <= 2 then return 1 end
  if waterfall_final_phases[phase] or
    (shared_state.total_owned(state, 295) > 0 and shared_state.total_owned(state, 296) > 0) then
    return 4
  end
  if waterfall_tomb_phases[phase] then return 3 end
  return 2
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
        { value = "tree_gnome_village", label = "Tree Gnome Village" },
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
      id = "scope",
      label = "Scope",
      type = "choice",
      default = "checkpoint",
      choices = {
        { value = "checkpoint", label = "Next checkpoint" },
        { value = "complete", label = "Quest completion" },
      },
    },
  },

  actions = {
    { id = "stop_safely", label = "Stop safely" },
  },

  run = function(input)
    assert(quest_labels[input.quest], "Unknown quest")
    gc.activity("questing")
    if input.quest == "tree_gnome_village" then
      return tree_gnome_runner.run(input)
    end
    gc.await { event = "game.tick" }

    local initial = read_state(input.quest)
    local initial_phase = resolve(initial)
    local initial_waterfall_checkpoint = input.quest == "waterfall" and
      waterfall_checkpoint_rank(initial, initial_phase) or nil
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

      local maximum_hitpoints = initial.player and initial.player.max_hitpoints or 10
      local threshold = math.max(4, math.floor(maximum_hitpoints * 0.25))
      local safety = gc.await {
        action = {
          type = "safety.configure",
          minimum_hitpoints = threshold,
          consumables = {
            { id = 1993, action = "Drink", heal_amount = 11 },
          },
          continue_after_consumable = true,
          allow_overheal = input.quest == "waterfall",
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
      elseif input.quest == "witchs_house" and initial.varp <= 2 then
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
      if input.quest == "waterfall" and input.scope == "checkpoint" and
        waterfall_checkpoints[phase] and
        waterfall_checkpoints[phase] > initial_waterfall_checkpoint then
        gc.await { action = { type = "mouse.offscreen" }, breaks = false }
        return { status = phase .. "_checkpoint", quest = input.quest, varp = state.varp }
      end
      if phase == "shed_ready_checkpoint" and input.scope == "complete" then
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
      if phase == "experiment_complete_checkpoint" and input.scope == "complete" then
        gc.phase("quest.witchs_house.return_ball")
        overlay(input.quest, "return_ball")
        local completed = witch_completion.execute()
        completed.quest = input.quest
        completed.varp = gc.read("vars", { varps = { 226 } }).varps[226]
        return completed
      end
      if phase == "strict_stats_block" or phase == "strict_hitpoints_block" or
        (input.quest == "witchs_house" and
          (phase == "bank_unknown" or phase == "loadout_required")) or
        phase == "experiment_loadout_required" or phase == "shed_ready_checkpoint" or
        phase == "experiment_complete_checkpoint" or
        phase == "amulet_missing_in_pillar_room" or phase == "unknown_stage" then
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
        waterfall_quest.execute(phase, input.restock)
      if not receipt or (receipt.status ~= "dispatched" and receipt.status ~= "complete") then
        gc.log("error", "quest-action-failed", { phase = phase, receipt = receipt })
        local failure = {
          status = "action_failed",
          quest = input.quest,
          phase = phase,
          receipt = receipt,
        }
        if input.quest == "waterfall" and waterfall_break_bypass_phases[phase] then
          failure.escape = waterfall_quest.escape_hostile_area()
        end
        return failure
      end
      local next_state, next_phase = wait_for_phase_change(input.quest, phase, 30)
      if not next_state then
        local failure = {
          status = "phase_timeout",
          quest = input.quest,
          phase = next_phase,
          receipt = receipt,
        }
        if input.quest == "waterfall" and waterfall_break_bypass_phases[phase] then
          failure.escape = waterfall_quest.escape_hostile_area()
        end
        return failure
      end
      if input.quest == "waterfall" and waterfall_break_bypass_phases[next_phase] then
        gc.phase("quest." .. input.quest .. "." .. next_phase, { breaks = false })
      else
        gc.phase("quest." .. input.quest .. "." .. next_phase)
      end
    end
  end,
}
