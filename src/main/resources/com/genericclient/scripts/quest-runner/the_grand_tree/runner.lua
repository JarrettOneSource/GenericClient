local config = gc.require("grand_tree_config")
local shared = gc.require("shared_state")
local state_module = gc.require("grand_tree_state")
local quest = gc.require("grand_tree_quest")

local function read()
  return shared.read(config.id)
end

local function under_attack()
  local player = gc.read("player")
  for _, target in ipairs(gc.read("npcs", { within = 20, limit = 50 })) do
    if target.world.plane == player.world.plane and target.combat_level > 0 and
      target.interacting == player.name then
      return true
    end
  end
  return false
end

local function terminal(status, state, receipt)
  local safety_preserved = under_attack()
  if not safety_preserved then
    gc.await { action = { type = "safety.clear" }, breaks = false }
    gc.await { action = { type = "mouse.offscreen" }, breaks = false }
  end
  return {
    status = status,
    quest = config.id,
    varp = state.varp,
    player = state.player,
    agility = state.skills.agility,
    receipt = receipt,
    safety_preserved = safety_preserved,
  }
end

local function run(input)
  gc.await { event = "game.tick" }
  local initial = read()
  local initial_phase = state_module.resolve(initial)
  if initial_phase == "complete" then return terminal("complete", initial) end
  if initial_phase == "strict_stats_block" or initial_phase == "inventory_space_block" or
    initial_phase == "unknown_stage" then
    return terminal(initial_phase, initial)
  end
  if initial_phase == "invasion_plans_checkpoint" then
    return terminal("invasion_plans_checkpoint", initial)
  end

  local safety = gc.await {
    action = {
      type = "safety.configure",
      minimum_hitpoints = 6,
      consumables = {
        { id = 379, action = "Eat", heal_amount = 12 },
      },
      continue_after_consumable = true,
      allow_overheal = false,
    },
    breaks = false,
  }
  if safety.status ~= "complete" then
    return terminal("safety_guard_failed", initial, safety)
  end

  gc.overlay {
    { label = "Quest", value = config.label },
    { label = "Phase", value = initial_phase },
  }
  local receipt = quest.execute(initial_phase)
  if not receipt or receipt.status ~= "complete" then
    return terminal("action_failed", read(), receipt)
  end
  for _ = 1, 60 do
    gc.await { event = "game.tick" }
    local state = read()
    local phase = state_module.resolve(state)
    if phase ~= initial_phase then
      if phase == "invasion_plans_checkpoint" then
        return terminal("invasion_plans_checkpoint", state, receipt)
      end
      return terminal(phase, state, receipt)
    end
  end
  return terminal("phase_timeout", read(), receipt)
end

return { run = run }
