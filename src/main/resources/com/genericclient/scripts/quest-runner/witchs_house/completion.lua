local travel = gc.require("shared_travel")

local function quantity(id)
  local total = 0
  for _, item in ipairs(gc.read("inventory").items) do
    if item.id == id then total = total + item.quantity end
  end
  return total
end

local function in_hostile_quest_area(world)
  return world and world.plane == 0 and world.x >= 2900 and world.x <= 2937 and
    world.y >= 3459 and world.y <= 3475
end

local function take_ball()
  if quantity(2407) > 0 then return { status = "complete", result = "ball_already_carried" } end
  local take = gc.await {
    action = {
      type = "ground_item.take",
      id = 2407,
      world = { x = 2935, y = 3460, plane = 0 },
      within = 10,
    },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if take.status ~= "dispatched" then return { status = "ball_take_failed", receipt = take } end
  for _ = 1, 12 do
    gc.await { event = "game.tick" }
    if quantity(2407) > 0 then return { status = "complete", result = "ball_obtained", receipt = take } end
  end
  return { status = "ball_take_unverified", receipt = take }
end

local function return_to_boy()
  local walked = gc.await {
    action = {
      type = "walk.to",
      destination = { x = 2927, y = 3455, plane = 0 },
      within = 3,
      run = true,
    },
    breaks = false,
    timeout = { game_ticks = 900 },
  }
  if walked.status ~= "arrived" then return { status = "boy_travel_failed", receipt = walked } end
  local talked = gc.await {
    action = { type = "npc.interact", id = 3994, action = "Talk-to", within = 10 },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if talked.status ~= "dispatched" then return { status = "boy_talk_failed", receipt = talked } end
  local continuations = {}
  for _ = 1, 80 do
    gc.await { event = "game.tick" }
    local vars = gc.read("vars", { varps = { 226 } })
    local quest = gc.read("quests").witchs_house
    if vars.varps[226] == 7 or quest.state == "finished" then
      return {
        status = "complete",
        result = "witchs_house_complete",
        varp = vars.varps[226],
        quest = quest,
        walk = walked,
        talk = talked,
        continuations = continuations,
      }
    end
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      local receipt = gc.await {
        action = { type = "dialogue.continue" },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
      table.insert(continuations, receipt)
      if receipt.status ~= "dispatched" then
        return { status = "boy_dialogue_failed", receipt = receipt, dialogue = dialogue }
      end
    elseif dialogue.type == "choice" then
      return { status = "unexpected_boy_choice", dialogue = dialogue }
    end
  end
  return {
    status = "quest_completion_timeout",
    vars = gc.read("vars", { varps = { 226 } }),
    quest = gc.read("quests").witchs_house,
    dialogue = gc.read("dialogue"),
  }
end

local function execute()
  local vars = gc.read("vars", { varps = { 226 } })
  local quest = gc.read("quests").witchs_house
  if vars.varps[226] == 7 or quest.state == "finished" then
    return { status = "complete", result = "witchs_house_already_complete", varp = vars.varps[226] }
  end
  if vars.varps[226] ~= 6 then
    return { status = "experiment_not_complete", varp = vars.varps[226] }
  end
  local ball = take_ball()
  if ball.status ~= "complete" then return ball end
  local teleport
  if in_hostile_quest_area(gc.read("player").world) then
    teleport = travel.teleport_to_burthorpe()
    if teleport.status ~= "complete" then return teleport end
    if quantity(2407) == 0 then
      return { status = "ball_lost_during_teleport", teleport = teleport }
    end
  end
  local completed = return_to_boy()
  completed.ball = ball
  completed.teleport = teleport
  if completed.status == "complete" then
    gc.await { action = { type = "safety.clear" }, breaks = false }
    gc.await { action = { type = "mouse.offscreen" }, breaks = false }
  end
  return completed
end

return { execute = execute }
