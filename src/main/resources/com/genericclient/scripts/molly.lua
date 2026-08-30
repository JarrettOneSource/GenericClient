local POSTIE_PETE_ID = 6738
local MOLLY_NAME = "Molly"
local SUSPECT_NAME = "Suspect"

local OBJECTS = {
  claw = 20811,
  panel = 20813,
  door = 20817,
}

local WIDGETS = {
  grab = 18153475,
  x_minus = 18153479,
  y_minus = 18153480,
  x_plus = 18153481,
  y_plus = 18153482,
}

-- Each Molly ID encodes one outfit and colour. The matching suspect uses the
-- same outfit and colour in the client cache, so no screen-image guess is needed.
local MATCHING_SUSPECT = {
  [342] = 5468, [5464] = 5469, [5474] = 5470, [352] = 357,
  [5478] = 5477, [5471] = 350, [356] = 351, [5476] = 358,
  [5480] = 346, [5467] = 347, [5485] = 5482, [5486] = 5483,
  [361] = 5484, [362] = 5465, [363] = 343, [5487] = 344,
  [364] = 345, [365] = 354, [366] = 355, [367] = 359,
}

local function fail(status, details)
  local value = details or {}
  value.status = status
  gc.log("error", "molly-failed", value)
  error(status, 0)
end

local function click(widget_id)
  return gc.await {
    action = { type = "ui.click", widget_id = widget_id },
    breaks = false,
    timeout = { game_ticks = 20 },
  }
end

local function continue_dialogue()
  return gc.await {
    action = { type = "dialogue.continue" },
    breaks = false,
    timeout = { game_ticks = 20 },
  }
end

local function choose(dialogue, text)
  for _, option in ipairs(dialogue.options or {}) do
    if option.text == text then
      return gc.await {
        action = { type = "dialogue.choose", text = text },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
    end
  end
  return nil
end

local function message_since(tick, wanted)
  for _, message in ipairs(gc.read("messages", { since_tick = tick, limit = 40 })) do
    if string.find(string.lower(message.text or ""), wanted, 1, true) then
      return message
    end
  end
  return nil
end

local function in_event_room()
  return gc.read("player").world.x >= 10000
end

local function enter_event()
  if in_event_room() then return end
  local talked = false
  for _ = 1, 160 do
    if in_event_room() then return end
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      local receipt = continue_dialogue()
      if receipt.status ~= "dispatched" then
        fail("postie_continue_failed", { receipt = receipt })
      end
    elseif dialogue.type == "choice" then
      local receipt = choose(dialogue, "Sure, anything for Molly.")
      if not receipt or receipt.status ~= "dispatched" then
        fail("postie_invitation_choice_missing", { dialogue = dialogue, receipt = receipt })
      end
    elseif not talked then
      local receipt = gc.await {
        action = { type = "npc.interact", id = POSTIE_PETE_ID, action = "Talk-to", within = 12 },
        breaks = false,
        timeout = { game_ticks = 30 },
      }
      if receipt.status ~= "dispatched" then
        fail("postie_talk_failed", { receipt = receipt })
      end
      talked = true
    else
      gc.await { event = "game.tick" }
    end
  end
  fail("molly_room_not_reached", { player = gc.read("player"), dialogue = gc.read("dialogue") })
end

local function molly()
  for _, npc in ipairs(gc.read("npcs", { within = 30, limit = 30 })) do
    if npc.name == MOLLY_NAME then return npc end
  end
  return nil
end

local function explain_event()
  local actor = molly()
  if not actor then fail("molly_not_observed", { player = gc.read("player") }) end
  local talked = gc.await {
    action = { type = "npc.interact", id = actor.id, action = "Talk-to", within = 20 },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if talked.status ~= "dispatched" then fail("molly_talk_failed", { receipt = talked }) end

  local opened = false
  for _ = 1, 100 do
    gc.await { event = "game.tick" }
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      opened = true
      local receipt = continue_dialogue()
      if receipt.status ~= "dispatched" then fail("molly_continue_failed", { receipt = receipt }) end
    elseif dialogue.type == "choice" then
      opened = true
      local receipt = choose(dialogue, "No thanks.")
      if not receipt or receipt.status ~= "dispatched" then
        fail("molly_tutorial_choice_missing", { dialogue = dialogue, receipt = receipt })
      end
    elseif opened then
      return actor.id
    end
  end
  fail("molly_explanation_timeout", { dialogue = gc.read("dialogue") })
end

local function nearest_object(id, action)
  return gc.read("objects", { id = id, action = action, within = 20, limit = 5 })[1]
end

local function open_nearest_door()
  local door = nearest_object(OBJECTS.door, "Open")
  if not door then fail("molly_door_not_observed", { player = gc.read("player") }) end
  local receipt = gc.await {
    action = {
      type = "object.interact",
      id = door.id,
      action = "Open",
      world = door.world,
      within = 20,
    },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if receipt.status ~= "dispatched" then fail("molly_door_failed", { receipt = receipt }) end
  for _ = 1, 15 do gc.await { event = "game.tick" } end
end

local function open_controls()
  if #gc.read("widgets", { ids = { WIDGETS.grab }, visible = true, limit = 1 }) > 0 then return end
  local panel = nearest_object(OBJECTS.panel, "Use")
  if not panel then fail("molly_control_panel_not_observed", { player = gc.read("player") }) end
  local receipt = gc.await {
    action = {
      type = "object.interact",
      id = panel.id,
      action = "Use",
      world = panel.world,
      within = 20,
    },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if receipt.status ~= "dispatched" then fail("molly_control_panel_failed", { receipt = receipt }) end
  for _ = 1, 20 do
    gc.await { event = "game.tick" }
    if #gc.read("widgets", { ids = { WIDGETS.grab }, visible = true, limit = 1 }) > 0 then return end
  end
  fail("molly_controls_not_open")
end

local function suspect(id)
  return gc.read("npcs", { id = id, within = 30, limit = 1 })[1]
end

local function capture_twin(target_id, started_tick)
  for step = 1, 200 do
    local success = message_since(started_tick, "caught the evil twin")
    if success then return { message = success, steps = step - 1 } end

    local claw = gc.read("objects", { id = OBJECTS.claw, within = 30, limit = 1 })[1]
    local target = suspect(target_id)
    if not claw or not target then
      gc.await { event = "game.tick" }
    else
      local widget
      if claw.world.x < target.world.x then widget = WIDGETS.x_plus
      elseif claw.world.x > target.world.x then widget = WIDGETS.x_minus
      elseif claw.world.y < target.world.y then widget = WIDGETS.y_plus
      elseif claw.world.y > target.world.y then widget = WIDGETS.y_minus
      else widget = WIDGETS.grab end

      local receipt = click(widget)
      if receipt.status ~= "dispatched" then
        fail("molly_control_click_failed", { widget = widget, receipt = receipt })
      end
      for _ = 1, 3 do gc.await { event = "game.tick" } end
    end
  end
  fail("molly_capture_timeout", {
    target_id = target_id,
    target = suspect(target_id),
    claw = gc.read("objects", { id = OBJECTS.claw, within = 30, limit = 1 })[1],
  })
end

local function drain_dialogue_until_closed()
  local opened = false
  for _ = 1, 80 do
    gc.await { event = "game.tick" }
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      opened = true
      local receipt = continue_dialogue()
      if receipt.status ~= "dispatched" then fail("molly_reward_continue_failed", { receipt = receipt }) end
    elseif dialogue.type == "choice" then
      fail("unexpected_molly_reward_choice", { dialogue = dialogue })
    elseif opened then
      return
    end
  end
  fail("molly_reward_dialogue_timeout", { dialogue = gc.read("dialogue") })
end

local function claim_reward(started_tick)
  drain_dialogue_until_closed()
  open_nearest_door()
  local actor = molly()
  if not actor then fail("molly_not_observed_for_reward", { player = gc.read("player") }) end
  local talked = gc.await {
    action = { type = "npc.interact", id = actor.id, action = "Talk-to", within = 20 },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if talked.status ~= "dispatched" then fail("molly_reward_talk_failed", { receipt = talked }) end

  for _ = 1, 120 do
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      local receipt = continue_dialogue()
      if receipt.status ~= "dispatched" then fail("molly_reward_continue_failed", { receipt = receipt }) end
    elseif dialogue.type == "choice" then
      fail("unexpected_molly_reward_choice", { dialogue = dialogue })
    end
    gc.await { event = "game.tick" }
    local reward = message_since(started_tick, "your reward is:")
    if reward and not in_event_room() then
      local final = gc.read("dialogue")
      if final.type == "continue" then continue_dialogue() end
      return reward
    end
  end
  fail("molly_reward_not_observed", {
    player = gc.read("player"),
    messages = gc.read("messages", { since_tick = started_tick, limit = 40 }),
  })
end

return {
  run = function()
    local event = gc.read("random_event")
    if not event.active or event.npc_id ~= POSTIE_PETE_ID then
      error("Molly solver started without its owned event")
    end
    local started_tick = event.detected_tick
    enter_event()
    local molly_id = explain_event()
    local target_id = MATCHING_SUSPECT[molly_id]
    if not target_id then fail("unknown_molly_appearance", { molly_id = molly_id }) end
    open_nearest_door()
    open_controls()
    local capture = capture_twin(target_id, started_tick)
    local reward = claim_reward(started_tick)
    return {
      status = "solved",
      molly_id = molly_id,
      target_id = target_id,
      control_steps = capture.steps,
      capture = capture.message,
      reward = reward,
    }
  end,
}
