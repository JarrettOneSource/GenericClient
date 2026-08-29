local NPC_ID = 6744
local ACCEPT_GAME = "Yes, pinball is fun."
local EXIT_ID = 9293

-- RuneLite gamevals: MACRO_PINBALL_CURRENT/NEXT/SCORE/COMPLETE.
local VARBITS = {
  current = 2119,
  next = 2120,
  score = 2121,
  complete = 2122,
}

-- The current varbit selects one of these five post families.
local POSTS = {
  [0] = 8982, -- tree
  [1] = 8984, -- iron
  [2] = 9079, -- coal
  [3] = 9081, -- fishing
  [4] = 9258, -- essence
}

local function fail(status, details)
  local value = details or {}
  value.status = status
  gc.log("error", "pinball-failed", value)
  error(status, 0)
end

local function object(id, action)
  local found = gc.read("objects", {
    id = id,
    action = action,
    within = 24,
    limit = 3,
  })
  return found[1]
end

local function in_arena()
  return object(EXIT_ID, "Exit") ~= nil
end

local function choose_game(dialogue)
  for _, option in ipairs(dialogue.options) do
    if option.text == ACCEPT_GAME then
      return gc.await {
        action = { type = "dialogue.choose", text = option.text },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
    end
  end
  fail("unexpected_dialogue_choice", { dialogue = dialogue })
end

local function enter_arena()
  if in_arena() then return end

  local talked = gc.await {
    action = { type = "npc.interact", id = NPC_ID, action = "Talk-to", within = 12 },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if talked.status ~= "dispatched" then
    fail("talk_failed", { receipt = talked })
  end

  for _ = 1, 60 do
    if in_arena() then return end
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      local continued = gc.await {
        action = { type = "dialogue.continue" },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
      if continued.status ~= "dispatched" then
        fail("dialogue_continue_failed", { receipt = continued })
      end
    elseif dialogue.type == "choice" then
      local chosen = choose_game(dialogue)
      if chosen.status ~= "dispatched" then
        fail("dialogue_choice_failed", { receipt = chosen })
      end
    else
      gc.await { event = "game.tick" }
    end
  end

  fail("arena_not_reached", { event = gc.read("random_event") })
end

local function state()
  local values = gc.read("vars", {
    varbits = {
      VARBITS.current,
      VARBITS.next,
      VARBITS.score,
      VARBITS.complete,
    },
  }).varbits
  return {
    current = values[VARBITS.current],
    next = values[VARBITS.next],
    score = values[VARBITS.score],
    complete = values[VARBITS.complete],
  }
end

local function tag_current()
  local before = state()
  local post_id = POSTS[before.current]
  if not post_id then
    fail("unknown_post_code", { pinball = before })
  end
  local target = object(post_id, "Tag")
  if not target then
    fail("post_not_observed", { post_id = post_id, pinball = before })
  end

  local tagged = gc.await {
    action = {
      type = "object.interact",
      id = post_id,
      action = "Tag",
      world = target.world,
      within = 24,
    },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if tagged.status ~= "dispatched" then
    fail("tag_failed", { receipt = tagged, pinball = before })
  end

  for _ = 1, 24 do
    gc.await { event = "game.tick" }
    local after = state()
    if after.complete == 1 or after.score == before.score + 1 then
      return after
    end
    if after.score < before.score then
      fail("score_reset", { before = before, after = after, post_id = post_id })
    end
  end
  fail("score_unchanged", { before = before, after = state(), post_id = post_id })
end

local function reward_message(since_tick)
  for _, message in ipairs(gc.read("messages", { since_tick = since_tick, limit = 30 })) do
    local text = string.lower(message.text or "")
    if string.find(text, "your reward is:", 1, true) or
      string.find(text, "you were awarded", 1, true) then
      return message
    end
  end
  return nil
end

local function leave_arena()
  local exit = object(EXIT_ID, "Exit")
  if not exit then fail("exit_not_observed") end
  local started_tick = gc.read("runtime").game_tick
  local left = gc.await {
    action = {
      type = "object.interact",
      id = EXIT_ID,
      action = "Exit",
      world = exit.world,
      within = 24,
    },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if left.status ~= "dispatched" then
    fail("exit_failed", { receipt = left })
  end

  for _ = 1, 40 do
    gc.await { event = "game.tick" }
    local reward = reward_message(started_tick)
    if reward then return reward end
  end
  fail("reward_not_observed", { messages = gc.read("messages", { limit = 20 }) })
end

return {
  run = function()
    local event = gc.read("random_event")
    if not event.active or event.npc_id ~= NPC_ID then
      error("Pinball solver started without its owned event")
    end

    enter_arena()
    local pinball = state()
    for _ = 1, 10 do
      if pinball.complete == 1 or pinball.score >= 10 then break end
      pinball = tag_current()
    end
    if pinball.complete ~= 1 or pinball.score < 10 then
      fail("game_not_complete", { pinball = pinball })
    end

    local reward = leave_arena()
    return { status = "solved", score = pinball.score, reward = reward }
  end,
}
