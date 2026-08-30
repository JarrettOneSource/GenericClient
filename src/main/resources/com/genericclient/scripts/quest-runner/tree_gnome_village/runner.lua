local config = gc.require("tree_gnome_config")
local state_module = gc.require("tree_gnome_state")
local quest = gc.require("tree_gnome_quest")
local shared = gc.require("shared_state")
local preparation = gc.require("shared_preparation")

local checkpoint_rank = {
  accept_quest = 0,
  talk_montai = 1,
  give_logs = 1,
  talk_montai_again = 1,
  tracker_one = 2,
  tracker_two = 2,
  tracker_three = 2,
  fire_ballista = 2,
  enter_orb_tower = 3,
  search_orb_chest = 3,
  return_first_orb = 4,
  fight_warlord = 5,
  take_orbs = 5,
  return_orbs = 6,
  finish_dialogue = 6,
  complete = 7,
}

local break_bypass = {
  enter_orb_tower = true,
  search_orb_chest = true,
  fight_warlord = true,
  take_orbs = true,
}

local function overlay(phase)
  gc.overlay {
    { label = "Quest", value = config.label },
    { label = "Phase", value = phase:gsub("_", " ") },
  }
end

local function read()
  return shared.read(config.id)
end

local function resolve(state)
  return state_module.resolve(state)
end

local function wait_for_phase_change(previous, ticks)
  for _ = 1, ticks do
    gc.await { event = "game.tick" }
    local state = read()
    local phase = resolve(state)
    if phase ~= previous then return state, phase end
  end
  return nil, previous
end

local function prepare(phase, restock)
  local loadout = phase == "combat_loadout_required" and
    config.combat_loadout or config.initial_loadout
  return preparation.prepare_items(config.id, restock, loadout)
end

local function configure_safety(state)
  local retaliate = gc.await {
    action = { type = "combat.set_auto_retaliate", enabled = false },
    breaks = false,
    timeout = { game_ticks = 20 },
  }
  if retaliate.status ~= "set" and retaliate.status ~= "unchanged" then
    return nil, { status = "auto_retaliate_failed", receipt = retaliate }
  end
  local threshold = math.max(4, math.floor(state.player.max_hitpoints * 0.25))
  local safety = gc.await {
    action = {
      type = "safety.configure",
      minimum_hitpoints = threshold,
      consumables = { { id = config.items.wine, action = "Drink", heal_amount = 11 } },
      continue_after_consumable = true,
      allow_overheal = false,
    },
    breaks = false,
  }
  if safety.status ~= "complete" then
    return nil, { status = "safety_guard_failed", receipt = safety }
  end
  return true
end

local function terminal(state, phase)
  return {
    status = phase,
    quest = config.id,
    varp = state.varp,
    missing = state.missing,
    magic = state.skills.magic.level,
    current_hitpoints = state.player and state.player.current_hitpoints,
    maximum_hitpoints = state.player and state.player.max_hitpoints,
  }
end

local function run(input)
  gc.await { event = "game.tick" }
  local initial = read()
  local initial_phase = resolve(initial)
  local initial_rank = checkpoint_rank[initial_phase] or -1

  if initial_phase == "complete" then
    gc.await { action = { type = "mouse.offscreen" }, breaks = false }
    return { status = "complete", quest = config.id, varp = initial.varp }
  end
  if initial_phase == "strict_stats_block" or initial_phase == "bank_unknown" or
    initial_phase == "unknown_stage" then
    return terminal(initial, initial_phase)
  end

  local configured, configure_error = configure_safety(initial)
  if not configured then return configure_error end
  if initial_phase == "loadout_required" or initial_phase == "combat_loadout_required" then
    local prepared, prepare_error = prepare(initial_phase, input.restock)
    if not prepared then return prepare_error end
  end

  while true do
    local state = read()
    local phase = resolve(state)
    overlay(phase)
    gc.log("info", "quest-phase", { quest = config.id, phase = phase, varp = state.varp })

    if phase == "complete" then
      gc.await { action = { type = "safety.clear" }, breaks = false }
      gc.await { action = { type = "mouse.offscreen" }, breaks = false }
      return { status = "complete", quest = config.id, varp = state.varp }
    end
    if input.scope == "checkpoint" and (checkpoint_rank[phase] or -1) > initial_rank then
      gc.await { action = { type = "mouse.offscreen" }, breaks = false }
      return { status = phase .. "_checkpoint", quest = config.id, varp = state.varp }
    end
    if phase == "loadout_required" or phase == "combat_loadout_required" then
      local prepared, prepare_error = prepare(phase, input.restock)
      if not prepared then return prepare_error end
    elseif phase == "strict_stats_block" or phase == "bank_unknown" or
      phase == "unknown_stage" then
      return terminal(state, phase)
    else
      if gc.next_action() == "stop_safely" then
        return { status = "stopped", quest = config.id, phase = phase }
      end
      if break_bypass[phase] then
        gc.phase("quest." .. config.id .. "." .. phase, { breaks = false })
      else
        gc.phase("quest." .. config.id .. "." .. phase)
      end
      local receipt = quest.execute(phase)
      if not receipt or (receipt.status ~= "complete" and receipt.status ~= "dispatched") then
        local failure = {
          status = "action_failed",
          quest = config.id,
          phase = phase,
          receipt = receipt,
        }
        if break_bypass[phase] then failure.escape = quest.escape_hostile_area() end
        return failure
      end
      local next_state, next_phase = wait_for_phase_change(phase, 60)
      if not next_state then
        local failure = {
          status = "phase_timeout",
          quest = config.id,
          phase = next_phase,
          receipt = receipt,
        }
        if break_bypass[phase] then failure.escape = quest.escape_hostile_area() end
        return failure
      end
    end
  end
end

return { run = run }
