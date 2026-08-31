local config = gc.require("config")
local preparation = gc.require("preparation")
local progress = gc.require("progress")

local function park_mouse()
  return gc.await { action = { type = "mouse.offscreen" }, breaks = false }
end

local function withdraw_bones(quantity)
  local bank, bank_error = preparation.open_bank()
  if not bank then return nil, bank_error end
  local receipt = gc.await {
    action = {
      type = "bank.loadout",
      items = { { id = config.bone.id, quantity = quantity } },
      minimum_free_slots = 1,
      close = true,
    },
    breaks = true,
    timeout = { game_ticks = 240 },
  }
  if receipt.status ~= "complete" then
    return nil, { status = "prayer_bone_withdrawal_failed", receipt = receipt }
  end
  return receipt
end

local function run(target_level, target_xp)
  gc.activity("skilling")
  local stop_requested = false
  local buried = 0
  while true do
    local prayer = gc.read("skills").prayer
    if prayer.xp >= target_xp or prayer.level >= target_level then break end
    if stop_requested then
      progress.show(target_level, target_xp, "Stopped")
      park_mouse()
      return { status = "stopped", level = prayer.level, xp = prayer.xp, bones_buried = buried }
    end

    local remaining = math.ceil((target_xp - prayer.xp) / config.bone.xp)
    local trip = math.min(config.inventory_size, remaining)
    progress.show(target_level, target_xp, "Withdrawing bones")
    local withdrawn, withdrawal_error = withdraw_bones(trip)
    if not withdrawn then return withdrawal_error end
    gc.activity("skilling")

    while preparation.quantity(gc.read("inventory"), config.bone.id) > 0 do
      prayer = gc.read("skills").prayer
      if prayer.xp >= target_xp or prayer.level >= target_level then break end
      if gc.next_action() == "stop_after_bone" then stop_requested = true end
      local before_xp = prayer.xp
      local before_quantity = preparation.quantity(gc.read("inventory"), config.bone.id)
      progress.show(target_level, target_xp, "Burying dragon bones")
      local receipt = gc.await {
        action = { type = "item.interact", id = config.bone.id, action = "Bury" },
        breaks = true,
        timeout = { game_ticks = 20 },
      }
      if receipt.status ~= "dispatched" then
        return { status = "prayer_bury_failed", receipt = receipt, level = prayer.level, xp = prayer.xp }
      end
      local verified = false
      for _ = 1, 10 do
        gc.await { event = "game.tick" }
        prayer = gc.read("skills").prayer
        local current_quantity = preparation.quantity(gc.read("inventory"), config.bone.id)
        if prayer.xp > before_xp and current_quantity < before_quantity then
          verified = true
          break
        end
      end
      if not verified then
        return {
          status = "prayer_xp_unverified",
          before_xp = before_xp,
          prayer = gc.read("skills").prayer,
          receipt = receipt,
        }
      end
      buried = buried + 1
      if stop_requested then break end
    end
  end

  local final = gc.read("skills").prayer
  progress.show(target_level, target_xp, "Complete")
  park_mouse()
  return {
    status = "complete",
    target_level = target_level,
    final_level = final.level,
    final_xp = final.xp,
    bones_buried = buried,
  }
end

return { run = run }
