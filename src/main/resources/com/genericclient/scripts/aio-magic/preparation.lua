local progress = gc.require("progress")
local supplies = gc.require("supplies")

local quantity = supplies.quantity

local function distance(a, b)
  if a.plane ~= b.plane then
    return 99999
  end
  return math.max(math.abs(a.x - b.x), math.abs(a.y - b.y))
end

local function wait_ticks(count)
  return gc.await { ticks = count }
end

local function ensure_at_ge(target)
  local player = gc.read("player")
  local ge = { x = 3165, y = 3491, plane = 0 }
  if distance(player.world, ge) > 8 then
    progress.show(target, "Travelling to GE")
    local walk = gc.await {
      action = { type = "walk.to", destination = ge, within = 8 },
      timeout = { game_ticks = 1200 },
    }
    if walk.status ~= "arrived" then
      return nil, { status = "ge_travel_failed", receipt = walk }
    end
  end
  return true
end

local function open_bank()
  local bank = gc.read("bank")
  if bank and bank.open then
    return bank
  end
  local function click_bank()
    return gc.await {
      action = { type = "npc.interact", name = "Banker", action = "Bank", within = 10 },
      activity = "banking",
      breaks = true,
      timeout = { game_ticks = 30 },
    }
  end
  local receipt = click_bank()
  if receipt.status ~= "dispatched" then
    gc.await {
      action = { type = "ui.close" },
      activity = "banking",
      breaks = true,
    }
    wait_ticks(2)
    receipt = click_bank()
  end
  if receipt.status ~= "dispatched" then
    return nil, { status = "bank_open_failed", receipt = receipt }
  end
  for _ = 1, 20 do
    gc.await { event = "game.tick" }
    bank = gc.read("bank")
    if bank and bank.open and bank.available then
      return bank
    end
  end
  return nil, { status = "bank_snapshot_unavailable", bank = bank }
end

local function missing_items(plan, bank)
  local inventory = gc.read("inventory")
  local equipment = gc.read("equipment")
  local missing = {}
  for _, item in ipairs(plan) do
    local owned = quantity(bank, item.id) + quantity(inventory, item.id) + quantity(equipment, item.id)
    if owned < item.quantity then
      table.insert(missing, {
        id = item.id,
        name = item.name,
        quantity = item.quantity - owned,
        maximum_unit_price = item.maximum_unit_price,
      })
    end
  end
  return missing
end

local function acquire_missing(missing, target)
  local maximum_spend = 0
  for _, item in ipairs(missing) do
    maximum_spend = maximum_spend + item.quantity * item.maximum_unit_price
  end
  progress.show(target, "Withdrawing coins")
  local coins = gc.await {
    action = {
      type = "bank.loadout",
      items = { { id = 995, quantity = maximum_spend } },
      minimum_free_slots = 27,
      close = true,
    },
    breaks = true,
    timeout = { game_ticks = 200 },
  }
  if coins.status ~= "complete" then
    return nil, { status = "coin_loadout_failed", receipt = coins }
  end
  wait_ticks(2)

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
    return nil, { status = "exchange_open_failed", receipt = exchange }
  end
  wait_ticks(3)

  for _, item in ipairs(missing) do
    progress.show(target, "Buying " .. item.name)
    local purchase = gc.await {
      action = {
        type = "ge.buy",
        item_id = item.id,
        item_name = item.name,
        quantity = item.quantity,
        maximum_unit_price = item.maximum_unit_price,
        minimum_cash_reserve = 5000000,
      },
      breaks = true,
      timeout = { game_ticks = 300 },
    }
    if purchase.status ~= "complete" then
      return nil, { status = "purchase_incomplete", item = item, receipt = purchase }
    end
  end

  gc.await {
    action = { type = "ui.close" },
    activity = "trading",
    breaks = true,
  }
  wait_ticks(2)
  return true
end

local function prepare_loadout(plan, restock, target)
  local bank, bank_error = open_bank()
  if not bank then
    return nil, bank_error
  end
  local missing = missing_items(plan, bank)
  if #missing > 0 then
    if restock ~= "ge" then
      return nil, { status = "supplies_missing", items = missing }
    end
    local acquired, acquire_error = acquire_missing(missing, target)
    if not acquired then
      return nil, acquire_error
    end
    bank, bank_error = open_bank()
    if not bank then
      return nil, bank_error
    end
  end

  local items = {}
  for _, item in ipairs(plan) do
    table.insert(items, { id = item.id, quantity = item.quantity })
  end
  progress.show(target, "Preparing loadout")
  local loadout = gc.await {
    action = {
      type = "bank.loadout",
      items = items,
      minimum_free_slots = 16,
      close = true,
    },
    breaks = true,
    timeout = { game_ticks = 240 },
  }
  if loadout.status ~= "complete" then
    return nil, { status = "training_loadout_failed", receipt = loadout }
  end
  return true
end

local function equip_staff(id, target, name)
  if supplies.has_equipped(id) then
    return true
  end
  progress.show(target, "Equipping " .. name)
  local receipt = gc.await {
    action = { type = "item.interact", id = id, action = "Wield" },
    breaks = true,
    timeout = { game_ticks = 20 },
  }
  if receipt.status ~= "dispatched" then
    return nil, { status = "staff_equip_failed", receipt = receipt }
  end
  wait_ticks(2)
  if not supplies.has_equipped(id) then
    return nil, { status = "staff_equip_unverified", item_id = id }
  end
  return true
end

return {
  ensure_at_ge = ensure_at_ge,
  prepare_loadout = prepare_loadout,
  equip_staff = equip_staff,
}
