local config = gc.require("monkey_madness_config")
local shared = gc.require("shared_state")
local state_module = gc.require("monkey_madness_state")
local quest = gc.require("monkey_madness_quest")

local function read()
  return shared.read(config.id)
end

local function terminal(status, state, receipt)
  gc.await { action = { type = "safety.clear" }, breaks = false }
  gc.await { action = { type = "mouse.offscreen" }, breaks = false }
  return {
    status = status,
    quest = config.id,
    varp = state.varp,
    varbits = state.varbits,
    player = state.player,
    receipt = receipt,
  }
end

local function run()
  gc.await { event = "game.tick" }
  local initial = read()
  local initial_phase = state_module.resolve(initial)
  gc.state(initial_phase)
  gc.overlay { { label = "Quest", value = config.label } }
  if initial_phase == "complete" or initial_phase == "unknown_stage" then
    return terminal(initial_phase, initial)
  end
  local receipt = quest.execute(initial_phase)
  if not receipt or receipt.status ~= "complete" then
    return terminal("action_failed", read(), receipt)
  end
  for _ = 1, 60 do
    gc.await { event = "game.tick" }
    local state = read()
    local phase = state_module.resolve(state)
    if phase ~= initial_phase then return terminal(phase, state, receipt) end
  end
  return terminal("phase_timeout", read(), receipt)
end

return { run = run }
