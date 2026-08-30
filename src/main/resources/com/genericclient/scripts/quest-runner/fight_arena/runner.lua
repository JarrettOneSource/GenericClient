local config = gc.require("fight_arena_config")
local state_module = gc.require("fight_arena_state")
local quest = gc.require("fight_arena_quest")
local shared = gc.require("shared_state")
local preparation = gc.require("shared_preparation")

local checkpoint_rank = {
  accept_quest = 0,
  obtain_khazard_armour = 1,
  equip_khazard_armour = 2,
  talk_head_guard = 3,
  buy_khali_brew = 4,
  give_khali_brew = 5,
  get_cell_keys = 6,
  free_sammy = 7,
  talk_sammy_for_ogre = 8,
  fight_ogre = 9,
  talk_general_khazard = 10,
  talk_hengrad = 11,
  talk_sammy_for_scorpion = 12,
  fight_scorpion = 13,
  talk_sammy_for_bouncer = 14,
  fight_bouncer = 15,
  leave_arena = 16,
  finish_quest = 17,
  complete = 18,
}

local break_bypass = {
  free_sammy = true,
  talk_sammy_for_ogre = true,
  fight_ogre = true,
  talk_general_khazard = true,
  talk_hengrad = true,
  talk_sammy_for_scorpion = true,
  fight_scorpion = true,
  talk_sammy_for_bouncer = true,
  fight_bouncer = true,
  leave_arena = true,
}

local function read()
  return shared.read(config.id)
end

local function resolve(state)
  return state_module.resolve(state)
end

local function overlay(phase)
  gc.overlay {
    { label = "Quest", value = config.label },
    { label = "Phase", value = phase:gsub("_", " ") },
  }
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

local function prepare(restock)
  return preparation.prepare_items(config.id, restock, config.loadout)
end

local function terminal(state, phase)
  return {
    status = phase,
    quest = config.id,
    varp = state.varp,
    varbits = state.varbits,
    missing = state.missing,
    player = state.player,
    inventory = state.inventory,
    equipment = state.equipment,
  }
end

local function stop(result, mouse_offscreen)
  gc.await { action = { type = "safety.clear" }, breaks = false }
  if mouse_offscreen then
    gc.await { action = { type = "mouse.offscreen" }, breaks = false }
  end
  return result
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
  if initial_phase == "strict_stats_block" or initial_phase == "unknown_stage" then
    return terminal(initial, initial_phase)
  end
  if initial_phase == "loadout_required" or initial_phase == "combat_loadout_required" then
    local prepared, failure = prepare(input.restock)
    if not prepared then return failure end
    initial = read()
    initial_phase = resolve(initial)
    initial_rank = checkpoint_rank[initial_phase] or -1
  end

  local retaliate = gc.await {
    action = { type = "combat.set_auto_retaliate", enabled = false },
    breaks = false,
    timeout = { game_ticks = 20 },
  }
  if retaliate.status ~= "set" and retaliate.status ~= "unchanged" then
    return { status = "auto_retaliate_failed", receipt = retaliate }
  end
  local safety = gc.await {
    action = {
      type = "safety.configure",
      minimum_hitpoints = 6,
      consumables = { { id = config.items.food, action = "Eat", heal_amount = 12 } },
      continue_after_consumable = true,
      allow_overheal = false,
    },
    breaks = false,
  }
  if safety.status ~= "complete" then
    return { status = "safety_guard_failed", receipt = safety }
  end

  while true do
    local state = read()
    local phase = resolve(state)
    overlay(phase)
    gc.log("info", "quest-phase", { quest = config.id, phase = phase, varp = state.varp })

    if phase == "complete" then
      return stop({ status = "complete", quest = config.id, varp = state.varp }, true)
    end
    if input.scope == "checkpoint" and (checkpoint_rank[phase] or -1) > initial_rank then
      return stop(terminal(state, phase .. "_checkpoint"), true)
    end
    if phase == "loadout_required" or phase == "combat_loadout_required" then
      local prepared, failure = prepare(input.restock)
      if not prepared then return stop(failure) end
    elseif phase == "strict_stats_block" or phase == "unknown_stage" then
      return stop(terminal(state, phase))
    else
      if gc.next_action() == "stop_safely" then
        return stop({ status = "stopped", quest = config.id, phase = phase })
      end
      if break_bypass[phase] then
        gc.phase("quest." .. config.id .. "." .. phase, { breaks = false })
      else
        gc.phase("quest." .. config.id .. "." .. phase)
      end
      local receipt = quest.execute(phase)
      if not receipt or (receipt.status ~= "complete" and receipt.status ~= "dispatched") then
        return stop({
          status = "action_failed",
          quest = config.id,
          phase = phase,
          receipt = receipt,
        })
      end
      local next_state, next_phase = wait_for_phase_change(phase, 60)
      if not next_state then
        return stop({
          status = "phase_timeout",
          quest = config.id,
          phase = next_phase,
          receipt = receipt,
        })
      end
    end
  end
end

return { run = run }
