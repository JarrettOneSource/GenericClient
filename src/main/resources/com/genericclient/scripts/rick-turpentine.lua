local NPC_ID = 375

local function fail(status, details)
  local value = details or {}
  value.status = status
  gc.log("error", "rick-turpentine-failed", value)
  error(status, 0)
end

local function reward_message(since_tick)
  for _, message in ipairs(gc.read("messages", { since_tick = since_tick, limit = 20 })) do
    if string.find(string.lower(message.text or ""), "your reward is:", 1, true) then
      return message
    end
  end
  return nil
end

return {
  run = function()
    local event = gc.read("random_event")
    if not event.active or event.npc_id ~= NPC_ID then
      error("Rick Turpentine solver started without its owned event")
    end

    local started_tick = event.detected_tick
    local talked = false
    for _ = 1, 80 do
      local dialogue = gc.read("dialogue")
      if dialogue.type == "continue" then
        local continued = gc.await {
          action = { type = "dialogue.continue" },
          breaks = false,
          timeout = { game_ticks = 20 },
        }
        if continued.status ~= "dispatched" then
          fail("dialogue_failed", { receipt = continued, dialogue = dialogue })
        end
      elseif dialogue.type == "choice" then
        fail("unexpected_dialogue_choice", { dialogue = dialogue })
      else
        event = gc.read("random_event")
        local reward = reward_message(started_tick)
        if reward and not event.present then
          return { status = "solved", reward = reward, inventory = gc.read("inventory") }
        end
        if event.present and not talked then
          local receipt = gc.await {
            action = { type = "npc.interact", id = NPC_ID, action = "Talk-to", within = 12 },
            breaks = false,
            timeout = { game_ticks = 30 },
          }
          if receipt.status ~= "dispatched" then
            fail("talk_failed", { receipt = receipt, event = event })
          end
          talked = true
        else
          gc.await { event = "game.tick" }
        end
      end
    end

    fail("reward_not_observed", {
      event = gc.read("random_event"),
      messages = gc.read("messages", { since_tick = started_tick, limit = 20 }),
    })
  end,
}
