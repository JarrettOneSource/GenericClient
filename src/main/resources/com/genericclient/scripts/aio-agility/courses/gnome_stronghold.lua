local config = gc.require("config")

local obstacles = config.course.obstacles

local function distance(a, b)
  if not a or a.plane ~= b.plane then return 99999 end
  return math.max(math.abs(a.x - b.x), math.abs(a.y - b.y))
end

local function resolve(player)
  local world = player.world
  if world.plane == 1 then return "branch_up" end
  if world.plane == 2 then
    return world.x < 2483 and "rope" or "branch_down"
  end
  if world.plane ~= 0 then return nil end
  if world.y >= 3434 then return "log" end
  if world.x <= 2470 and world.y <= 3424 then return "log" end
  if world.x <= 2479 and world.y <= 3432 then return "net_up" end
  if world.x >= 2482 and world.y <= 3423 then return "net_down" end
  if world.x >= 2482 and world.y >= 3427 then return "pipe" end
  return "log"
end

local function drain_level_dialogue()
  for _ = 1, 12 do
    local dialogue = gc.read("dialogue")
    if dialogue.type == "closed" then return true end
    if dialogue.type ~= "continue" then
      return nil, { status = "unexpected_agility_dialogue", dialogue = dialogue }
    end
    local receipt = gc.await {
      action = { type = "dialogue.continue" },
      breaks = false,
      timeout = { game_ticks = 20 },
    }
    if receipt.status ~= "dispatched" then return nil, receipt end
    gc.await { event = "game.tick" }
  end
  return nil, { status = "agility_dialogue_timeout", dialogue = gc.read("dialogue") }
end

local function settle()
  local still_ticks = 0
  for _ = 1, 20 do
    local player = gc.read("player")
    if player.animation == -1 then
      still_ticks = still_ticks + 1
      if still_ticks >= 2 then return true end
    else
      still_ticks = 0
    end
    gc.await { event = "game.tick" }
  end
  return false
end

local function perform(key)
  local obstacle = obstacles[key]
  if not obstacle then return nil, { status = "unknown_agility_obstacle", key = key } end
  local approach_within = obstacle.approach_within or 1
  if obstacle.approach and distance(gc.read("player").world, obstacle.approach) > approach_within then
    local approached = gc.await {
      action = {
        type = "walk.to",
        destination = obstacle.approach,
        within = approach_within,
        run = true,
      },
      breaks = true,
      timeout = { game_ticks = 120 },
    }
    if approached.status ~= "arrived" then
      return nil, { status = "agility_obstacle_approach_failed", key = key, receipt = approached }
    end
  end
  local before = gc.read("skills").agility.xp
  local target = gc.read("objects", {
    id = obstacle.id,
    action = obstacle.action,
    within = 24,
    limit = 1,
  })[1]
  if not target then
    return nil, {
      status = "agility_obstacle_not_observed",
      key = key,
      player = gc.read("player"),
    }
  end
  local receipt
  for attempt = 1, 3 do
    receipt = gc.await {
      action = {
        type = "object.interact",
        id = obstacle.id,
        action = obstacle.action,
        world = target.world,
        within = 24,
      },
      breaks = true,
      timeout = { game_ticks = 40 },
    }
    if receipt.status == "dispatched" then break end
    if receipt.result ~= "object_not_visible" and
      receipt.result ~= "hover_has_no_matching_action" then break end
    if attempt < 3 then gc.await { ticks = 3 } end
  end
  if receipt.status ~= "dispatched" then
    return nil, { status = "agility_obstacle_click_failed", key = key, receipt = receipt }
  end
  local after
  for _ = 1, 40 do
    gc.await { event = "game.tick" }
    after = gc.read("skills").agility
    if after.xp > before then break end
  end
  if not after or after.xp <= before then
    return nil, { status = "agility_xp_unverified", key = key, receipt = receipt }
  end
  local drained, failure = drain_level_dialogue()
  if not drained then return nil, failure end
  settle()
  return {
    status = "complete",
    key = key,
    gained = after.xp - before,
    completes_lap = obstacle.completes_lap == true,
    receipt = receipt,
  }
end

return { resolve = resolve, perform = perform }
