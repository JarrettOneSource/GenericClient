local config = gc.require("config")

local function quantity(container, id)
  if not container or not container.items then
    return 0
  end
  local total = 0
  for _, item in ipairs(container.items) do
    if item.id == id then
      total = total + item.quantity
    end
  end
  return total
end

local function has_equipped(id)
  return quantity(gc.read("equipment"), id) > 0
end

local function has_loadout(plan)
  local inventory = gc.read("inventory")
  local equipment = gc.read("equipment")
  for _, item in ipairs(plan) do
    if quantity(inventory, item.id) + quantity(equipment, item.id) < item.quantity then
      return false
    end
  end
  return true
end

local function casts_to(cursor_xp, ceiling_xp, required_xp, base_xp)
  local stage_end = math.min(ceiling_xp, required_xp)
  if cursor_xp >= stage_end then
    return 0, cursor_xp
  end
  return math.ceil((stage_end - cursor_xp) / base_xp), stage_end
end

local function add_runes(plan, id, name, quantity_needed)
  if quantity_needed > 0 then
    table.insert(plan, {
      id = id,
      name = name,
      quantity = quantity_needed,
      maximum_unit_price = 10,
    })
  end
end

local function plan_for(target_level, current_magic)
  local plan = {}
  for _, item in ipairs(assert(config.plans[target_level], "Missing supply plan")) do
    table.insert(plan, {
      id = item.id,
      name = item.name,
      quantity = item.quantity,
      maximum_unit_price = item.maximum_unit_price,
    })
  end

  local required_xp = assert(config.target_xp[target_level], "Missing target XP")
  local cursor_xp = current_magic.xp
  local mind_runes = 0
  local water_runes = 0
  local earth_runes = 0
  local air_runes = 0
  local casts

  casts, cursor_xp = casts_to(cursor_xp, 388, required_xp, 5.5)
  mind_runes = mind_runes + casts
  casts, cursor_xp = casts_to(cursor_xp, 969, required_xp, 7.5)
  mind_runes = mind_runes + casts
  water_runes = water_runes + casts
  casts, cursor_xp = casts_to(cursor_xp, 1833, required_xp, 9.5)
  mind_runes = mind_runes + casts
  earth_runes = earth_runes + casts * 2
  casts = casts_to(cursor_xp, required_xp, required_xp, 11.5)
  mind_runes = mind_runes + casts
  air_runes = air_runes + casts * 2

  add_runes(plan, 558, "Mind rune", mind_runes)
  add_runes(plan, 555, "Water rune", water_runes)
  add_runes(plan, 557, "Earth rune", earth_runes)
  add_runes(plan, 556, "Air rune", air_runes)
  return plan
end

return {
  quantity = quantity,
  has_equipped = has_equipped,
  has_loadout = has_loadout,
  plan_for = plan_for,
}
