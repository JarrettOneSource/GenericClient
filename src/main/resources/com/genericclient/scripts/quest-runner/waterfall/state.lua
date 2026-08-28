local config = gc.require("waterfall_config")
local shared = gc.require("shared_state")

local function resolve(state)
  if state.quests.waterfall_quest.state == "finished" or state.varp == 8 then
    return "complete"
  end
  local hp = state.player and state.player.max_hitpoints or 0
  if hp < 15 or state.player.current_hitpoints < hp then return "strict_hitpoints_block" end
  if state.varp == 0 and #shared.missing_carried_items(state, config.loadout) > 0 then
    if not state.bank.available then return "bank_unknown" end
    local missing = shared.missing_items(state, config.loadout)
    if #missing > 0 then
      state.missing = missing
      return "loadout_required"
    end
  end
  return "waterfall_ready_checkpoint"
end

return { resolve = resolve }
