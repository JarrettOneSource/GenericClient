local config = gc.require("config")
local progress = gc.require("progress")
local travel = gc.require("travel")

local function distance(a, b)
  if not a or not b or a.plane ~= b.plane then return 99999 end
  return math.max(math.abs(a.x - b.x), math.abs(a.y - b.y))
end

local function quantity(container, id)
  local total = 0
  for _, item in ipairs(container and container.items or {}) do
    if item.id == id then total = total + item.quantity end
  end
  return total
end

local function ensure_at_ge(target_level, target_xp)
  if distance(gc.read("player").world, config.ge) <= 8 then return true end
  if travel.has_dueling_ring() then
    progress.show(target_level, target_xp, "Teleporting")
    local teleported = travel.teleport_to_emirs_arena(true)
    if teleported.status ~= "complete" then
      return nil, { status = "prayer_ge_teleport_failed", receipt = teleported }
    end
  end
  progress.show(target_level, target_xp, "Travelling to GE")
  gc.activity("travel")
  local walked = gc.await {
    action = { type = "walk.to", destination = config.ge, within = 8, run = true },
    breaks = true,
    timeout = { game_ticks = 1200 },
  }
  if walked.status ~= "arrived" then
    return nil, { status = "prayer_ge_walk_failed", receipt = walked }
  end
  return true
end

local function open_bank()
  local bank = gc.read("bank")
  if bank and bank.open then return bank end
  local receipt = gc.await {
    action = { type = "npc.interact", name = "Banker", action = "Bank", within = 10 },
    activity = "banking",
    breaks = true,
    timeout = { game_ticks = 30 },
  }
  if receipt.status ~= "dispatched" then
    return nil, { status = "prayer_bank_open_failed", receipt = receipt }
  end
  for _ = 1, 20 do
    gc.await { event = "game.tick" }
    bank = gc.read("bank")
    if bank and bank.open and bank.available then return bank end
  end
  return nil, { status = "prayer_bank_snapshot_unavailable", bank = bank }
end

local function buy_bones(quantity_needed, target_level, target_xp)
  local maximum_spend = quantity_needed * config.bone.maximum_unit_price
  progress.show(target_level, target_xp, "Withdrawing coins")
  local coins = gc.await {
    action = {
      type = "bank.loadout",
      items = { { id = 995, quantity = maximum_spend } },
      minimum_free_slots = 27,
      close = true,
    },
    breaks = true,
    timeout = { game_ticks = 240 },
  }
  if coins.status ~= "complete" then
    return nil, { status = "prayer_coin_loadout_failed", receipt = coins }
  end

  local exchange = gc.await {
    action = {
      type = "npc.interact",
      name = "Grand Exchange Clerk",
      action = "Exchange",
      within = 10,
    },
    activity = "trading",
    breaks = true,
    timeout = { game_ticks = 30 },
  }
  if exchange.status ~= "dispatched" then
    return nil, { status = "prayer_exchange_open_failed", receipt = exchange }
  end
  gc.await { ticks = 3 }

  progress.show(target_level, target_xp, "Buying dragon bones")
  local purchase = gc.await {
    action = {
      type = "ge.buy",
      item_id = config.bone.id,
      item_name = config.bone.name,
      quantity = quantity_needed,
      maximum_unit_price = config.bone.maximum_unit_price,
      minimum_cash_reserve = config.minimum_cash_reserve,
      collect_mode = "bank",
    },
    breaks = true,
    timeout = { game_ticks = 400 },
  }
  if purchase.status ~= "complete" then
    return nil, { status = "prayer_bone_purchase_failed", receipt = purchase }
  end
  gc.await { action = { type = "ui.close" }, activity = "trading", breaks = true }
  gc.await { ticks = 2 }
  return purchase
end

local function prepare(required_bones, restock, target_level, target_xp)
  local reached, reach_error = ensure_at_ge(target_level, target_xp)
  if not reached then return nil, reach_error end
  local bank, bank_error = open_bank()
  if not bank then return nil, bank_error end
  local owned = quantity(bank, config.bone.id) + quantity(gc.read("inventory"), config.bone.id)
  local missing = math.max(0, required_bones - owned)
  if missing > 0 then
    if restock ~= "ge" then
      return nil, { status = "prayer_bones_missing", quantity = missing }
    end
    local purchased, purchase_error = buy_bones(missing, target_level, target_xp)
    if not purchased then return nil, purchase_error end
    bank, bank_error = open_bank()
    if not bank then return nil, bank_error end
    owned = quantity(bank, config.bone.id) + quantity(gc.read("inventory"), config.bone.id)
  end
  if owned < required_bones then
    return nil, { status = "prayer_bones_unverified", required = required_bones, owned = owned }
  end
  return true
end

return { prepare = prepare, open_bank = open_bank, quantity = quantity }
