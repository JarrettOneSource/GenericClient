local preparation = gc.require("waterfall_preparation")
local navigation = gc.require("waterfall_navigation")
local tomb = gc.require("waterfall_tomb")
local ritual = gc.require("waterfall_ritual")

local navigation_phases = {
  accept = true,
  reach_hudon = true,
  talk_hudon = true,
  cross_to_tree = true,
  descend_tree = true,
  leave_ledge = true,
  reach_tourist_stairs = true,
  obtain_book = true,
  read_book = true,
  leave_tourist_house = true,
  reach_gnome_dungeon = true,
  obtain_golrie_key = true,
  open_golrie_gate = true,
  obtain_pebble = true,
  leave_gnome_dungeon = true,
  reach_falls = true,
  cross_to_tree_final = true,
  descend_tree_final = true,
  equip_amulet = true,
  enter_falls = true,
}

local tomb_phases = {
  enter_glarial_tomb = true,
  obtain_amulet = true,
  obtain_urn = true,
  leave_glarial_tomb = true,
}

local ritual_phases = {
  obtain_baxtorian_key = true,
  open_inner_door = true,
  remove_amulet = true,
  charge_pillars = true,
  finish_quest = true,
}

local function execute(phase, restock)
  if preparation.handles(phase) then return preparation.execute(phase, restock) end
  if navigation_phases[phase] then return navigation.execute(phase) end
  if tomb_phases[phase] then return tomb.execute(phase) end
  if ritual_phases[phase] then return ritual.execute(phase) end
  return { status = "rejected", result = "waterfall_phase_unknown:" .. tostring(phase) }
end

local function escape_hostile_area()
  local tomb_escape = tomb.escape()
  if tomb_escape.result ~= "not_in_glarial_tomb" then return tomb_escape end
  return navigation.escape_hostile_area()
end

return { execute = execute, escape_hostile_area = escape_hostile_area }
