local config = gc.require("monkey_madness_config")

local PUZZLE_GROUP = 306

local function puzzle_widgets()
  return gc.read("widgets", { group = PUZZLE_GROUP, limit = 100 })
end

local function blank_position()
  local occupied = {}
  local count = 0
  for _, widget in ipairs(puzzle_widgets()) do
    if widget.name == "Sliding piece" and widget.index >= 0 and widget.index < 25 then
      occupied[widget.index] = true
      count = count + 1
    end
  end
  if count == 0 then return nil, "closed" end
  if count ~= 24 then return nil, "invalid" end
  for index = 0, 24 do
    if not occupied[index] then return index end
  end
  return nil, "invalid"
end

local function wait_for_completion(ticks)
  for _ = 1, ticks do
    gc.await { event = "game.tick" }
    local vars = gc.read("vars", { varbits = { config.varbits.daero } })
    if vars.varbits[config.varbits.daero] >= 6 then return true end
  end
  return false
end

local function open_panel()
  if gc.read("sliding_puzzle").available then
    return { status = "complete", result = "puzzle_already_open" }
  end
  gc.activity("questing")
  local reached = gc.await {
    action = {
      type = "walk.to",
      destination = config.points.reinitialization_panel,
      within = 3,
      run = true,
    },
    breaks = true,
    timeout = { game_ticks = 120 },
  }
  if reached.status ~= "arrived" then return reached end
  local panel = gc.read("objects", {
    id = config.objects.reinitialization_panel,
    action = "Operate",
    within = 10,
    limit = 1,
  })[1]
  if not panel then
    return {
      status = "monkey_madness_reinitialization_panel_not_observed",
      objects = gc.read("objects", { within = 10, limit = 50 }),
    }
  end
  local operated = gc.await {
    action = {
      type = "object.interact",
      id = panel.id,
      action = "Operate",
      world = panel.world,
      within = 10,
    },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if operated.status ~= "dispatched" then return operated end
  for _ = 1, 20 do
    gc.await { event = "game.tick" }
    if gc.read("sliding_puzzle").available then
      return { status = "complete", result = "reinitialization_puzzle_opened", receipt = operated }
    end
  end
  return { status = "monkey_madness_reinitialization_puzzle_not_observed", receipt = operated }
end

local function solve()
  local opened = open_panel()
  if opened.status ~= "complete" then return opened end
  local puzzle = gc.read("sliding_puzzle")
  if not puzzle.available or #puzzle.board ~= 25 or #puzzle.moves > 400 then
    return { status = "monkey_madness_sliding_puzzle_invalid", puzzle = puzzle }
  end
  gc.state("puzzle.0.of." .. tostring(#puzzle.moves))
  local completed_moves = 0
  for step, position in ipairs(puzzle.moves) do
    gc.state("puzzle." .. tostring(step) .. ".of." .. tostring(#puzzle.moves))
    local clicked = gc.await {
      action = {
        type = "ui.click",
        widget_id = puzzle.widget_id,
        widget_index = position,
      },
      breaks = true,
      timeout = { game_ticks = 30 },
    }
    if clicked.status ~= "dispatched" then
      return {
        status = "monkey_madness_sliding_puzzle_click_failed",
        step = step,
        position = position,
        receipt = clicked,
      }
    end
    completed_moves = step
    local moved = false
    for _ = 1, 10 do
      gc.await { event = "game.tick" }
      local blank, state = blank_position()
      if blank == position then
        moved = true
        break
      end
      if state == "closed" then
        if wait_for_completion(40) then
          return {
            status = "complete",
            result = "reinitialization_puzzle_solved",
            moves = step,
            planned_moves = #puzzle.moves,
          }
        end
        return {
          status = "monkey_madness_reinitialization_completion_unverified",
          moves = step,
          planned_moves = #puzzle.moves,
          vars = gc.read("vars", { varbits = { config.varbits.daero } }),
        }
      end
    end
    if not moved then
      return {
        status = "monkey_madness_sliding_puzzle_move_unverified",
        step = step,
        position = position,
      }
    end
  end
  if wait_for_completion(30) then
    return {
      status = "complete",
      result = "reinitialization_puzzle_solved",
      moves = completed_moves,
      planned_moves = #puzzle.moves,
    }
  end
  return {
    status = "monkey_madness_reinitialization_completion_unverified",
    moves = completed_moves,
    vars = gc.read("vars", { varbits = { config.varbits.daero } }),
  }
end

return { solve = solve }
