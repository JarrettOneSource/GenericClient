local varp_ids = {
  witchs_house = 226,
  waterfall = 65,
}

local varbit_ids = {
  waterfall = { 9110 },
}

local function distance(a, b)
  if not a or a.plane ~= b.plane then return 99999 end
  return math.max(math.abs(a.x - b.x), math.abs(a.y - b.y))
end

local function quantity(container, id)
  if not container or not container.items then return 0 end
  local total = 0
  for _, item in ipairs(container.items) do
    if item.id == id then total = total + item.quantity end
  end
  return total
end

local function total_owned(state, id)
  return quantity(state.inventory, id) + quantity(state.equipment, id) + quantity(state.bank, id)
end

local function requirement_quantity(state, item, include_bank)
  local total = quantity(state.inventory, item.id) + quantity(state.equipment, item.id)
  if include_bank then total = total + quantity(state.bank, item.id) end
  for _, id in ipairs(item.alternative_ids or {}) do
    total = total + quantity(state.inventory, id) + quantity(state.equipment, id)
    if include_bank then total = total + quantity(state.bank, id) end
  end
  return total
end

local function missing_items(state, loadout)
  local missing = {}
  for _, item in ipairs(loadout) do
    local owned = requirement_quantity(state, item, true)
    if owned < item.quantity then
      table.insert(missing, {
        id = item.id,
        name = item.name,
        quantity = item.quantity - owned,
        maximum_unit_price = item.maximum_unit_price,
        purchase = item.purchase,
      })
    end
  end
  return missing
end

local function missing_carried_items(state, loadout)
  local missing = {}
  for _, item in ipairs(loadout) do
    if requirement_quantity(state, item, false) < item.quantity then
      table.insert(missing, item)
    end
  end
  return missing
end

local function matches_carried_loadout(state, loadout)
  if not state.inventory or not state.inventory.items or
    not state.equipment or not state.equipment.items then
    return false
  end
  if #state.equipment.items > 0 then return false end
  local allowed = {}
  for _, requirement in ipairs(loadout) do
    allowed[requirement.id] = true
    for _, id in ipairs(requirement.alternative_ids or {}) do allowed[id] = true end
    if requirement_quantity(state, requirement, false) ~= requirement.quantity then
      return false
    end
  end
  for _, item in ipairs(state.inventory.items) do
    if not allowed[item.id] then return false end
  end
  return true
end

local function read(quest)
  local varp_id = assert(varp_ids[quest], "Unknown quest state: " .. tostring(quest))
  local vars = gc.read("vars", {
    varps = { varp_id },
    varbits = varbit_ids[quest] or {},
  })
  return {
    quest = quest,
    varp = vars.varps[varp_id],
    varbits = vars.varbits or {},
    player = gc.read("player"),
    skills = gc.read("skills"),
    inventory = gc.read("inventory"),
    equipment = gc.read("equipment"),
    bank = gc.read("bank"),
    quests = gc.read("quests"),
    dialogue = gc.read("dialogue"),
  }
end

return {
  distance = distance,
  quantity = quantity,
  total_owned = total_owned,
  missing_items = missing_items,
  missing_carried_items = missing_carried_items,
  matches_carried_loadout = matches_carried_loadout,
  read = read,
}
