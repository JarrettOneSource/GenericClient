local config = gc.require("config")
local progress = gc.require("progress")
local travel = gc.require("travel")
local gnome = gc.require("gnome_course")

local function park_mouse()
  return gc.await { action = { type = "mouse.offscreen" }, breaks = false }
end

local function terminal(status, target, start_xp, obstacles, laps, extra)
  local agility = gc.read("skills").agility
  local result = {
    status = status,
    target_level = target,
    start_xp = start_xp,
    level = agility.level,
    xp = agility.xp,
    gained_xp = math.max(0, agility.xp - start_xp),
    obstacles = obstacles,
    laps = laps,
  }
  if extra then result.receipt = extra end
  park_mouse()
  return result
end

local function run(target, method)
  gc.await { event = "game.tick" }
  local required_xp = config.target_xp[tostring(target)]
  local start = gc.read("skills").agility
  progress.begin(start.xp)
  if start.xp >= required_xp or start.level >= target then
    progress.show(target, "Target met")
    return terminal("already_complete", target, start.xp, 0, 0)
  end

  progress.show(target, "Traveling")
  local arrived, failure = travel.ensure()
  if not arrived then
    return terminal("travel_failed", target, start.xp, 0, 0, failure)
  end
  gc.phase("agility." .. method .. ".arrived", { activity = "skilling" })
  gc.activity("skilling")

  local obstacle_count = 0
  local laps = 0
  local stop_requested = false
  while true do
    local agility = gc.read("skills").agility
    if agility.xp >= required_xp or agility.level >= target then
      progress.show(target, "Complete")
      return terminal("complete", target, start.xp, obstacle_count, laps)
    end
    if gc.next_action() == "stop_after_obstacle" then stop_requested = true end
    if stop_requested then
      progress.show(target, "Stopped")
      return terminal("stopped", target, start.xp, obstacle_count, laps)
    end

    local key = gnome.resolve(gc.read("player"))
    if not key then
      return terminal("course_state_unknown", target, start.xp, obstacle_count, laps,
        { player = gc.read("player") })
    end
    progress.show(target, config.course.obstacles[key].label)
    local receipt, action_failure = gnome.perform(key)
    if not receipt then
      return terminal("obstacle_failed", target, start.xp, obstacle_count, laps, action_failure)
    end
    obstacle_count = obstacle_count + 1
    if receipt.completes_lap then laps = laps + 1 end
  end
end

return { run = run }
