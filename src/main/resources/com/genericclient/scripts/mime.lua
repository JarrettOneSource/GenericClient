local INVITATION_NPC_ID = 6753
local MIME_NPC_ID = 321
local ACCEPT_SHOW = "Yeah, I'd love to do a mime show."
local FIRST_BUTTON = 12320770

local EMOTES = {
  [857] = { name = "Think", widget = 12320770 },
  [860] = { name = "Cry", widget = 12320774 },
  [861] = { name = "Laugh", widget = 12320771 },
  [866] = { name = "Dance", widget = 12320775 },
  [1128] = { name = "Glass wall", widget = 12320777 },
  [1129] = { name = "Lean", widget = 12320776 },
  [1130] = { name = "Climb rope", widget = 12320772 },
  [1131] = { name = "Glass box", widget = 12320773 },
}

local function fail(status, details)
  local value = details or {}
  value.status = status
  gc.log("error", "mime-failed", value)
  error(status, 0)
end

local function mime()
  return gc.read("npcs", { id = MIME_NPC_ID, within = 20, limit = 1 })[1]
end

local function response_panel_open()
  return #gc.read("widgets", { ids = { FIRST_BUTTON }, visible = true, limit = 1 }) > 0
end

local function choose_show(dialogue)
  for _, option in ipairs(dialogue.options) do
    if option.text == ACCEPT_SHOW then
      return gc.await {
        action = { type = "dialogue.choose", text = option.text },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
    end
  end
  fail("unexpected_invitation_choice", { dialogue = dialogue })
end

local function enter_show()
  if mime() then return end

  local talked = gc.await {
    action = {
      type = "npc.interact",
      id = INVITATION_NPC_ID,
      action = "Talk-to",
      within = 12,
    },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if talked.status ~= "dispatched" then
    fail("invitation_talk_failed", { receipt = talked })
  end

  for _ = 1, 100 do
    if mime() then return end
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      local continued = gc.await {
        action = { type = "dialogue.continue" },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
      if continued.status ~= "dispatched" then
        fail("invitation_continue_failed", { receipt = continued })
      end
    elseif dialogue.type == "choice" then
      local chosen = choose_show(dialogue)
      if chosen.status ~= "dispatched" then
        fail("invitation_choice_failed", { receipt = chosen })
      end
    else
      gc.await { event = "game.tick" }
    end
  end

  fail("show_not_reached", {
    event = gc.read("random_event"),
    player = gc.read("player"),
    dialogue = gc.read("dialogue"),
  })
end

local function reward_message(since_tick)
  for _, message in ipairs(gc.read("messages", { since_tick = since_tick, limit = 40 })) do
    local text = string.lower(message.text or "")
    if string.find(text, "you can now use the", 1, true) and
      string.find(text, "emote", 1, true) then
      return message
    end
  end
  return nil
end

local function perform_show(started_tick)
  local answer = nil
  local waiting_for_panel_to_close = false
  local rounds = {}

  for _ = 1, 800 do
    local actor = mime()
    if not actor then
      local reward = reward_message(started_tick)
      if reward then
        return rounds, reward
      end
      gc.await { event = "game.tick" }
    else
      local observed = EMOTES[actor.animation]
      if observed then answer = observed end

      local panel_open = response_panel_open()
      if waiting_for_panel_to_close then
        if not panel_open then
          waiting_for_panel_to_close = false
          answer = nil
        end
      elseif panel_open and answer then
        local receipt = gc.await {
          action = { type = "ui.click", widget_id = answer.widget },
          breaks = false,
          timeout = { game_ticks = 20 },
        }
        if receipt.status ~= "dispatched" then
          fail("emote_click_failed", { answer = answer.name, receipt = receipt })
        end
        rounds[#rounds + 1] = answer.name
        waiting_for_panel_to_close = true
      else
        gc.await { event = "game.tick" }
      end
    end
  end

  fail("show_completion_not_observed", {
    rounds = rounds,
    player = gc.read("player"),
    mime = mime(),
    messages = gc.read("messages", { since_tick = started_tick, limit = 40 }),
  })
end

return {
  run = function()
    local event = gc.read("random_event")
    if not event.active or event.npc_id ~= INVITATION_NPC_ID then
      error("Mime solver started without its owned event")
    end

    local started_tick = event.detected_tick
    enter_show()
    local rounds, reward = perform_show(started_tick)
    return { status = "solved", rounds = rounds, reward = reward }
  end,
}
