-- Dial facts were cross-checked against Infinitay/Random-Event-Helper at
-- 43e578fd30f60ac765a32b7b99c82b6ca3791776. GenericClient owns this Lua solver.

local NPC_ID = 5426
local VARBITS = { left = 9585, centre = 9593, right = 9594 }
local WIDGETS = {
  left = { up = 1703941, down = 1703942, label = 1703958 },
  centre = { up = 1703944, down = 1703945, label = 1703959 },
  right = { up = 1703947, down = 1703948, label = 1703960 },
  confirm = 1703961,
}
local ITEM_VALUES = { COINS = 0, BOWL = 1, BAR = 2, RING = 3 }
local ACCEPT_HELP = "Yes, I'll help you unlock your chest."

local function fail(value)
  gc.log("error", "capt-arnav-failed", value)
  error(value.status or "capt_arnav_failed", 0)
end

local function widgets(ids)
  return gc.read("widgets", { ids = ids, limit = #ids })
end

local function widget(id)
  local found = widgets({ id })
  return found[1]
end

local function click(id)
  return gc.await {
    action = { type = "ui.click", widget_id = id },
    breaks = false,
    timeout = { game_ticks = 20 },
  }
end

local function open_puzzle()
  if widget(WIDGETS.confirm) then return true end
  local talked = gc.await {
    action = { type = "npc.interact", id = NPC_ID, action = "Talk-to", within = 12 },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if talked.status ~= "dispatched" then
    return nil, { status = "talk_failed", receipt = talked }
  end
  for _ = 1, 30 do
    gc.await { event = "game.tick" }
    if widget(WIDGETS.confirm) then return true end
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      local continued = gc.await {
        action = { type = "dialogue.continue" },
        breaks = false,
      }
      if continued.status ~= "dispatched" then
        return nil, { status = "dialogue_failed", receipt = continued }
      end
    elseif dialogue.type == "choice" then
      local selected = nil
      for _, option in ipairs(dialogue.options) do
        if option.text == ACCEPT_HELP then selected = option.text break end
      end
      if not selected then
        return nil, { status = "unexpected_dialogue_choice", dialogue = dialogue }
      end
      local chosen = gc.await {
        action = { type = "dialogue.choose", text = selected },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
      if chosen.status ~= "dispatched" then
        return nil, { status = "dialogue_choice_failed", receipt = chosen }
      end
    end
  end
  return nil, { status = "puzzle_not_open", event = gc.read("random_event") }
end

local function required_values()
  local ids = { WIDGETS.left.label, WIDGETS.centre.label, WIDGETS.right.label }
  local found = widgets(ids)
  local by_id = {}
  for _, value in ipairs(found) do by_id[value.id] = value end
  local result = {}
  for _, slot in ipairs({ "left", "centre", "right" }) do
    local label = by_id[WIDGETS[slot].label]
    local required = label and ITEM_VALUES[string.upper(label.text or "")] or nil
    if required == nil then
      return nil, { status = "unknown_required_item", slot = slot, widget = label }
    end
    result[slot] = required
  end
  return result
end

local function active_values()
  local value = gc.read("vars", {
    varbits = { VARBITS.left, VARBITS.centre, VARBITS.right },
  }).varbits
  return {
    left = value[VARBITS.left],
    centre = value[VARBITS.centre],
    right = value[VARBITS.right],
  }
end

local function align(slot, required)
  for _ = 1, 4 do
    local active = active_values()[slot]
    if active == required then return true end
    local add = (required - active + 4) % 4
    local subtract = (active - required + 4) % 4
    local button = add <= subtract and WIDGETS[slot].up or WIDGETS[slot].down
    local receipt = click(button)
    if receipt.status ~= "dispatched" then
      return nil, { status = "dial_click_failed", slot = slot, receipt = receipt }
    end
    gc.await { event = "game.tick" }
  end
  return nil, {
    status = "dial_alignment_failed",
    slot = slot,
    required = required,
    active = active_values()[slot],
  }
end

local function completion_message(since_tick)
  for _, message in ipairs(gc.read("messages", { since_tick = since_tick, limit = 30 })) do
    local text = string.lower(message.text or "")
    if string.find(text, "your reward", 1, true) or
      string.find(text, "successfully", 1, true) then
      return message
    end
  end
  return nil
end

return {
  run = function()
    local event = gc.read("random_event")
    if not event.active or event.npc_id ~= NPC_ID then
      error("Capt' Arnav solver started without its owned event")
    end
    local opened, open_error = open_puzzle()
    if not opened then fail(open_error) end
    local required, label_error = required_values()
    if not required then fail(label_error) end
    for _, slot in ipairs({ "left", "centre", "right" }) do
      local aligned, align_error = align(slot, required[slot])
      if not aligned then fail(align_error) end
    end
    local started_tick = gc.read("runtime").game_tick
    local confirmed = click(WIDGETS.confirm)
    if confirmed.status ~= "dispatched" then
      fail({ status = "confirm_failed", receipt = confirmed })
    end
    for _ = 1, 30 do
      gc.await { event = "game.tick" }
      local reward = completion_message(started_tick)
      if reward then
        return { status = "solved", reward = reward, required = required }
      end
      local dialogue = gc.read("dialogue")
      if dialogue.type == "continue" then
        gc.await { action = { type = "dialogue.continue" }, breaks = false }
      end
    end
    error("Capt' Arnav confirmation had no observable reward receipt")
  end,
}
