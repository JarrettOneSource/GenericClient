local config = gc.require("tree_gnome_config")
local interact = gc.require("tree_gnome_interactions")
local navigation = gc.require("tree_gnome_navigation")

local function npc(id, within)
  return gc.read("npcs", {
    id = id,
    within = within or 20,
    limit = 5,
  })[1]
end

local function orbs_on_ground()
  return gc.read("ground_items", {
    id = config.items.remaining_orbs,
    within = 20,
    limit = 3,
  })[1]
end

local function drain_continue_dialogue()
  for _ = 1, 5 do
    local dialogue = gc.read("dialogue")
    if dialogue.type ~= "continue" then return true end
    local continued = gc.await {
      action = { type = "dialogue.continue" },
      breaks = false,
      timeout = { game_ticks = 20 },
    }
    if continued.status ~= "dispatched" then return false end
    gc.await { event = "game.tick" }
  end
  return gc.read("dialogue").type ~= "continue"
end

local function equip_staff()
  if interact.quantity(gc.read("equipment"), config.items.staff_of_air) > 0 then return true end
  if interact.quantity(gc.read("inventory"), config.items.staff_of_air) == 0 then
    return nil, { status = "staff_missing" }
  end
  local equipped = gc.await {
    action = { type = "item.interact", id = config.items.staff_of_air, action = "Wield" },
    breaks = false,
    timeout = { game_ticks = 20 },
  }
  if equipped.status ~= "dispatched" then
    return nil, { status = "staff_equip_failed", receipt = equipped }
  end
  if not interact.wait_for(function()
    return interact.quantity(gc.read("equipment"), config.items.staff_of_air) > 0
  end, 10) then
    return nil, { status = "staff_equip_unverified", receipt = equipped }
  end
  return true
end

local function configure()
  local equipped, failure = equip_staff()
  if not equipped then return nil, failure end
  local autocast = gc.await {
    action = { type = "combat.set_autocast", spell = "Earth Bolt" },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if autocast.status ~= "set" and autocast.status ~= "unchanged" then
    return nil, { status = "earth_bolt_autocast_failed", receipt = autocast }
  end
  local safety = gc.await {
    action = {
      type = "safety.configure",
      minimum_hitpoints = 6,
      consumables = { { id = config.items.food, action = "Eat", heal_amount = 12 } },
      continue_after_consumable = true,
      allow_overheal = false,
    },
    breaks = false,
  }
  if safety.status ~= "complete" then
    return nil, { status = "warlord_safety_failed", receipt = safety }
  end
  return true
end

local function start_fight()
  if npc(config.npcs.warlord_combat, 20) then return true end

  local receipts = {}
  for attempt = 1, 3 do
    local target = npc(config.npcs.warlord_chat, 20)
    if not target then
      return nil, {
        status = "warlord_chat_form_not_observed",
        attempt = attempt,
        nearby = gc.read("npcs", { within = 20, limit = 30 }),
      }
    end

    local clicked = gc.await {
      action = {
        type = "npc.interact",
        id = config.npcs.warlord_chat,
        action = "Talk-to",
        within = 20,
      },
      breaks = false,
      timeout = { game_ticks = 40 },
    }
    receipts[#receipts + 1] = clicked
    local dialogue_seen = false
    if clicked.status ~= "dispatched" then
      local result = clicked.result
      if result ~= "npc_not_visible" and result ~= "matching_npc_not_found" and
        result ~= "client_not_logged_in" then
        return nil, {
          status = "warlord_talk_failed",
          attempt = attempt,
          receipt = clicked,
        }
      end
      gc.await { ticks = 3 }
    else
      local closed_ticks = 0
      for _ = 1, 40 do
        gc.await { event = "game.tick" }
        if npc(config.npcs.warlord_combat, 20) or orbs_on_ground() then
          return true
        end

        local dialogue = gc.read("dialogue")
        if dialogue.type == "continue" then
          dialogue_seen = true
          closed_ticks = 0
          local continued = gc.await {
            action = { type = "dialogue.continue" },
            breaks = false,
            timeout = { game_ticks = 20 },
          }
          receipts[#receipts + 1] = continued
          if continued.status ~= "dispatched" then
            return nil, {
              status = "warlord_dialogue_failed",
              attempt = attempt,
              receipt = continued,
              dialogue = dialogue,
            }
          end
        elseif dialogue.type == "choice" then
          return nil, {
            status = "unexpected_warlord_dialogue_choice",
            attempt = attempt,
            dialogue = dialogue,
          }
        else
          closed_ticks = closed_ticks + 1
          local retry_after = dialogue_seen and 8 or 6
          if closed_ticks >= retry_after then break end
        end
      end
    end

    gc.log("info", "warlord-talk-retry", {
      attempt = attempt,
      dialogue_seen = dialogue_seen,
      world = gc.read("player").world,
    })
  end

  return nil, {
    status = "warlord_activation_failed",
    dialogue = gc.read("dialogue"),
    nearby = gc.read("npcs", { within = 20, limit = 30 }),
    messages = gc.read("messages", { limit = 30 }),
    receipts = receipts,
  }
end

local function attack()
  local target = npc(config.npcs.warlord_combat, 20)
  if not target then return nil, { status = "warlord_not_observed" } end
  local receipt = gc.await {
    action = {
      type = "npc.interact",
      id = config.npcs.warlord_combat,
      action = "Attack",
      within = 20,
    },
    breaks = false,
    timeout = { game_ticks = 40 },
  }
  if receipt.status ~= "dispatched" then
    return nil, { status = "warlord_attack_failed", receipt = receipt, target = target }
  end
  return true
end

local function safespot_ready(target)
  if not target or not target.line_of_sight or not target.clickable or target.distance < 4 then
    return false
  end
  local player = gc.read("player").world
  return target.world.x <= config.points.warlord_pin.x and
    player.x <= config.points.warlord_cast.x + 1 and
    player.y <= config.points.warlord_cast.y + 1
end

local function position_safespot()
  local target = npc(config.npcs.warlord_combat, 20)
  if safespot_ready(target) then return true end

  local dragged = interact.walk(config.points.warlord_drag, 1, false, 120)
  if dragged.status ~= "arrived" then
    return nil, { status = "warlord_drag_failed", receipt = dragged }
  end
  local pinned
  for _ = 1, 20 do
    gc.await { event = "game.tick" }
    target = npc(config.npcs.warlord_combat, 20)
    if target and target.world.x <= config.points.warlord_pin.x and target.distance <= 5 then
      pinned = target
      break
    end
  end
  if not pinned then
    return nil, { status = "warlord_pin_unverified", target = target }
  end

  local cast = interact.walk(config.points.warlord_cast, 1, false, 80)
  if cast.status ~= "arrived" then
    return nil, { status = "warlord_cast_tile_failed", receipt = cast }
  end
  for _ = 1, 10 do
    gc.await { event = "game.tick" }
    target = npc(config.npcs.warlord_combat, 20)
    if safespot_ready(target) then return true end
  end
  return nil, {
    status = "warlord_safespot_unverified",
    player = gc.read("player"),
    target = target,
  }
end

local function fight()
  local ready, failure = configure()
  if not ready then return failure end
  local reached = navigation.reach_warlord()
  if reached.status ~= "complete" then return reached end
  ready, failure = start_fight()
  if not ready then return failure end
  if orbs_on_ground() then return { status = "complete", result = "warlord_defeated" } end

  ready, failure = position_safespot()
  if not ready then return failure end
  ready, failure = attack()
  if not ready then return failure end

  local missing_ticks = 0
  local death_ticks = 0
  for _ = 1, 600 do
    gc.await { event = "game.tick" }
    if not drain_continue_dialogue() then
      return { status = "warlord_combat_dialogue_failed", dialogue = gc.read("dialogue") }
    end
    local drop = orbs_on_ground()
    if drop then
      return { status = "complete", result = "warlord_defeated", drop = drop }
    end

    local target = npc(config.npcs.warlord_combat, 20)
    if not target then
      if death_ticks > 0 then
        death_ticks = death_ticks + 1
        if death_ticks >= 60 then
          return { status = "warlord_drop_missing", messages = gc.read("messages", { limit = 20 }) }
        end
      else
        missing_ticks = missing_ticks + 1
      end
      if death_ticks == 0 and missing_ticks >= 20 then
        return { status = "warlord_missing", messages = gc.read("messages", { limit = 20 }) }
      end
    elseif target.dead then
      death_ticks = death_ticks + 1
      if death_ticks >= 30 then
        return { status = "warlord_drop_missing", messages = gc.read("messages", { limit = 20 }) }
      end
    else
      missing_ticks = 0
      death_ticks = 0
      if target.distance < 4 then
        ready, failure = position_safespot()
        if not ready then return failure end
        ready, failure = attack()
        if not ready then return failure end
      elseif not gc.read("player").interacting then
        ready, failure = attack()
        if not ready then return failure end
      end
    end
  end
  return { status = "warlord_timeout", messages = gc.read("messages", { limit = 20 }) }
end

local function take_orbs()
  local drop = orbs_on_ground()
  if not drop then return { status = "rejected", result = "orbs_not_observed" } end

  local approached = interact.walk(drop.world, 2, false, 80)
  if approached.status ~= "arrived" then
    return { status = "rejected", result = "orbs_approach_failed", receipt = approached }
  end

  local attempts = {}
  for _ = 1, 4 do
    if interact.carried(config.items.remaining_orbs) > 0 then
      return { status = "complete", result = "orbs_obtained", attempts = attempts }
    end
    drop = orbs_on_ground()
    if not drop then break end
    local taken = gc.await {
      action = {
        type = "ground_item.take",
        id = config.items.remaining_orbs,
        world = drop.world,
        within = 10,
      },
      breaks = false,
      timeout = { game_ticks = 40 },
    }
    attempts[#attempts + 1] = taken
    if taken.status == "dispatched" and interact.wait_for(function()
      return interact.carried(config.items.remaining_orbs) > 0
    end, 20) then
      return {
        status = "complete",
        result = "orbs_obtained",
        approach = approached,
        attempts = attempts,
      }
    end
    gc.await { ticks = 2 }
  end
  return {
    status = "rejected",
    result = "orbs_pickup_unverified",
    approach = approached,
    attempts = attempts,
    ground = gc.read("ground_items", {
      id = config.items.remaining_orbs,
      within = 20,
      limit = 3,
    }),
  }
end

return { fight = fight, take_orbs = take_orbs }
