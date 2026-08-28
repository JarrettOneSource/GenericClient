local zones = {
  witch_house = { x1 = 2901, y1 = 3466, x2 = 2907, y2 = 3476, plane = 0 },
  witch_upper = { x1 = 2900, y1 = 3466, x2 = 2907, y2 = 3476, plane = 1 },
  basement_west = { x1 = 2897, y1 = 9870, x2 = 2902, y2 = 9878, plane = 0 },
  basement_east = { x1 = 2903, y1 = 9870, x2 = 2909, y2 = 9878, plane = 0 },
  garden = { x1 = 2900, y1 = 3459, x2 = 2933, y2 = 3467, plane = 0 },
  shed = { x1 = 2934, y1 = 3459, x2 = 2937, y2 = 3467, plane = 0 },
}

local witch_loadout = {
  { id = 1985, name = "Cheese", quantity = 2, maximum_unit_price = 100 },
  { id = 1059, name = "Leather gloves", quantity = 1, maximum_unit_price = 50 },
}

local witch_combat_loadout = {
  { id = 1387, name = "Staff of fire", quantity = 1, maximum_unit_price = 1200 },
  { id = 556, name = "Air rune", quantity = 300, maximum_unit_price = 10 },
  { id = 558, name = "Mind rune", quantity = 150, maximum_unit_price = 10 },
  { id = 2550, name = "Ring of recoil", quantity = 4, maximum_unit_price = 1000 },
  {
    id = 3853,
    name = "Games necklace",
    quantity = 1,
    maximum_unit_price = 1000,
    alternative_ids = { 3855, 3857, 3859, 3861, 3863, 3865, 3867 },
  },
  { id = 1993, name = "Jug of wine", quantity = 6, maximum_unit_price = 20 },
  { id = 2409, name = "Door key", quantity = 1, purchase = false },
}

local experiment = {
  name = "Witch's experiment",
  forms = { 3996, 3997, 3998, 3999 },
  door = { id = 2863, world = { x = 2934, y = 3463, plane = 0 } },
  spawn = { x = 2935, y = 3463, plane = 0 },
  ball = { id = 2407, world = { x = 2935, y = 3460, plane = 0 } },
  first_form_start = { x = 2937, y = 3466, plane = 0 },
  first_form_walk = { x = 2937, y = 3465, plane = 0 },
  first_form_safe = { x = 2936, y = 3465, plane = 0 },
  outside = { x = 2933, y = 3463, plane = 0 },
  south_safespot = { x = 2936, y = 3459, plane = 0 },
}

return {
  id = "witchs_house",
  label = "Witch's House",
  zones = zones,
  loadout = witch_loadout,
  combat_loadout = witch_combat_loadout,
  experiment = experiment,
}
