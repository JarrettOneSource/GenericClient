local zones = {
  village = { x1 = 2514, y1 = 3158, x2 = 2542, y2 = 3175, plane = 0 },
  tower_ground = { x1 = 2500, y1 = 3253, x2 = 2507, y2 = 3260, plane = 0 },
  tower_upstairs = { x1 = 2500, y1 = 3251, x2 = 2506, y2 = 3259, plane = 1 },
}

local points = {
  maze_outside = { x = 2505, y = 3190, plane = 0 },
  maze_inside = { x = 2515, y = 3159, plane = 0 },
  king_bolren = { x = 2541, y = 3170, plane = 0 },
  commander_montai = { x = 2523, y = 3208, plane = 0 },
  tracker_one = { x = 2501, y = 3261, plane = 0 },
  tracker_two = { x = 2524, y = 3257, plane = 0 },
  tracker_three = { x = 2497, y = 3234, plane = 0 },
  ballista = { x = 2509, y = 3211, plane = 0 },
  crumbled_wall = { x = 2503, y = 3252, plane = 0 },
  tower_chest = { x = 2506, y = 3259, plane = 1 },
  elkoy = { x = 2505, y = 3191, plane = 0 },
  tower_exit = { x = 2503, y = 3248, plane = 0 },
  warlord = { x = 2456, y = 3301, plane = 0 },
  warlord_reset = { x = 2447, y = 3302, plane = 0 },
  warlord_cast = { x = 2447, y = 3297, plane = 0 },
}

local npcs = {
  king_bolren = 4963,
  commander_montai = 4964,
  elkoy_outside = 4968,
  tracker_one = 4975,
  tracker_two = 4976,
  tracker_three = 4977,
  warlord_chat = 7621,
  warlord_combat = 7622,
}

local objects = {
  ballista = 2181,
  khazard_door = 2184,
  crumbled_wall = 2926,
  tower_ladder = 16683,
  chest_open = 2182,
  chest_closed = 2183,
}

local items = {
  logs = 1511,
  staff_of_air = 1381,
  earth_rune = 557,
  chaos_rune = 562,
  wine = 1993,
  ring_of_dueling = 2552,
  first_orb = 587,
  remaining_orbs = 588,
  gnome_amulet = 589,
}

local ring = {
  id = items.ring_of_dueling,
  name = "Ring of dueling",
  quantity = 1,
  maximum_unit_price = 2000,
  alternative_ids = { 2554, 2556, 2558, 2560, 2562, 2564, 2566 },
}

local combat_loadout = {
  { id = items.staff_of_air, name = "Staff of air", quantity = 1, maximum_unit_price = 2000 },
  { id = items.chaos_rune, name = "Chaos rune", quantity = 100, maximum_unit_price = 500 },
  { id = items.earth_rune, name = "Earth rune", quantity = 300, maximum_unit_price = 100 },
  ring,
  { id = items.wine, name = "Jug of wine", quantity = 8, maximum_unit_price = 100 },
}

local combat_minimum = {
  { id = items.staff_of_air, name = "Staff of air", quantity = 1, maximum_unit_price = 2000 },
  { id = items.chaos_rune, name = "Chaos rune", quantity = 40, maximum_unit_price = 500 },
  { id = items.earth_rune, name = "Earth rune", quantity = 120, maximum_unit_price = 100 },
  { id = items.wine, name = "Jug of wine", quantity = 3, maximum_unit_price = 100 },
}

local initial_loadout = {
  { id = items.logs, name = "Logs", quantity = 6, maximum_unit_price = 500 },
}
for _, item in ipairs(combat_loadout) do initial_loadout[#initial_loadout + 1] = item end

local maze_route = {
  { x = 2505, y = 3190, plane = 0 },
  { x = 2512, y = 3190, plane = 0 },
  { x = 2512, y = 3188, plane = 0 },
  { x = 2532, y = 3188, plane = 0 },
  { x = 2532, y = 3182, plane = 0 },
  { x = 2523, y = 3181, plane = 0 },
  { x = 2523, y = 3185, plane = 0 },
  { x = 2521, y = 3185, plane = 0 },
  { x = 2520, y = 3179, plane = 0 },
  { x = 2514, y = 3179, plane = 0 },
  { x = 2514, y = 3177, plane = 0 },
  { x = 2527, y = 3177, plane = 0 },
  { x = 2527, y = 3179, plane = 0 },
  { x = 2529, y = 3179, plane = 0 },
  { x = 2529, y = 3177, plane = 0 },
  { x = 2531, y = 3177, plane = 0 },
  { x = 2531, y = 3179, plane = 0 },
  { x = 2533, y = 3179, plane = 0 },
  { x = 2533, y = 3177, plane = 0 },
  { x = 2544, y = 3177, plane = 0 },
  { x = 2544, y = 3174, plane = 0 },
  { x = 2549, y = 3174, plane = 0 },
  { x = 2549, y = 3165, plane = 0 },
  { x = 2545, y = 3165, plane = 0 },
  { x = 2545, y = 3159, plane = 0 },
  { x = 2550, y = 3159, plane = 0 },
  { x = 2550, y = 3156, plane = 0 },
  { x = 2548, y = 3156, plane = 0 },
  { x = 2548, y = 3145, plane = 0 },
  { x = 2538, y = 3145, plane = 0 },
  { x = 2538, y = 3150, plane = 0 },
  { x = 2541, y = 3150, plane = 0 },
  { x = 2541, y = 3148, plane = 0 },
  { x = 2544, y = 3148, plane = 0 },
  { x = 2544, y = 3150, plane = 0 },
  { x = 2545, y = 3150, plane = 0 },
  { x = 2545, y = 3156, plane = 0 },
  { x = 2520, y = 3156, plane = 0 },
  { x = 2520, y = 3159, plane = 0 },
  { x = 2515, y = 3159, plane = 0 },
}

return {
  id = "tree_gnome_village",
  label = "Tree Gnome Village",
  zones = zones,
  points = points,
  npcs = npcs,
  objects = objects,
  items = items,
  varbits = {
    bolren_got_orbs = 598,
    tracker_height = 599,
    tracker_y = 600,
    tracker_x = 601,
    ballista = 602,
  },
  initial_loadout = initial_loadout,
  combat_loadout = combat_loadout,
  combat_minimum = combat_minimum,
  loadout = initial_loadout,
  maze_route = maze_route,
}
