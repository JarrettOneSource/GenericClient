local state_api = gc.require("shared_state")
local witch_config = gc.require("witch_config")
local waterfall_config = gc.require("waterfall_config")

local configs = {
  witchs_house = witch_config,
  waterfall = waterfall_config,
}

local ge = { x = 3165, y = 3491, plane = 0 }
local banks = {
  { x = 2946, y = 3368, plane = 0 },
  { x = 3092, y = 3245, plane = 0 },
  ge,
}

local function overlay(quest, phase)
  gc.overlay {
    { label = "Quest", value = configs[quest].label },
    { label = "Phase", value = phase:gsub("_", " ") },
    { label = "State", value = "Observed" },
  }
end

local function walk(destination, within, breaks)
  return gc.await {
    action = { type = "walk.to", destination = destination, within = within or 3 },
    breaks = breaks,
    timeout = { game_ticks = 900 },
  }
end

local function ensure_at_ge(quest)
  if state_api.distance(gc.read("player").world, ge) <= 8 then
    return true
  end
  overlay(quest, "travel_to_ge")
  local receipt = walk(ge, 8, true)
  if receipt.status ~= "arrived" then
    return nil, { status = "ge_travel_failed", receipt = receipt }
  end
  return true
end

local function ensure_at_bank(quest)
  local player = gc.read("player").world
  local nearest = banks[1]
  local nearest_distance = state_api.distance(player, nearest)
  for index = 2, #banks do
    local candidate_distance = state_api.distance(player, banks[index])
    if candidate_distance < nearest_distance then
      nearest = banks[index]
      nearest_distance = candidate_distance
    end
  end
  if nearest_distance <= 1 then return true end
  overlay(quest, "travel_to_bank")
  local receipt = walk(nearest, 1, true)
  if receipt.status ~= "arrived" then
    return nil, { status = "bank_travel_failed", receipt = receipt }
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
      breaks = false,
      timeout = { game_ticks = 30 },
    }
  end
  local receipt = click_bank()
  if receipt.status ~= "dispatched" then
    gc.await { action = { type = "ui.close" }, breaks = false }
    gc.await { ticks = 2 }
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

local function acquire_missing(missing, quest)
  local maximum_spend = 0
  for _, item in ipairs(missing) do
    if item.purchase == false then
      return nil, { status = "untradeable_supply_missing", item = item }
    end
    maximum_spend = maximum_spend + item.quantity * item.maximum_unit_price
  end
  overlay(quest, "withdraw_coins")
  local coins = gc.await {
    action = {
      type = "bank.loadout",
      items = { { id = 995, quantity = maximum_spend } },
      minimum_free_slots = 27,
      close = true,
    },
    breaks = false,
    timeout = { game_ticks = 200 },
  }
  if coins.status ~= "complete" then
    return nil, { status = "coin_loadout_failed", receipt = coins }
  end
  gc.await { ticks = 2 }

  local exchange = gc.await {
    action = {
      type = "npc.interact",
      name = "Grand Exchange Clerk",
      action = "Exchange",
      within = 10,
    },
    breaks = false,
    timeout = { game_ticks = 30 },
  }
  if exchange.status ~= "dispatched" then
    return nil, { status = "exchange_open_failed", receipt = exchange }
  end
  gc.await { ticks = 3 }

  for _, item in ipairs(missing) do
    overlay(quest, "buy_" .. item.name:lower():gsub("[^a-z0-9]+", "_"))
    local purchase = gc.await {
      action = {
        type = "ge.buy",
        item_id = item.id,
        item_name = item.name,
        quantity = item.quantity,
        maximum_unit_price = item.maximum_unit_price,
        minimum_cash_reserve = 5000000,
      },
      breaks = false,
      timeout = { game_ticks = 300 },
    }
    if purchase.status ~= "complete" then
      return nil, { status = "purchase_incomplete", item = item, receipt = purchase }
    end
  end

  gc.await { action = { type = "ui.close" }, breaks = false }
  gc.await { ticks = 2 }
  return true
end

local function prepare_items(quest, restock, loadout)
  local state = state_api.read(quest)
  if #state_api.missing_carried_items(state, loadout) == 0 then
    return true
  end
  local at_bank, travel_error = ensure_at_bank(quest)
  if not at_bank then
    return nil, travel_error
  end
  local bank, bank_error = open_bank()
  if not bank then
    return nil, bank_error
  end
  state = state_api.read(quest)
  local missing = state_api.missing_items(state, loadout)
  if #missing > 0 then
    if restock ~= "ge" then
      return nil, { status = "supplies_missing", items = missing }
    end
    if state_api.distance(gc.read("player").world, ge) > 8 then
      gc.await { action = { type = "ui.close" }, breaks = false }
      local at_ge, ge_error = ensure_at_ge(quest)
      if not at_ge then return nil, ge_error end
      bank, bank_error = open_bank()
      if not bank then return nil, bank_error end
      state = state_api.read(quest)
      missing = state_api.missing_items(state, loadout)
    end
    if #missing > 0 then
      local acquired, acquire_error = acquire_missing(missing, quest)
      if not acquired then
        return nil, acquire_error
      end
      bank, bank_error = open_bank()
      if not bank then
        return nil, bank_error
      end
      state = state_api.read(quest)
    end
  end

  local items = {}
  for _, item in ipairs(loadout) do
    local selected_id = item.id
    if state_api.total_owned(state, selected_id) == 0 then
      for _, alternative_id in ipairs(item.alternative_ids or {}) do
        if state_api.total_owned(state, alternative_id) > 0 then
          selected_id = alternative_id
          break
        end
      end
    end
    table.insert(items, { id = selected_id, quantity = item.quantity })
  end
  overlay(quest, "prepare_loadout")
  local receipt = gc.await {
    action = {
      type = "bank.loadout",
      items = items,
      minimum_free_slots = 4,
      close = true,
    },
    breaks = false,
    timeout = { game_ticks = 240 },
  }
  if receipt.status ~= "complete" then
    return nil, { status = "quest_loadout_failed", receipt = receipt }
  end
  state = state_api.read(quest)
  if #state_api.missing_carried_items(state, loadout) > 0 then
    return nil, { status = "quest_loadout_unverified" }
  end
  return true
end

local function prepare(quest, restock)
  return prepare_items(quest, restock, configs[quest].loadout)
end

return { prepare = prepare, prepare_items = prepare_items }
