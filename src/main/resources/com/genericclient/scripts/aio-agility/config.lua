local function xp_for_level(level)
  local points = 0
  for current = 1, level - 1 do
    points = points + math.floor(current + 300 * 2 ^ (current / 7))
  end
  return math.floor(points / 4)
end

local target_xp = {}
for _, level in ipairs({ 10, 20, 25 }) do
  target_xp[tostring(level)] = xp_for_level(level)
end

return {
  xp_for_level = xp_for_level,
  target_xp = target_xp,
  course = {
    id = "gnome_stronghold",
    label = "Gnome Stronghold",
    zone = { x1 = 2455, y1 = 3400, x2 = 2500, y2 = 3450 },
    arrival = { x = 2470, y = 3420, plane = 0 },
    gate = { id = 190, world = { x = 2461, y = 3383, plane = 0 } },
    route = {
      { x = 2545, y = 3260, plane = 0 },
      { x = 2580, y = 3260, plane = 0 },
      { x = 2580, y = 3310, plane = 0 },
      { x = 2580, y = 3355, plane = 0 },
      { x = 2530, y = 3370, plane = 0 },
      { x = 2480, y = 3375, plane = 0 },
      { x = 2461, y = 3379, plane = 0 },
    },
    inside_route = {
      { x = 2461, y = 3400, plane = 0 },
      { x = 2470, y = 3420, plane = 0 },
    },
    obstacles = {
      log = {
        label = "Log balance",
        id = 23145,
        action = "Walk-across",
        world = { x = 2474, y = 3435, plane = 0 },
        approach = { x = 2475, y = 3437, plane = 0 },
        approach_within = 0,
      },
      net_up = {
        label = "South net",
        id = 23134,
        action = "Climb-over",
        world = { x = 2473, y = 3425, plane = 0 },
      },
      branch_up = {
        label = "Branch up",
        id = 23559,
        action = "Climb",
        world = { x = 2473, y = 3422, plane = 1 },
      },
      rope = {
        label = "Balancing rope",
        id = 23557,
        action = "Walk-on",
        world = { x = 2478, y = 3420, plane = 2 },
      },
      branch_down = {
        label = "Branch down",
        id = 23560,
        action = "Climb-down",
        world = { x = 2486, y = 3419, plane = 2 },
      },
      net_down = {
        label = "North net",
        id = 23135,
        action = "Climb-over",
        world = { x = 2487, y = 3426, plane = 0 },
      },
      pipe = {
        label = "Obstacle pipe",
        id = 23139,
        action = "Squeeze-through",
        world = { x = 2487, y = 3431, plane = 0 },
        completes_lap = true,
      },
    },
  },
}
