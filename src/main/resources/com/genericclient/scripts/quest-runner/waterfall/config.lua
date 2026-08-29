local zones = {
  gnome_basement = { x1 = 2497, y1 = 9552, x2 = 2559, y2 = 9593, plane = 0 },
  golrie_room = { x1 = 2502, y1 = 9576, x2 = 2523, y2 = 9593, plane = 0 },
  glarial_tomb = { x1 = 2524, y1 = 9801, x2 = 2557, y2 = 9849, plane = 0 },
  hudon_island = { x1 = 2510, y1 = 3476, x2 = 2515, y2 = 3482, plane = 0 },
  dead_tree_island = { x1 = 2512, y1 = 3465, x2 = 2513, y2 = 3475, plane = 0 },
  ledge = { x1 = 2510, y1 = 3462, x2 = 2513, y2 = 3464, plane = 0 },
  tourist_upstairs = { x1 = 2516, y1 = 3424, x2 = 2520, y2 = 3431, plane = 1 },
  falls = { x1 = 2556, y1 = 9861, x2 = 2595, y2 = 9920, plane = 0 },
  pillar_room = { x1 = 2561, y1 = 9902, x2 = 2570, y2 = 9917, plane = 0 },
  chalice_room = { x1 = 2599, y1 = 9890, x2 = 2608, y2 = 9916, plane = 0 },
}

local points = {
  almera = { x = 2521, y = 3495, plane = 0 },
  raft = { x = 2509, y = 3493, plane = 0 },
  hudon = { x = 2511, y = 3484, plane = 0 },
  crossing_rock_stand = { x = 2512, y = 3476, plane = 0 },
  crossing_rock = { x = 2512, y = 3468, plane = 0 },
  overhanging_tree = { x = 2512, y = 3465, plane = 0 },
  falls_entrance = { x = 2511, y = 3464, plane = 0 },
  barrel = { x = 2512, y = 3463, plane = 0 },
  tourist_stairs = { x = 2517, y = 3429, plane = 0 },
  tourist_stairs_top = { x = 2518, y = 3430, plane = 1 },
  bookcase = { x = 2520, y = 3426, plane = 1 },
  gnome_ladder = { x = 2533, y = 3155, plane = 0 },
  gnome_ladder_below = { x = 2533, y = 9555, plane = 0 },
  golrie_crate = { x = 2548, y = 9565, plane = 0 },
  golrie_gate = { x = 2515, y = 9575, plane = 0 },
  golrie = { x = 2514, y = 9580, plane = 0 },
  tombstone = { x = 2559, y = 3445, plane = 0 },
  tomb_exit = { x = 2556, y = 9844, plane = 0 },
  amulet_chest = { x = 2530, y = 9844, plane = 0 },
  urn_tomb = { x = 2542, y = 9812, plane = 0 },
  falls_crate = { x = 2589, y = 9888, plane = 0 },
  inner_door_staging = { x = 2568, y = 9898, plane = 0 },
  statue = { x = 2565, y = 9916, plane = 0 },
  chalice = { x = 2604, y = 9911, plane = 0 },
}

local objects = {
  raft = 1987,
  bookcase = 1989,
  golrie_crate = 1990,
  golrie_gate = 1991,
  tombstone = 1992,
  urn_tomb = 1993,
  amulet_chest_closed = 1994,
  amulet_chest_open = 1995,
  crossing_rock = 1996,
  falls_crate = 1999,
  inner_door_closed = 2002,
  inner_door_open = 2003,
  pillar = 2005,
  statue = 2006,
  falls_entrance = 2010,
  chalice = 2014,
  overhanging_tree = 2020,
  barrel = 2022,
  gnome_ladder = 5250,
  gnome_ladder_below = 17387,
  tourist_stairs = 16671,
  tourist_stairs_top = 16673,
}

local varbits = {
  golrie_chat = 9110,
}

local npcs = {
  almera = 4181,
  hudon = 4182,
  golrie = 4183,
}

local items = {
  book = 292,
  golrie_key = 293,
  pebble = 294,
  amulet = 295,
  urn = 296,
  empty_urn = 297,
  baxtorian_key = 298,
  water_rune = 555,
  air_rune = 556,
  earth_rune = 557,
  rope = 954,
  wine = 1993,
  dueling_ring = 2552,
}

local initial_loadout = {
  { id = items.rope, name = "Rope", quantity = 1, maximum_unit_price = 1000 },
  {
    id = 3853,
    name = "Games necklace",
    quantity = 1,
    maximum_unit_price = 1000,
    alternative_ids = { 3855, 3857, 3859, 3861, 3863, 3865 },
  },
  { id = items.wine, name = "Jug of wine", quantity = 6, maximum_unit_price = 100 },
}

local tomb_loadout = {
  { id = items.pebble, name = "Glarial's pebble", quantity = 1, purchase = false },
  {
    id = 3853,
    name = "Games necklace",
    quantity = 1,
    maximum_unit_price = 1000,
    alternative_ids = { 3855, 3857, 3859, 3861, 3863, 3865 },
  },
  { id = items.wine, name = "Jug of wine", quantity = 10, maximum_unit_price = 100 },
}

local tomb_urn_loadout = {
  { id = items.amulet, name = "Glarial's amulet", quantity = 1, purchase = false },
  { id = items.pebble, name = "Glarial's pebble", quantity = 1, purchase = false },
  {
    id = 3853,
    name = "Games necklace",
    quantity = 1,
    maximum_unit_price = 1000,
    alternative_ids = { 3855, 3857, 3859, 3861, 3863, 3865 },
  },
  { id = items.wine, name = "Jug of wine", quantity = 10, maximum_unit_price = 100 },
}

local gnome_loadout = {
  {
    id = items.dueling_ring,
    name = "Ring of dueling",
    quantity = 1,
    maximum_unit_price = 1000,
    alternative_ids = { 2554, 2556, 2558, 2560, 2562, 2564, 2566 },
  },
  { id = items.wine, name = "Jug of wine", quantity = 10, maximum_unit_price = 100 },
}

local gnome_key_loadout = {
  { id = items.golrie_key, name = "Key", quantity = 1, purchase = false },
  {
    id = items.dueling_ring,
    name = "Ring of dueling",
    quantity = 1,
    maximum_unit_price = 1000,
    alternative_ids = { 2554, 2556, 2558, 2560, 2562, 2564, 2566 },
  },
  { id = items.wine, name = "Jug of wine", quantity = 10, maximum_unit_price = 100 },
}

local final_loadout = {
  { id = items.rope, name = "Rope", quantity = 1, maximum_unit_price = 1000 },
  { id = items.air_rune, name = "Air rune", quantity = 6, maximum_unit_price = 50 },
  { id = items.water_rune, name = "Water rune", quantity = 6, maximum_unit_price = 50 },
  { id = items.earth_rune, name = "Earth rune", quantity = 6, maximum_unit_price = 50 },
  { id = items.amulet, name = "Glarial's amulet", quantity = 1, purchase = false },
  { id = items.urn, name = "Glarial's urn", quantity = 1, purchase = false },
  {
    id = 3853,
    name = "Games necklace",
    quantity = 1,
    maximum_unit_price = 1000,
    alternative_ids = { 3855, 3857, 3859, 3861, 3863, 3865 },
  },
  { id = items.wine, name = "Jug of wine", quantity = 8, maximum_unit_price = 100 },
}

local final_key_loadout = {
  { id = items.baxtorian_key, name = "Key", quantity = 1, purchase = false },
}
for _, item in ipairs(final_loadout) do table.insert(final_key_loadout, item) end

local gnome_route = {
  { x = 2505, y = 3190, plane = 0 }, { x = 2512, y = 3190, plane = 0 },
  { x = 2512, y = 3188, plane = 0 }, { x = 2532, y = 3188, plane = 0 },
  { x = 2532, y = 3182, plane = 0 }, { x = 2523, y = 3181, plane = 0 },
  { x = 2523, y = 3185, plane = 0 }, { x = 2521, y = 3185, plane = 0 },
  { x = 2520, y = 3179, plane = 0 }, { x = 2514, y = 3179, plane = 0 },
  { x = 2514, y = 3177, plane = 0 }, { x = 2527, y = 3177, plane = 0 },
  { x = 2527, y = 3179, plane = 0 }, { x = 2529, y = 3179, plane = 0 },
  { x = 2529, y = 3177, plane = 0 }, { x = 2531, y = 3177, plane = 0 },
  { x = 2531, y = 3179, plane = 0 }, { x = 2533, y = 3179, plane = 0 },
  { x = 2533, y = 3177, plane = 0 }, { x = 2544, y = 3177, plane = 0 },
  { x = 2544, y = 3174, plane = 0 }, { x = 2549, y = 3174, plane = 0 },
  { x = 2549, y = 3165, plane = 0 }, { x = 2545, y = 3165, plane = 0 },
  { x = 2545, y = 3159, plane = 0 }, { x = 2550, y = 3159, plane = 0 },
  { x = 2550, y = 3156, plane = 0 }, { x = 2548, y = 3156, plane = 0 },
  { x = 2548, y = 3145, plane = 0 }, { x = 2538, y = 3145, plane = 0 },
  { x = 2538, y = 3150, plane = 0 }, { x = 2541, y = 3150, plane = 0 },
  { x = 2541, y = 3148, plane = 0 }, { x = 2544, y = 3148, plane = 0 },
  { x = 2544, y = 3150, plane = 0 }, { x = 2545, y = 3150, plane = 0 },
  { x = 2545, y = 3155, plane = 0 },
}

return {
  id = "waterfall",
  label = "Waterfall Quest",
  zones = zones,
  points = points,
  objects = objects,
  varbits = varbits,
  npcs = npcs,
  items = items,
  initial_loadout = initial_loadout,
  gnome_loadout = gnome_loadout,
  gnome_key_loadout = gnome_key_loadout,
  tomb_loadout = tomb_loadout,
  tomb_urn_loadout = tomb_urn_loadout,
  final_loadout = final_loadout,
  final_key_loadout = final_key_loadout,
  gnome_route = gnome_route,
}
