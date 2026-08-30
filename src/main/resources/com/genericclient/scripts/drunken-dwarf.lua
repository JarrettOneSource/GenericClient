local NPC_ID = 322

local function fail(status, details)
  local value = details or {}
  value.status = status
  gc.log("error", "drunken-dwarf-failed", value)
  error(status, 0)
end

return {
  run = function()
    local event = gc.read("random_event")
    if not event.active or event.npc_id ~= NPC_ID then
      error("Drunken Dwarf solver started without its owned event")
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
        if not event.present then
          return {
            status = "solved",
            inventory = gc.read("inventory"),
            messages = gc.read("messages", { since_tick = started_tick, limit = 20 }),
          }
        end
        if not talked then
          local receipt = gc.await {
            action = {
              type = "npc.interact",
              id = NPC_ID,
              action = "Talk-to",
              within = 12,
            },
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

    fail("completion_not_observed", {
      event = gc.read("random_event"),
      messages = gc.read("messages", { since_tick = started_tick, limit = 30 }),
    })
  end,
}
