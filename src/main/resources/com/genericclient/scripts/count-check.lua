local NPC_IDS = { [12551] = true, [12552] = true }
local CHECK_ACCOUNT = "Check my account, Count Check!"
local LEAVE = "I'll see you another time."

local function fail(status, details)
  local value = details or {}
  value.status = status
  gc.log("error", "count-check-failed", value)
  error(status, 0)
end

local function outcome_message(since_tick)
  for _, message in ipairs(gc.read("messages", { since_tick = since_tick, limit = 30 })) do
    local text = string.lower(message.text or "")
    if string.find(text, "pass my checks", 1, true) then
      return "passed", message
    end
    if string.find(text, "fail my checks", 1, true) then
      return "failed", message
    end
  end
  return nil, nil
end

local function choose(dialogue)
  for _, wanted in ipairs({ CHECK_ACCOUNT, LEAVE }) do
    for _, option in ipairs(dialogue.options) do
      if option.text == wanted then
        return gc.await {
          action = { type = "dialogue.choose", text = option.text },
          breaks = false,
          timeout = { game_ticks = 20 },
        }
      end
    end
  end
  return {
    status = "rejected",
    result = "unexpected_count_check_choice",
    dialogue = dialogue,
  }
end

return {
  run = function()
    local event = gc.read("random_event")
    if not event.active or not NPC_IDS[event.npc_id] then
      error("Count Check solver started without its owned event")
    end

    local started_tick = event.detected_tick
    local talked = nil
    for _ = 1, 60 do
      local dialogue = gc.read("dialogue")
      if dialogue.type == "continue" then
        local continued = gc.await {
          action = { type = "dialogue.continue" },
          breaks = false,
          timeout = { game_ticks = 20 },
        }
        if continued.status ~= "dispatched" then
          error("Count Check continue failed: " .. tostring(continued.result), 0)
        end
      elseif dialogue.type == "choice" then
        local selected = choose(dialogue)
        if selected.status ~= "dispatched" then
          gc.log("error", "count-check-unexpected-choice", selected)
          error(selected.result, 0)
        end
      else
        local outcome, message = outcome_message(started_tick)
        event = gc.read("random_event")
        if outcome and not event.present then
          return { status = "solved", outcome = outcome, message = message }
        end
        if event.present and not talked then
          talked = gc.await {
            action = {
              type = "npc.interact",
              id = event.npc_id,
              action = "Talk-to",
              within = 12,
            },
            breaks = false,
            timeout = { game_ticks = 30 },
          }
          if talked.status ~= "dispatched" then
            fail("talk_failed", { receipt = talked, event = event })
          end
        else
          gc.await { event = "game.tick" }
        end
      end
    end

    fail("outcome_not_observed", {
      event = gc.read("random_event"),
      messages = gc.read("messages", { since_tick = started_tick, limit = 30 }),
    })
  end,
}
