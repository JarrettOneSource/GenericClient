local config = gc.require("grand_tree_config")

local function distance(a, b)
  if not a or not b or a.plane ~= b.plane then return 99999 end
  return math.max(math.abs(a.x - b.x), math.abs(a.y - b.y))
end

local function has_item(id)
  for _, item in ipairs(gc.read("inventory").items or {}) do
    if item.id == id and item.quantity > 0 then return true end
  end
  return false
end

local function npc(ids, within)
  for _, id in ipairs(ids) do
    local target = gc.read("npcs", {
      id = id,
      within = within or 32,
      where = { clickable = true },
      limit = 1,
    })[1]
    if target then return target end
  end
  return nil
end

local function walk(destination, within)
  gc.activity("travel")
  return gc.await {
    action = {
      type = "walk.to",
      destination = destination,
      within = within or 4,
      run = true,
    },
    breaks = true,
    timeout = { game_ticks = 600 },
  }
end

local function in_tunnel(world)
  return world and world.plane == 0 and (world.y >= 9800 or world.x >= 10000)
end

local function finish_dialogue(predicate, started_tick)
  local progressed = false
  local closed_ticks = 0
  for _ = 1, 180 do
    gc.await { event = "game.tick" }
    progressed = progressed or predicate()
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      closed_ticks = 0
      local continued = gc.await {
        action = { type = "dialogue.continue" },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
      if continued.status ~= "dispatched" then return nil, continued end
    elseif dialogue.type == "choice" then
      return nil, { status = "unexpected_grand_tree_completion_choice", dialogue = dialogue }
    elseif progressed then
      closed_ticks = closed_ticks + 1
      if closed_ticks >= 2 then return true end
    end
    for _, message in ipairs(gc.read("messages", { since_tick = started_tick, limit = 20 })) do
      if string.find(string.lower(message.text or ""), "can't reach that", 1, true) then
        return nil, { status = "grand_tree_completion_target_unreachable", message = message }
      end
    end
  end
  return nil, { status = "grand_tree_completion_dialogue_timeout", dialogue = gc.read("dialogue") }
end

local function enter_tunnel()
  if in_tunnel(gc.read("player").world) then return true end
  local trapdoor = gc.read("objects", {
    id = config.objects.watchtower_trapdoor_after_fight,
    within = 16,
    limit = 1,
  })[1]
  if not trapdoor then
    return nil, {
      status = "post_demon_trapdoor_not_observed",
      player = gc.read("player"),
      objects = gc.read("objects", { within = 16, limit = 80 }),
    }
  end
  local action = nil
  for _, candidate in ipairs(trapdoor.actions or {}) do
    if candidate == "Climb-down" or candidate == "Open" then
      action = candidate
      break
    end
  end
  if not action then
    return nil, { status = "post_demon_trapdoor_action_missing", trapdoor = trapdoor }
  end
  local descended = gc.await {
    action = {
      type = "object.interact",
      id = trapdoor.id,
      action = action,
      world = trapdoor.world,
      within = 16,
    },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if descended.status ~= "dispatched" then return nil, descended end
  for _ = 1, 40 do
    gc.await { event = "game.tick" }
    if in_tunnel(gc.read("player").world) then return true end
  end
  return nil, { status = "post_demon_tunnel_entry_unverified", receipt = descended }
end

local function reach_king()
  local target = npc(config.npcs.king_narnode, 32)
  if target then return target end
  local entered, failure = enter_tunnel()
  if not entered then return nil, failure end
  local approached = walk(config.points.cave_king, 6)
  if approached.status ~= "arrived" then
    return nil, { status = "cave_king_approach_failed", receipt = approached }
  end
  target = npc(config.npcs.king_narnode, 20)
  if target then return target end
  return nil, {
    status = "cave_king_not_observed",
    player = gc.read("player"),
    nearby = gc.read("npcs", { within = 30, limit = 50 }),
  }
end

local function talk_to_king_after_demon()
  local target, failure = reach_king()
  if not target then return failure end
  local started_tick = gc.read("runtime").game_tick
  local talked = gc.await {
    action = { type = "npc.interact", id = target.id, action = "Talk-to", within = 20 },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if talked.status ~= "dispatched" then return talked end
  local finished, dialogue_failure = finish_dialogue(function()
    return gc.read("vars", { varps = { config.varp } }).varps[config.varp] >= 150
  end, started_tick)
  if not finished then return dialogue_failure end
  return { status = "complete", result = "cave_king_briefed", receipt = talked }
end

local function roots_in_scene()
  local roots = {}
  for _, id in ipairs({ config.objects.daconia_root, config.objects.daconia_root_alternate }) do
    for _, root in ipairs(gc.read("objects", { id = id, action = "Search", within = 32, limit = 80 })) do
      roots[#roots + 1] = root
    end
  end
  table.sort(roots, function(a, b) return a.distance < b.distance end)
  return roots
end

local function search_root(root)
  if distance(gc.read("player").world, root.world) > 12 then
    local approached = walk(root.world, 6)
    if approached.status ~= "arrived" then return nil, approached end
  end
  local searched = gc.await {
    action = {
      type = "object.interact",
      id = root.id,
      action = "Search",
      world = root.world,
      within = 16,
    },
    breaks = true,
    timeout = { game_ticks = 40 },
  }
  if searched.status ~= "dispatched" then return nil, searched end
  for tick = 1, 20 do
    gc.await { event = "game.tick" }
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      local continued = gc.await {
        action = { type = "dialogue.continue" },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
      if continued.status ~= "dispatched" then return nil, continued end
    end
    if has_item(config.items.daconia_rock) then return true, searched end
    if tick >= 4 and dialogue.type == "closed" then break end
  end
  return false, searched
end

local function find_daconia_rock()
  if has_item(config.items.daconia_rock) then
    return { status = "complete", result = "daconia_rock_already_carried" }
  end
  local entered, failure = enter_tunnel()
  if not entered then return failure end
  gc.activity("questing")
  local searched = {}
  local receipts = {}
  for _ = 1, 3 do
    local roots = roots_in_scene()
    if #roots == 0 then
      return {
        status = "daconia_roots_not_observed",
        player = gc.read("player"),
        objects = gc.read("objects", { within = 32, limit = 120 }),
      }
    end
    local attempted = false
    for _, root in ipairs(roots) do
      local key = root.id .. ":" .. root.world.x .. ":" .. root.world.y
      if not searched[key] then
        attempted = true
        searched[key] = true
        local found, receipt = search_root(root)
        receipts[#receipts + 1] = { root = root.world, receipt = receipt }
        if found == nil then
          return { status = "daconia_root_search_failed", root = root.world, receipt = receipt }
        end
        if found then
          return { status = "complete", result = "daconia_rock_found", receipts = receipts }
        end
      end
    end
    if not attempted then break end
  end
  return {
    status = "daconia_rock_not_found_in_observed_roots",
    player = gc.read("player"),
    receipts = receipts,
    messages = gc.read("messages", { limit = 30 }),
  }
end

local function return_daconia_rock()
  if not has_item(config.items.daconia_rock) then
    return { status = "daconia_rock_not_carried" }
  end
  local target, failure = reach_king()
  if not target then return failure end
  local started_tick = gc.read("runtime").game_tick
  local talked = gc.await {
    action = { type = "npc.interact", id = target.id, action = "Talk-to", within = 20 },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if talked.status ~= "dispatched" then return talked end
  local finished, dialogue_failure = finish_dialogue(function()
    local quests = gc.read("quests")
    return quests.the_grand_tree and quests.the_grand_tree.state == "finished"
  end, started_tick)
  if not finished then return dialogue_failure end
  return { status = "complete", result = "grand_tree_completed", receipt = talked }
end

return {
  talk_to_king_after_demon = talk_to_king_after_demon,
  find_daconia_rock = find_daconia_rock,
  return_daconia_rock = return_daconia_rock,
}
