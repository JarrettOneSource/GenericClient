local config = gc.require("fight_arena_config")
local interact = gc.require("fight_arena_interactions")
local navigation = gc.require("fight_arena_navigation")
local combat = gc.require("fight_arena_combat")

local function in_zone(world, zone)
  return world and world.plane == zone.plane and world.x >= zone.x1 and world.x <= zone.x2 and
    world.y >= zone.y1 and world.y <= zone.y2
end

local function accept_quest()
  local reached = navigation.reach_lady()
  if reached.status ~= "complete" then return reached end
  return interact.talk(
    config.npcs.lady_servil,
    config.points.lady_servil,
    function() return interact.varp() > 0 end,
    { "Yes." },
    true)
end

local function talk_head_guard()
  local before = interact.varp()
  return interact.talk(
    config.npcs.head_guard,
    config.points.head_guard,
    function() return interact.varp() ~= before end,
    {},
    true)
end

local function buy_khali_brew()
  local reached = navigation.reach_bar()
  if reached.status ~= "complete" then return reached end
  return interact.talk(
    config.npcs.barman,
    config.points.bar,
    function() return interact.carried(config.items.khali_brew) > 0 end,
    { "I'd like a Khali Brew please." },
    true)
end

local function give_khali_brew()
  local before = interact.varp()
  return interact.talk(
    config.npcs.head_guard,
    config.points.head_guard,
    function() return interact.varp() ~= before end,
    {},
    true)
end

local function get_cell_keys()
  return interact.talk(
    config.npcs.head_guard,
    config.points.head_guard,
    function() return interact.carried(config.items.cell_keys) > 0 end,
    {},
    true)
end

local function free_sammy()
  return interact.use_on_object(
    config.items.cell_keys,
    config.objects.sammy_door,
    config.points.sammy_door,
    function() return interact.varp() >= 6 end,
    false,
    10)
end

local function talk_sammy(targets)
  local function target_ready()
    for _, ids in ipairs(targets) do
      if interact.npc(ids, 24) then return true end
    end
    return false
  end
  if gc.read("player").world.x >= 10000 or gc.read("dialogue").type ~= "closed" then
    local dialogue, failure = interact.finish_dialogue(target_ready, {}, false, 160)
    if not dialogue then return failure end
    return { status = "complete", result = "arena_cutscene_complete", dialogue = dialogue }
  end
  local reached = navigation.reach_arena_area()
  if reached.status ~= "complete" then return reached end

  local talkable_sammy = nil
  for _, id in ipairs(config.npcs.sammy_servil) do
    talkable_sammy = gc.read("npcs", {
      id = id,
      action = "Talk-to",
      within = 24,
      limit = 1,
    })[1]
    if talkable_sammy then break end
  end
  if not talkable_sammy then
    local near = interact.approach(config.points.arena_reentry, 1, false)
    if near.status ~= "arrived" then return near end
    gc.await { event = "game.tick" }
    local door = interact.object(config.objects.arena_door_one, "Open", 12)
    if not door then
      return {
        status = "rejected",
        result = "arena_reentry_door_not_observed",
        nearby = gc.read("objects", { within = 12, limit = 50 }),
      }
    end
    local opened = gc.await {
      action = {
        type = "object.interact",
        id = door.id,
        action = "Open",
        world = door.world,
        within = 12,
      },
      breaks = false,
      timeout = { game_ticks = 40 },
    }
    if opened.status ~= "dispatched" then return opened end
    local dialogue, failure = interact.finish_dialogue(target_ready, {}, false, 160)
    if not dialogue then return failure end
    return {
      status = "complete",
      result = "arena_reentry_complete",
      receipt = opened,
      dialogue = dialogue,
    }
  end
  return interact.talk(
    config.npcs.sammy_servil,
    config.points.sammy,
    target_ready,
    {},
    false)
end

local function talk_general_khazard()
  local before = interact.varp()
  if gc.read("dialogue").type ~= "closed" then
    local dialogue, failure = interact.finish_dialogue(
      function() return interact.varp() ~= before end,
      {},
      false,
      160)
    if not dialogue then return failure end
    return { status = "complete", result = "general_khazard_cutscene_complete", dialogue = dialogue }
  end
  return interact.talk(
    config.npcs.general_khazard,
    config.points.sammy,
    function() return interact.varp() ~= before end,
    {},
    false)
end

local function talk_hengrad()
  return interact.talk(
    config.npcs.hengrad,
    config.points.hengrad,
    function()
      return not in_zone(gc.read("player").world, config.zones.cell) or
        interact.npc(config.npcs.scorpion, 24) ~= nil
    end,
    {},
    false)
end

local function leave_arena()
  if gc.read("player").world.x >= 10000 then
    if gc.read("dialogue").type ~= "closed" then
      local dialogue, failure = interact.finish_dialogue(
        function() return gc.read("dialogue").type == "closed" end,
        {},
        false,
        160)
      if not dialogue then return failure end
    end

    local door = gc.read("objects", { action = "Quick-escape", within = 24, limit = 3 })[1]
    if not door then
      local mapping = gc.read("instance", { template = config.points.arena_exit })
      local destination = mapping.matches and mapping.matches[1]
      if destination then
        local near = interact.walk(destination, 6, false, 120)
        if near.status ~= "arrived" then return near end
        gc.await { event = "game.tick" }
        door = gc.read("objects", { action = "Quick-escape", within = 24, limit = 3 })[1]
      end
    end
    if not door then
      return { status = "rejected", result = "arena_quick_escape_not_observed" }
    end
    local escaped = gc.await {
      action = {
        type = "object.interact",
        id = door.id,
        action = "Quick-escape",
        world = door.world,
        within = 12,
      },
      breaks = false,
      timeout = { game_ticks = 40 },
    }
    if escaped.status ~= "dispatched" then return escaped end
    if not interact.wait_for(function()
      return gc.read("player").world.x < 10000
    end, 30) then
      return { status = "timed_out", result = "arena_quick_escape_unverified", receipt = escaped }
    end
    return { status = "complete", result = "arena_quick_escape_verified", receipt = escaped }
  end

  local near = interact.approach(config.points.arena_exit, 2, false)
  if near.status ~= "arrived" then return near end
  local door = interact.object(config.objects.arena_door_two, "Open", 10)
  if not door then
    return {
      status = "rejected",
      result = "arena_exit_not_observed",
      nearby = gc.read("objects", { within = 12, limit = 40 }),
    }
  end
  local opened = gc.await {
    action = {
      type = "object.interact",
      id = door.id,
      action = "Open",
      world = door.world,
      within = 10,
    },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if opened.status ~= "dispatched" then return opened end
  local dialogue, failure = interact.finish_dialogue(
    function() return not in_zone(gc.read("player").world, config.zones.arena) end,
    { "Yes." },
    false,
    80)
  if not dialogue then return failure end
  return { status = "complete", result = "arena_exited", receipt = opened, dialogue = dialogue }
end

local function finish_quest()
  return interact.talk(
    config.npcs.lady_servil,
    config.points.lady_servil,
    interact.quest_finished,
    {},
    true)
end

local function execute(phase)
  if phase == "accept_quest" then return accept_quest() end
  if phase == "obtain_khazard_armour" then return navigation.obtain_armour() end
  if phase == "equip_khazard_armour" then return navigation.equip_armour() end
  if phase == "talk_head_guard" then return talk_head_guard() end
  if phase == "buy_khali_brew" then return buy_khali_brew() end
  if phase == "give_khali_brew" then return give_khali_brew() end
  if phase == "get_cell_keys" then return get_cell_keys() end
  if phase == "free_sammy" then return free_sammy() end
  if phase == "talk_sammy_for_ogre" then return talk_sammy({ config.npcs.ogre }) end
  if phase == "talk_general_khazard" then return talk_general_khazard() end
  if phase == "talk_hengrad" then return talk_hengrad() end
  if phase == "talk_sammy_for_scorpion" then return talk_sammy({ config.npcs.scorpion }) end
  if phase == "talk_sammy_for_bouncer" then return talk_sammy({ config.npcs.bouncer }) end
  if phase == "leave_arena" then return leave_arena() end
  if phase == "finish_quest" then return finish_quest() end
  if string.find(phase, "fight_", 1, true) == 1 then return combat.execute(phase) end
  return { status = "rejected", result = "fight_arena_phase_unknown:" .. tostring(phase) }
end

return { execute = execute }
