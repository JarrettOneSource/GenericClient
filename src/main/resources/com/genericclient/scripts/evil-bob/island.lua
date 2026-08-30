local INVITATION_NPC_ID = 390
local ISLAND_BOB_NPC_ID = 391
local SERVANT_NPC_ID = 393

local NET_ITEM_ID = 6209
local CORRECT_COOKED_FISH_ID = 6202
local CORRECT_RAW_FISH_ID = 6200
local WRONG_COOKED_FISH_ID = 6206

local FISHING_SPOT_OBJECT_ID = 23114
local UNCOOKING_POT_OBJECT_ID = 23113
local EXIT_PORTAL_OBJECT_ID = 23115

local ACCEPT_INVITATION = "Yes, that seems like a good idea."
local ISLAND = { x1 = 2500, x2 = 2555, y1 = 4750, y2 = 4805, plane = 0 }
local CENTRE = { x = 2522, y = 4773, plane = 0 }
local NET = { x = 2533, y = 4784, plane = 0 }

local FISHING_SPOTS = {
  {
    name = "south",
    approach = { x = 2525, y = 4770, plane = 0 },
    world = { x = 2525, y = 4764, plane = 0 },
  },
  {
    name = "north",
    approach = { x = 2527, y = 4785, plane = 0 },
    world = { x = 2527, y = 4791, plane = 0 },
  },
  {
    name = "west",
    approach = { x = 2516, y = 4775, plane = 0 },
    world = { x = 2510, y = 4775, plane = 0 },
  },
  {
    name = "east",
    approach = { x = 2537, y = 4777, plane = 0 },
    world = { x = 2543, y = 4777, plane = 0 },
  },
}

local function fail(status, details)
  local value = details or {}
  value.status = status
  gc.log("error", "evil-bob-failed", value)
  error(status, 0)
end

local function quantity(id)
  local total = 0
  for _, item in ipairs(gc.read("inventory").items or {}) do
    if item.id == id then total = total + item.quantity end
  end
  return total
end

local function free_slots()
  return 28 - #(gc.read("inventory").items or {})
end

local function on_island()
  local world = gc.read("player").world
  return world and world.plane == ISLAND.plane and
    world.x >= ISLAND.x1 and world.x <= ISLAND.x2 and
    world.y >= ISLAND.y1 and world.y <= ISLAND.y2
end

local function wait_for(predicate, ticks)
  for _ = 1, ticks do
    if predicate() then return true end
    gc.await { event = "game.tick" }
  end
  return predicate()
end

local function walk(world, within)
  local receipt = gc.await {
    action = { type = "walk.to", destination = world, within = within or 3, run = true },
    breaks = false,
    timeout = { game_ticks = 240 },
  }
  if receipt.status ~= "arrived" then
    fail("evil_bob_walk_failed", { destination = world, receipt = receipt })
  end
  return receipt
end

local function continue_dialogue()
  local receipt = gc.await {
    action = { type = "dialogue.continue" },
    breaks = false,
    timeout = { game_ticks = 20 },
  }
  if receipt.status ~= "dispatched" and
    receipt.result ~= "dialogue_continue_not_visible" and
    receipt.result ~= "dialogue_is_choice" then
    fail("evil_bob_dialogue_continue_failed", { receipt = receipt })
  end
end

local function choose_exact(dialogue, wanted)
  for _, option in ipairs(dialogue.options or {}) do
    if option.text == wanted then
      local receipt = gc.await {
        action = { type = "dialogue.choose", text = option.text },
        breaks = false,
        timeout = { game_ticks = 20 },
      }
      if receipt.status ~= "dispatched" then
        fail("evil_bob_dialogue_choice_failed", { receipt = receipt, wanted = wanted })
      end
      return
    end
  end
  fail("evil_bob_dialogue_choice_not_observed", { wanted = wanted, dialogue = dialogue })
end

local function drain_dialogue(ticks)
  local opened = gc.read("dialogue").type ~= "closed"
  local closed_ticks = 0
  for _ = 1, ticks do
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      opened = true
      closed_ticks = 0
      continue_dialogue()
    elseif dialogue.type == "choice" then
      fail("evil_bob_unexpected_dialogue_choice", { dialogue = dialogue })
    elseif opened then
      closed_ticks = closed_ticks + 1
      if closed_ticks >= 2 then return end
      gc.await { event = "game.tick" }
    else
      return
    end
  end
  fail("evil_bob_dialogue_did_not_close", { dialogue = gc.read("dialogue") })
end

local function drain_arrival_dialogue()
  for _ = 1, 12 do
    if gc.read("dialogue").type ~= "closed" then
      drain_dialogue(80)
      return
    end
    gc.await { event = "game.tick" }
  end
end

local function enter_island()
  if on_island() then return end

  local talked = false
  for _ = 1, 140 do
    if on_island() then return end
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      continue_dialogue()
    elseif dialogue.type == "choice" then
      choose_exact(dialogue, ACCEPT_INVITATION)
    elseif not talked then
      local receipt = gc.await {
        action = {
          type = "npc.interact",
          id = INVITATION_NPC_ID,
          action = "Talk-to",
          within = 12,
        },
        breaks = false,
        timeout = { game_ticks = 30 },
      }
      if receipt.status ~= "dispatched" then
        fail("evil_bob_invitation_talk_failed", {
          receipt = receipt,
          event = gc.read("random_event"),
        })
      end
      talked = true
    else
      gc.await { event = "game.tick" }
    end
  end

  fail("evil_bob_island_entry_not_observed", {
    player = gc.read("player"),
    dialogue = gc.read("dialogue"),
  })
end

local function ensure_net()
  if quantity(NET_ITEM_ID) > 0 then return end
  if free_slots() < 2 then
    fail("evil_bob_needs_two_free_inventory_slots", { inventory = gc.read("inventory") })
  end

  walk(NET, 3)
  local receipt = gc.await {
    action = { type = "ground_item.take", id = NET_ITEM_ID, world = NET, within = 8 },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if receipt.status ~= "dispatched" or not wait_for(function()
    return quantity(NET_ITEM_ID) > 0
  end, 20) then
    fail("evil_bob_net_not_obtained", { receipt = receipt, inventory = gc.read("inventory") })
  end
end

local function talk_to_servant()
  drain_dialogue(30)
  walk(CENTRE, 4)
  local receipt = gc.await {
    action = { type = "npc.interact", id = SERVANT_NPC_ID, action = "Talk-to", within = 16 },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if receipt.status ~= "dispatched" then
    fail("evil_bob_servant_talk_failed", { receipt = receipt })
  end

  local opened = false
  local closed_ticks = 0
  for _ = 1, 80 do
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then
      opened = true
      closed_ticks = 0
      continue_dialogue()
    elseif dialogue.type == "choice" then
      fail("evil_bob_servant_unexpected_choice", { dialogue = dialogue })
    elseif opened then
      closed_ticks = closed_ticks + 1
      if closed_ticks >= 2 then return end
      gc.await { event = "game.tick" }
    else
      gc.await { event = "game.tick" }
    end
  end
  fail("evil_bob_servant_dialogue_not_observed", { receipt = receipt })
end

local function fish(spot)
  walk(spot.approach, 2)
  local receipt = gc.await {
    action = {
      type = "object.interact",
      id = FISHING_SPOT_OBJECT_ID,
      action = "Net",
      world = spot.world,
      within = 12,
    },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if receipt.status ~= "dispatched" then
    fail("evil_bob_fishing_dispatch_failed", { spot = spot.name, receipt = receipt })
  end

  for _ = 1, 35 do
    if quantity(CORRECT_COOKED_FISH_ID) > 0 then return true end
    if quantity(WRONG_COOKED_FISH_ID) > 0 then return false end
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then continue_dialogue() else gc.await { event = "game.tick" } end
  end
  fail("evil_bob_fishing_result_not_observed", {
    spot = spot.name,
    receipt = receipt,
    inventory = gc.read("inventory"),
  })
end

local function affirmative_option(dialogue)
  for _, option in ipairs(dialogue.options or {}) do
    local text = string.lower(option.text or "")
    if text == "yes." or text == "yes" or string.sub(text, 1, 4) == "yes," then
      return option.text
    end
  end
  return nil
end

local function destroy_wrong_fish()
  if quantity(WRONG_COOKED_FISH_ID) == 0 then return end
  local receipt = gc.await {
    action = { type = "item.interact", id = WRONG_COOKED_FISH_ID, action = "Destroy" },
    breaks = false,
    timeout = { game_ticks = 20 },
  }
  if receipt.status ~= "dispatched" then
    fail("evil_bob_wrong_fish_destroy_failed", { receipt = receipt })
  end

  for _ = 1, 30 do
    if quantity(WRONG_COOKED_FISH_ID) == 0 then return end
    local dialogue = gc.read("dialogue")
    if dialogue.type == "choice" then
      local option = affirmative_option(dialogue)
      if not option then
        fail("evil_bob_destroy_confirmation_unknown", { dialogue = dialogue })
      end
      choose_exact(dialogue, option)
    elseif dialogue.type == "continue" then
      continue_dialogue()
    else
      gc.await { event = "game.tick" }
    end
  end
  fail("evil_bob_wrong_fish_remained", { inventory = gc.read("inventory") })
end

local function obtain_correct_fish()
  if quantity(CORRECT_COOKED_FISH_ID) > 0 then return {} end
  destroy_wrong_fish()

  local tried = {}
  for _, spot in ipairs(FISHING_SPOTS) do
    talk_to_servant()
    tried[#tried + 1] = spot.name
    if fish(spot) then return tried end
    destroy_wrong_fish()
  end
  fail("evil_bob_correct_fishing_spot_not_found", { tried = tried })
end

local function uncook_fish()
  if quantity(CORRECT_RAW_FISH_ID) > 0 then return end
  if quantity(CORRECT_COOKED_FISH_ID) == 0 then
    fail("evil_bob_correct_fish_missing_before_uncook")
  end

  walk(CENTRE, 5)
  local receipt = gc.await {
    action = {
      type = "item.use_on_object",
      item_id = CORRECT_COOKED_FISH_ID,
      object_id = UNCOOKING_POT_OBJECT_ID,
      within = 24,
    },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if receipt.status ~= "dispatched" or not wait_for(function()
    return quantity(CORRECT_RAW_FISH_ID) > 0
  end, 30) then
    fail("evil_bob_fish_not_uncooked", { receipt = receipt, inventory = gc.read("inventory") })
  end
end

local function catnap_message(since_tick)
  for _, message in ipairs(gc.read("messages", { since_tick = since_tick, limit = 40 })) do
    if string.find(string.lower(message.text or ""), "catnap", 1, true) then return message end
  end
  return nil
end

local function feed_bob(started_tick)
  walk(CENTRE, 5)
  local receipt = gc.await {
    action = {
      type = "item.use_on_npc",
      item_id = CORRECT_RAW_FISH_ID,
      npc_id = ISLAND_BOB_NPC_ID,
      npc_name = "Evil Bob",
      within = 20,
    },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if receipt.status ~= "dispatched" then
    fail("evil_bob_feeding_failed", { receipt = receipt })
  end

  for _ = 1, 80 do
    local message = catnap_message(started_tick)
    if message then
      drain_dialogue(40)
      return message
    end
    local dialogue = gc.read("dialogue")
    if dialogue.type == "continue" then continue_dialogue() else gc.await { event = "game.tick" } end
  end
  fail("evil_bob_catnap_not_observed", {
    receipt = receipt,
    messages = gc.read("messages", { since_tick = started_tick, limit = 40 }),
  })
end

local function exit_island()
  walk(CENTRE, 5)
  local receipt = gc.await {
    action = { type = "object.interact", id = EXIT_PORTAL_OBJECT_ID, action = "Enter", within = 24 },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if receipt.status ~= "dispatched" or not wait_for(function() return not on_island() end, 80) then
    fail("evil_bob_exit_not_observed", { receipt = receipt, player = gc.read("player") })
  end
  return receipt
end

local function solve(started_tick)
  started_tick = started_tick or gc.read("runtime").game_tick
  enter_island()
  drain_arrival_dialogue()
  ensure_net()
  local tried = obtain_correct_fish()
  uncook_fish()
  local catnap = feed_bob(started_tick)
  local exit = exit_island()
  return {
    status = "solved",
    fishing_spots_tried = tried,
    catnap = catnap,
    exit = exit,
    player = gc.read("player"),
  }
end

return {
  invitation_npc_id = INVITATION_NPC_ID,
  solve = solve,
}
