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

local function descend_glough()
  if gc.read("player").world.plane == 0 then
    return { status = "complete", result = "glough_ladder_already_descended" }
  end
  local ladder = object(config.objects.glough_ladder_down, "Climb-down", 12)
  if not ladder then
    return {
      status = "glough_descent_not_observed",
      objects = gc.read("objects", { within = 12, limit = 40 }),
    }
  end
  local descended = gc.await {
    action = {
      type = "object.interact",
      id = ladder.id,
      action = "Climb-down",
      world = ladder.world,
      within = 12,
    },
    breaks = true,
    timeout = { game_ticks = 30 },
  }
  if descended.status ~= "dispatched" then return descended end
  for _ = 1, 30 do
    gc.await { event = "game.tick" }
    if gc.read("player").world.plane == 0 then
      return { status = "complete", result = "glough_ladder_descended", receipt = descended }
    end
  end
  return { status = "glough_descent_unverified", receipt = descended }
end

local function talk_narnode_after_glough()
  local target = npc(config.npcs.king_narnode, 20, true)
  if not target then return { status = "king_narnode_not_reachable_after_glough" } end
  local started_tick = gc.read("runtime").game_tick
  local clicked = gc.await {
    action = { type = "npc.interact", id = target.id, action = "Talk-to", within = 20 },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if clicked.status ~= "dispatched" then return clicked end
  local finished, failure = finish_dialogue(
    function()
      local vars = gc.read("vars", { varps = { config.varp } })
      return vars.varps[config.varp] >= 50
    end,
    {},
    started_tick)
  if not finished then return failure end
  return { status = "complete", result = "narnode_after_glough_verified", receipt = clicked }
end

local function climb_grand_tree_top()
  if gc.read("player").world.plane == 3 then
    return { status = "complete", result = "grand_tree_top_already_reached" }
  end
  local ladder = object(config.objects.grand_tree_ladder, "Top-Floor", 12)
  if not ladder then
    return {
      status = "grand_tree_top_ladder_not_observed",
      player = gc.read("player"),
      objects = gc.read("objects", { within = 12, limit = 40 }),
    }
  end
  local climbed = gc.await {
    action = {
      type = "object.interact",
      id = ladder.id,
      action = "Top-Floor",
      world = ladder.world,
      within = 12,
    },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if climbed.status ~= "dispatched" then return climbed end
  for _ = 1, 40 do
    gc.await { event = "game.tick" }
    if gc.read("player").world.plane == 3 then
      return { status = "complete", result = "grand_tree_top_reached", receipt = climbed }
    end
  end
  return { status = "grand_tree_top_unverified", receipt = climbed }
end

local function talk_charlie()
  local target = npc(config.npcs.charlie, 20, true)
  if not target then return { status = "charlie_not_reachable" } end
  local started_tick = gc.read("runtime").game_tick
  local clicked = gc.await {
    action = { type = "npc.interact", id = target.id, action = "Talk-to", within = 20 },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if clicked.status ~= "dispatched" then return clicked end
  local finished, failure = finish_dialogue(
    function()
      local vars = gc.read("vars", { varps = { config.varp } })
      return vars.varps[config.varp] >= 60
    end,
    {},
    started_tick)
  if not finished then return failure end
  return { status = "complete", result = "charlie_investigation_verified", receipt = clicked }
end

local function descend_grand_tree_bottom()
  if gc.read("player").world.plane == 0 then
    return { status = "complete", result = "grand_tree_bottom_already_reached" }
  end
  local ladder = object(config.objects.grand_tree_ladder_top, "Bottom-Floor", 12)
  if not ladder then
    return {
      status = "grand_tree_bottom_ladder_not_observed",
      player = gc.read("player"),
      objects = gc.read("objects", { within = 12, limit = 40 }),
    }
  end
  local descended = gc.await {
    action = {
      type = "object.interact",
      id = ladder.id,
      action = "Bottom-Floor",
      world = ladder.world,
      within = 12,
    },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if descended.status ~= "dispatched" then return descended end
  for _ = 1, 40 do
    gc.await { event = "game.tick" }
    if gc.read("player").world.plane == 0 then
      return { status = "complete", result = "grand_tree_bottom_reached", receipt = descended }
    end
  end
  return { status = "grand_tree_bottom_unverified", receipt = descended }
end

local function has_inventory_item(id)
  for _, item in ipairs(gc.read("inventory").items or {}) do
    if item.id == id and item.quantity > 0 then return true end
  end
  return false
end

local function search_glough_cupboard()
  if has_inventory_item(config.items.glough_journal) then
    return { status = "complete", result = "glough_journal_already_owned" }
  end
  local cupboard = object(config.objects.glough_cupboard_open, "Search", 12)
  local closed = object(config.objects.glough_cupboard_closed, "Open", 12)
  if not cupboard and closed then
    local opened = gc.await {
      action = {
        type = "object.interact",
        id = closed.id,
        action = "Open",
        world = closed.world,
        within = 12,
      },
      breaks = true,
      timeout = { game_ticks = 30 },
    }
    if opened.status ~= "dispatched" then return opened end
    for _ = 1, 20 do
      gc.await { event = "game.tick" }
      cupboard = object(config.objects.glough_cupboard_open, "Search", 12)
      if cupboard then break end
    end
  end
  if not cupboard then
    return {
      status = "glough_cupboard_not_observed",
      objects = gc.read("objects", { within = 12, limit = 50 }),
    }
  end
  local searched = gc.await {
    action = {
      type = "object.interact",
      id = cupboard.id,
      action = "Search",
      world = cupboard.world,
      within = 12,
    },
    breaks = true,
    timeout = { game_ticks = 30 },
  }
  if searched.status ~= "dispatched" then return searched end
  for _ = 1, 30 do
    gc.await { event = "game.tick" }
    if has_inventory_item(config.items.glough_journal) then
      return { status = "complete", result = "glough_journal_obtained", receipt = searched }
    end
  end
  return { status = "glough_journal_unverified", receipt = searched }
end

local function talk_glough_again()
  local started_tick = gc.read("runtime").game_tick
  local clicked = { status = "dispatched", result = "resumed_open_dialogue" }
  if not gc.read("dialogue").open then
    local target = npc(config.npcs.glough, 16, true)
    if not target then return { status = "glough_not_reachable_for_confrontation" } end
    clicked = gc.await {
      action = { type = "npc.interact", id = target.id, action = "Talk-to", within = 16 },
      breaks = false,
      timeout = { game_ticks = 40 },
    }
    if clicked.status ~= "dispatched" then return clicked end
  end
  local finished, failure = finish_dialogue(
    function()
      local world = gc.read("player").world
      return world.plane == 3 and world.x == 2464 and world.y == 3496
    end,
    {},
    started_tick)
  if not finished then return failure end
  return { status = "complete", result = "glough_confrontation_verified", receipt = clicked }
end

local function talk_charlie_from_cell()
  local started_tick = gc.read("runtime").game_tick
  local clicked = { status = "dispatched", result = "resumed_open_dialogue" }
  if not gc.read("dialogue").open then
    local target = npc(config.npcs.charlie, 10, true)
    if not target then return { status = "charlie_not_reachable_from_cell" } end
    clicked = gc.await {
      action = { type = "npc.interact", id = target.id, action = "Talk-to", within = 10 },
      breaks = false,
      timeout = { game_ticks = 40 },
    }
    if clicked.status ~= "dispatched" then return clicked end
  end
  local finished, failure = finish_dialogue(
    function()
      local world = gc.read("player").world
      return not (world.plane == 3 and world.x == 2464 and world.y == 3496)
    end,
    {},
    started_tick)
  if not finished then return failure end
  return { status = "complete", result = "charlie_cell_dialogue_verified", receipt = clicked }
end

local function take_glider_to_karamja()
  local target = npc(config.npcs.captain_errdo, 12, true)
  if not target then return { status = "captain_errdo_not_reachable" } end
  local started_tick = gc.read("runtime").game_tick
  local clicked = gc.await {
    action = { type = "npc.interact", id = target.id, action = "Talk-to", within = 12 },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if clicked.status ~= "dispatched" then return clicked end
  local finished, failure = finish_dialogue(
    function()
      local world = gc.read("player").world
      return world.plane == 0 and world.x >= 2900 and world.x <= 3010 and
        world.y >= 2950 and world.y <= 3070
    end,
    { "Take me to Karamja please!" },
    started_tick)
  if not finished then return failure end
  return { status = "complete", result = "karamja_glider_verified", receipt = clicked }
end

return {
  npc = npc,
  talk_narnode = talk_narnode,
  climb_hazelmere = climb_hazelmere,
  talk_hazelmere = talk_hazelmere,
  talk_narnode_translation = talk_narnode_translation,
  climb_glough = climb_glough,
  talk_glough = talk_glough,
  descend_glough = descend_glough,
  talk_narnode_after_glough = talk_narnode_after_glough,
  climb_grand_tree_top = climb_grand_tree_top,
  talk_charlie = talk_charlie,
  descend_grand_tree_bottom = descend_grand_tree_bottom,
  search_glough_cupboard = search_glough_cupboard,
  talk_glough_again = talk_glough_again,
  talk_charlie_from_cell = talk_charlie_from_cell,
  take_glider_to_karamja = take_glider_to_karamja,
}
