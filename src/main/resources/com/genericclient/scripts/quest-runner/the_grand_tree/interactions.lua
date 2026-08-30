local config = gc.require("grand_tree_config")

local function npc(ids, within, accessible)
  for _, id in ipairs(ids) do
    local query = { id = id, within = within or 20, limit = 1 }
    if accessible then
      query.where = { clickable = true }
    end
    local found = gc.read("npcs", query)[1]
    if found then return found end
  end
  return nil
end

local function choose(dialogue, choices)
  for choice_index, wanted in ipairs(choices) do
    for _, option in ipairs(dialogue.options or {}) do
      if option.text == wanted then
        table.remove(choices, choice_index)
        return gc.await {
          action = { type = "dialogue.choose", text = wanted },
          breaks = false,
          timeout = { game_ticks = 20 },
        }
      end
    end
  end
  return { status = "rejected", result = "grand_tree_dialogue_choice_missing", dialogue = dialogue }
end

local function finish_dialogue(predicate, choices, since_tick)
  local progressed = false
  local closed_ticks = 0
  for _ = 1, 120 do
    gc.await { event = "game.tick" }
    for _, message in ipairs(gc.read("messages", { since_tick = since_tick, limit = 10 })) do
      if string.find(string.lower(message.text or ""), "can't reach that", 1, true) then
        return nil, { status = "king_narnode_unreachable", message = message }
      end
    end
    progressed = progressed or predicate()
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      closed_ticks = 0
      local receipt = gc.await {
        action = { type = "dialogue.continue" },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
      if receipt.status ~= "dispatched" then return nil, receipt end
    elseif dialogue.type == "choice" then
      closed_ticks = 0
      if #choices == 0 then
        return nil, { status = "unexpected_grand_tree_dialogue", dialogue = dialogue }
      end
      local receipt = choose(dialogue, choices)
      if receipt.status ~= "dispatched" then return nil, receipt end
    elseif progressed then
      closed_ticks = closed_ticks + 1
      if closed_ticks >= 2 then return true end
    end
  end
  return nil, { status = "grand_tree_dialogue_timeout", dialogue = gc.read("dialogue") }
end

local function talk_narnode()
  local started_tick = gc.read("runtime").game_tick
  local clicked = { status = "dispatched", result = "resumed_open_dialogue" }
  if not gc.read("dialogue").open then
    local target = npc(config.npcs.king_narnode, 20, true)
    if not target then
      return {
        status = "king_narnode_not_reachable",
        nearby = gc.read("npcs", { within = 20, limit = 30 }),
      }
    end
    clicked = gc.await {
      action = { type = "npc.interact", id = target.id, action = "Talk-to", within = 20 },
      breaks = true,
      timeout = { game_ticks = 40 },
    }
    if clicked.status ~= "dispatched" then return clicked end
  end
  local finished, failure = finish_dialogue(
    function()
      local vars = gc.read("vars", { varps = { config.varp } })
      return vars.varps[config.varp] >= 10
    end,
    { "You seem worried, what's up?", "Yes.", "I'd be happy to help!" },
    started_tick)
  if not finished then return failure end
  return { status = "complete", result = "grand_tree_start_verified", receipt = clicked }
end

local function object(id, action, within)
  return gc.read("objects", {
    id = id,
    action = action,
    within = within or 16,
    limit = 5,
  })[1]
end

local function climb_hazelmere()
  local world = gc.read("player").world
  if world.plane == 1 and math.max(
    math.abs(world.x - config.points.hazelmere_upstairs.x),
    math.abs(world.y - config.points.hazelmere_upstairs.y)) <= 8 then
    return { status = "complete", result = "hazelmere_ladder_already_climbed" }
  end
  local ladder = object(config.objects.hazelmere_ladder, "Climb-up", 12)
  if not ladder then
    return {
      status = "hazelmere_ladder_not_observed",
      objects = gc.read("objects", { within = 12, limit = 40 }),
    }
  end
  local climbed = gc.await {
    action = {
      type = "object.interact",
      id = ladder.id,
      action = "Climb-up",
      world = ladder.world,
      within = 12,
    },
    breaks = true,
    timeout = { game_ticks = 30 },
  }
  if climbed.status ~= "dispatched" then return climbed end
  for _ = 1, 30 do
    gc.await { event = "game.tick" }
    if gc.read("player").world.plane == 1 then
      return { status = "complete", result = "hazelmere_ladder_climbed", receipt = climbed }
    end
  end
  return { status = "hazelmere_ladder_unverified", receipt = climbed }
end

local function talk_hazelmere()
  local started_tick = gc.read("runtime").game_tick
  local clicked = { status = "dispatched", result = "resumed_open_dialogue" }
  if not gc.read("dialogue").open then
    local target = npc(config.npcs.hazelmere, 16, true)
    if not target then
      return {
        status = "hazelmere_not_reachable",
        nearby = gc.read("npcs", { within = 20, limit = 30 }),
      }
    end
    clicked = gc.await {
      action = { type = "npc.interact", id = target.id, action = "Talk-to", within = 16 },
      breaks = true,
      timeout = { game_ticks = 40 },
    }
    if clicked.status ~= "dispatched" then return clicked end
  end
  local finished, failure = finish_dialogue(
    function()
      local vars = gc.read("vars", { varps = { config.varp } })
      return vars.varps[config.varp] >= 20
    end,
    {},
    started_tick)
  if not finished then return failure end
  return { status = "complete", result = "hazelmere_translation_verified", receipt = clicked }
end

local TRANSLATION_CHOICES = {
  "I think so!",
  "A man came to me with the King's seal.",
  "I gave the man Daconia rocks.",
  "And Daconia rocks will kill the tree!",
}

local function choose_translation(dialogue)
  for _, wanted in ipairs(TRANSLATION_CHOICES) do
    for _, option in ipairs(dialogue.options or {}) do
      if option.text == wanted then
        return gc.await {
          action = { type = "dialogue.choose", text = wanted },
          breaks = false,
          timeout = { game_ticks = 20 },
        }
      end
    end
  end
  for _, option in ipairs(dialogue.options or {}) do
    if option.text == "None of the above." then
      return gc.await {
        action = { type = "dialogue.choose", text = option.text },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
    end
  end
  return { status = "rejected", result = "translation_choice_missing", dialogue = dialogue }
end

local function talk_narnode_translation()
  local target = npc(config.npcs.king_narnode, 20, true)
  if not target then
    return { status = "king_narnode_not_reachable_for_translation" }
  end
  local started_tick = gc.read("runtime").game_tick
  local clicked = gc.await {
    action = { type = "npc.interact", id = target.id, action = "Talk-to", within = 20 },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if clicked.status ~= "dispatched" then return clicked end
  local progressed = false
  local closed_ticks = 0
  for _ = 1, 160 do
    gc.await { event = "game.tick" }
    local vars = gc.read("vars", { varps = { config.varp } })
    progressed = progressed or vars.varps[config.varp] >= 30
    for _, message in ipairs(gc.read("messages", { since_tick = started_tick, limit = 10 })) do
      if string.find(string.lower(message.text or ""), "can't reach that", 1, true) then
        return { status = "king_narnode_unreachable_for_translation", message = message }
      end
    end
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      closed_ticks = 0
      local receipt = gc.await {
        action = { type = "dialogue.continue" },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
      if receipt.status ~= "dispatched" then return receipt end
    elseif dialogue.type == "choice" then
      closed_ticks = 0
      local receipt = choose_translation(dialogue)
      if receipt.status ~= "dispatched" then return receipt end
    elseif progressed then
      closed_ticks = closed_ticks + 1
      if closed_ticks >= 2 then
        return { status = "complete", result = "narnode_translation_verified", receipt = clicked }
      end
    end
  end
  return {
    status = "narnode_translation_timeout",
    varp = gc.read("vars", { varps = { config.varp } }),
    dialogue = gc.read("dialogue"),
  }
end

local function climb_glough()
  if gc.read("player").world.plane == 1 then
    return { status = "complete", result = "glough_ladder_already_climbed" }
  end
  local ladder = object(config.objects.glough_ladder, "Climb-up", 12)
  if not ladder then
    return {
      status = "glough_ladder_not_observed",
      objects = gc.read("objects", { within = 12, limit = 40 }),
    }
  end
  local climbed = gc.await {
    action = {
      type = "object.interact",
      id = ladder.id,
      action = "Climb-up",
      world = ladder.world,
      within = 12,
    },
    breaks = true,
    timeout = { game_ticks = 30 },
  }
  if climbed.status ~= "dispatched" then return climbed end
  for _ = 1, 30 do
    gc.await { event = "game.tick" }
    if gc.read("player").world.plane == 1 then
      return { status = "complete", result = "glough_ladder_climbed", receipt = climbed }
    end
  end
  return { status = "glough_ladder_unverified", receipt = climbed }
end

local function talk_glough()
  local target = npc(config.npcs.glough, 16, true)
  if not target then return { status = "glough_not_reachable" } end
  local started_tick = gc.read("runtime").game_tick
  local clicked = gc.await {
    action = { type = "npc.interact", id = target.id, action = "Talk-to", within = 16 },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if clicked.status ~= "dispatched" then return clicked end
  local finished, failure = finish_dialogue(
    function()
      local vars = gc.read("vars", { varps = { config.varp } })
      return vars.varps[config.varp] >= 40
    end,
    {},
    started_tick)
  if not finished then return failure end
  return { status = "complete", result = "glough_warning_verified", receipt = clicked }
end

return {
  npc = npc,
  talk_narnode = talk_narnode,
  climb_hazelmere = climb_hazelmere,
  talk_hazelmere = talk_hazelmere,
  talk_narnode_translation = talk_narnode_translation,
  climb_glough = climb_glough,
  talk_glough = talk_glough,
}
