local necklace_ids = { 3853, 3855, 3857, 3859, 3861, 3863, 3865, 3867 }

local function quantity(container, id)
  if not container or not container.items then return 0 end
  local total = 0
  for _, item in ipairs(container.items) do
    if item.id == id then total = total + item.quantity end
  end
  return total
end

local function has_necklace()
  local inventory = gc.read("inventory")
  local equipment = gc.read("equipment")
  for _, id in ipairs(necklace_ids) do
    if quantity(inventory, id) + quantity(equipment, id) > 0 then return true end
  end
  return false
end

local function inventory_necklace()
  local inventory = gc.read("inventory")
  for _, id in ipairs(necklace_ids) do
    if quantity(inventory, id) > 0 then return id end
  end
  return nil
end

local function teleport_to_burthorpe()
  local necklace = inventory_necklace()
  if not necklace then return { status = "games_necklace_not_in_inventory" } end
  local rubbed = gc.await {
    action = { type = "item.interact", id = necklace, action = "Rub" },
    breaks = false,
  }
  if rubbed.status ~= "dispatched" then
    return { status = "games_necklace_rub_failed", receipt = rubbed }
  end
  local chosen
  for _ = 1, 20 do
    gc.await { event = "game.tick" }
    local dialogue = gc.read("dialogue")
    if dialogue.type == "choice" then
      for _, option in ipairs(dialogue.options) do
        if option.text == "Burthorpe." or option.text == "Burthorpe" then
          chosen = gc.await {
            action = { type = "dialogue.choose", text = option.text },
            breaks = false,
          }
          break
        end
      end
    end
    if chosen then break end
  end
  if not chosen or chosen.status ~= "dispatched" then
    return { status = "burthorpe_choice_failed", rub = rubbed, choice = chosen }
  end
  for _ = 1, 20 do
    gc.await { event = "game.tick" }
    local world = gc.read("player").world
    if world.y >= 3500 then
      return { status = "complete", result = "burthorpe_teleport_verified", world = world }
    end
  end
  return { status = "burthorpe_teleport_unverified", rub = rubbed, choice = chosen }
end

return {
  has_necklace = has_necklace,
  teleport_to_burthorpe = teleport_to_burthorpe,
}
