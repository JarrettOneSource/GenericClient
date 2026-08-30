local config = gc.require("tree_gnome_config")
local interact = gc.require("tree_gnome_interactions")
local navigation = gc.require("tree_gnome_navigation")
local combat = gc.require("tree_gnome_combat")

local function quest_finished()
  return gc.read("quests").tree_gnome_village.state == "finished"
end

local function talk_bolren(choices, predicate)
  return interact.talk(
    config.npcs.king_bolren,
    config.points.king_bolren,
    predicate,
    choices,
    true)
end

local function accept_quest()
  local entered = navigation.enter_village_through_maze()
  if entered.status ~= "complete" then return entered end
  return talk_bolren(
    { "Can I help at all?", "I would be glad to help.", "Yes." },
    function() return interact.varp() >= 1 end)
end

local function talk_montai(choices, target_varp)
  return interact.talk(
    config.npcs.commander_montai,
    config.points.commander_montai,
    function() return interact.varp() >= target_varp end,
    choices,
    true)
end

local function tracker(id, point, varbit)
  return interact.talk(
    id,
    point,
    function() return interact.varbit(varbit) > 0 end,
    {},
    true)
end

local function fire_ballista()
  local near = interact.approach(config.points.ballista, 3, true)
  if near.status ~= "arrived" then return near end
  local target = interact.object(config.objects.ballista, "Fire", 16)
  if not target then
    return {
      status = "rejected",
      result = "ballista_not_observed",
      nearby = gc.read("objects", { within = 16, limit = 30 }),
    }
  end
  local coordinate = string.format("%04d", interact.varbit(config.varbits.ballista) + 1)
  local fired = gc.await {
    action = {
      type = "object.interact",
      id = config.objects.ballista,
      action = "Fire",
      world = target.world,
      within = 16,
    },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if fired.status ~= "dispatched" then return fired end
  local dialogue, failure = interact.finish_dialogue(
    function() return interact.varp() >= 5 end,
    { coordinate },
    true,
    100)
  if not dialogue then return failure end
  return {
    status = "complete",
    result = "ballista_hit_verified",
    coordinate = coordinate,
    receipt = fired,
    dialogue = dialogue,
  }
end

local function report_ballista()
  local talked = talk_montai({}, 5)
  if talked.status ~= "complete" then return talked end
  local reached = interact.walk(config.points.crumbled_wall, 8, true, 900)
  if reached.status ~= "arrived" then return reached end
  gc.await { event = "game.tick" }
  local wall = interact.object(config.objects.crumbled_wall, "Climb-over", 32)
  if not wall then
    return {
      status = "rejected",
      result = "crumbled_wall_not_observed_after_report",
      nearby = gc.read("objects", { within = 20, limit = 40 }),
    }
  end
  return {
    status = "complete",
    result = "ballista_reported",
    dialogue = talked,
    wall = wall,
  }
end

local function return_first_orb()
  local entered = navigation.return_to_village()
  if entered.status ~= "complete" then return entered end
  return talk_bolren(
    { "I will find the warlord and bring back the orbs." },
    function() return interact.varp() >= 7 end)
end

local function return_orbs()
  local entered = navigation.enter_village_with_elkoy()
  if entered.status ~= "complete" then return entered end
  return talk_bolren({}, quest_finished)
end

local function execute(phase)
  if phase == "accept_quest" then return accept_quest() end
  if phase == "talk_montai" then
    return talk_montai({ "Ok, I'll gather some wood." }, 2)
  end
  if phase == "give_logs" then return talk_montai({}, 3) end
  if phase == "talk_montai_again" then
    return talk_montai({ "I'll try my best." }, 4)
  end
  if phase == "tracker_one" then
    return tracker(config.npcs.tracker_one, config.points.tracker_one,
      config.varbits.tracker_height)
  end
  if phase == "tracker_two" then
    return tracker(config.npcs.tracker_two, config.points.tracker_two,
      config.varbits.tracker_y)
  end
  if phase == "tracker_three" then
    return tracker(config.npcs.tracker_three, config.points.tracker_three,
      config.varbits.tracker_x)
  end
  if phase == "fire_ballista" then return fire_ballista() end
  if phase == "report_ballista" then return report_ballista() end
  if phase == "enter_orb_tower" then return navigation.enter_orb_tower() end
  if phase == "search_orb_chest" then return navigation.search_orb_chest() end
  if phase == "return_first_orb" then return return_first_orb() end
  if phase == "fight_warlord" then return combat.fight() end
  if phase == "take_orbs" then return combat.take_orbs() end
  if phase == "return_orbs" or phase == "finish_dialogue" then return return_orbs() end
  return { status = "rejected", result = "tree_gnome_phase_unknown:" .. tostring(phase) }
end

return { execute = execute, escape_hostile_area = navigation.escape_hostile_area }
