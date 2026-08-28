local target_xp = {
  ["13"] = 1833,
  ["20"] = 4470,
  ["30"] = 13363,
}

local spell_labels = {
  wind_strike = "Wind Strike",
  water_strike = "Water Strike",
  earth_strike = "Earth Strike",
  fire_strike = "Fire Strike",
}

local methods = {
  port_sarim_jail = {
    label = "Port Sarim jail corridor",
    destination = { x = 3012, y = 3189, plane = 0 },
    route = {
      { x = 3104, y = 3420, plane = 0 },
      { x = 3070, y = 3359, plane = 0 },
      { x = 3052, y = 3294, plane = 0 },
      { x = 3038, y = 3245, plane = 0 },
      { x = 3024, y = 3205, plane = 0 },
      { x = 3012, y = 3189, plane = 0 },
    },
    escape = { x = 3020, y = 3210, plane = 0, within = 3 },
    disengage = { x = 3012, y = 3190, plane = 0 },
    within = 0,
    npc_names = { "Pirate", "Thief", "Mugger", "Black knight" },
    npc_radius = 15,
    maximum_level = 30,
  },
}

local plans = {
  ["13"] = {
    { id = 1381, name = "Staff of air", quantity = 1, maximum_unit_price = 2000 },
    { id = 1993, name = "Jug of wine", quantity = 6, maximum_unit_price = 10 },
  },
  ["20"] = {
    { id = 1381, name = "Staff of air", quantity = 1, maximum_unit_price = 2000 },
    { id = 1387, name = "Staff of fire", quantity = 1, maximum_unit_price = 2000 },
    { id = 1993, name = "Jug of wine", quantity = 6, maximum_unit_price = 10 },
  },
  ["30"] = {
    { id = 1381, name = "Staff of air", quantity = 1, maximum_unit_price = 2000 },
    { id = 1387, name = "Staff of fire", quantity = 1, maximum_unit_price = 2000 },
    { id = 1993, name = "Jug of wine", quantity = 6, maximum_unit_price = 10 },
  },
}

return {
  target_xp = target_xp,
  spell_labels = spell_labels,
  methods = methods,
  plans = plans,
}
