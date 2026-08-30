local games_necklace_ids = { 3853, 3855, 3857, 3859, 3861, 3863, 3865, 3867 }
local dueling_ring_ids = { 2552, 2554, 2556, 2558, 2560, 2562, 2564, 2566 }

local function quantity(container, id)
  if not container or not container.items then return 0 end
  local total = 0
  for _, item in ipairs(container.items) do
    if item.id == id then total = total + item.quantity end
  end
  return total
end

local function has_item(ids)
  local inventory = gc.read("inventory")
  local equipment = gc.read("equipment")
  for _, id in ipairs(ids) do
    if quantity(inventory, id) + quantity(equipment, id) > 0 then return true end
  end
  return false
end

local function inventory_item(ids)
  local inventory = gc.read("inventory")
  for _, id in ipairs(ids) do
    if quantity(inventory, id) > 0 then return id end
  end
  return nil
end

local function equipment_item(ids)
  local equipment = gc.read("equipment")
  for _, id in ipairs(ids) do
    if quantity(equipment, id) > 0 then return id end
  end
  return nil
end

local function wait_for_arrival(arrived, ticks)
  for _ = 1, ticks do
    gc.await { event = "game.tick" }
    local world = gc.read("player").world
    if arrived(world) then return world end
  end
  return nil
end

local function teleport(ids, item_label, choice_name, arrived, result, breaks)
  if breaks == nil then breaks = true end
  local item = inventory_item(ids)
  if not item then
    local equipped = equipment_item(ids)
    if not equipped then return { status = item_label .. "_not_carried" } end
    local removed = gc.await {
      action = {
        type = "equipment.interact",
        id = equipped,
        action = "Remove",
      },
      breaks = breaks,
    }
    if removed.status ~= "dispatched" then
      return { status = item_label .. "_remove_failed", receipt = removed }
    end
    for _ = 1, 10 do
      gc.await { event = "game.tick" }
      if inventory_item(ids) then
        return teleport(ids, item_label, choice_name, arrived, result, breaks)
      end
    end
    return { status = item_label .. "_remove_unverified", receipt = removed }
  end
  local rubbed = gc.await {
    action = { type = "item.interact", id = item, action = "Rub" },
    breaks = breaks,
  }
  if rubbed.status ~= "dispatched" then
    return { status = item_label .. "_rub_failed", receipt = rubbed }
  end
  local chosen
  for _ = 1, 20 do
    gc.await { event = "game.tick" }
    local dialogue = gc.read("dialogue")
    if dialogue.type == "choice" then
      for _, option in ipairs(dialogue.options) do
        if option.text == choice_name .. "." or option.text == choice_name then
          chosen = gc.await {
            action = { type = "dialogue.choose", text = option.text },
            breaks = breaks,
          }
          break
        end
      end
    end
    if chosen then break end
  end
  if not chosen or chosen.status ~= "dispatched" then
    return { status = item_label .. "_choice_failed", destination = choice_name, rub = rubbed, choice = chosen }
  end
  local world = wait_for_arrival(arrived, 20)
  if world then return { status = "complete", result = result, world = world } end
  return {
    status = item_label .. "_teleport_unverified",
    destination = choice_name,
    rub = rubbed,
    choice = chosen,
  }
end

local function teleport_to_burthorpe(breaks)
  return teleport(
    games_necklace_ids,
    "games_necklace",
    "Burthorpe",
    function(world) return world.y >= 3500 end,
    "burthorpe_teleport_verified",
    breaks)
end

local function teleport_to_barbarian_outpost(breaks)
  return teleport(
    games_necklace_ids,
    "games_necklace",
    "Barbarian Outpost",
    function(world) return world.x >= 2500 and world.x <= 2540 and world.y >= 3550 end,
    "barbarian_outpost_teleport_verified",
    breaks)
end

local function teleport_to_castle_wars(breaks)
  return teleport(
    dueling_ring_ids,
    "ring_of_dueling",
    "Castle Wars Arena",
    function(world) return world.x >= 2425 and world.x <= 2455 and world.y >= 3075 and world.y <= 3105 end,
    "castle_wars_teleport_verified",
    breaks)
end

local function teleport_to_ferox_enclave(breaks)
  return teleport(
    dueling_ring_ids,
    "ring_of_dueling",
    "Ferox Enclave",
    function(world)
      return world.x >= 3120 and world.x <= 3165 and world.y >= 3600 and world.y <= 3650
    end,
    "ferox_enclave_teleport_verified",
    breaks)
end

local function teleport_to_emirs_arena(breaks)
  return teleport(
    dueling_ring_ids,
    "ring_of_dueling",
    "Emir's Arena",
    function(world)
      return world.x >= 3290 and world.x <= 3340 and world.y >= 3210 and world.y <= 3260
    end,
    "emirs_arena_teleport_verified",
    breaks)
end

return {
  has_necklace = function() return has_item(games_necklace_ids) end,
  has_dueling_ring = function() return has_item(dueling_ring_ids) end,
  teleport_to_burthorpe = teleport_to_burthorpe,
  teleport_to_barbarian_outpost = teleport_to_barbarian_outpost,
  teleport_to_castle_wars = teleport_to_castle_wars,
  teleport_to_ferox_enclave = teleport_to_ferox_enclave,
  teleport_to_emirs_arena = teleport_to_emirs_arena,
}
