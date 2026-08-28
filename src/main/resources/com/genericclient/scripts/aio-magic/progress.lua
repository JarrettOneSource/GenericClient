local start_xp = 0

local function xp_for_level(level)
  local points = 0
  for current = 1, level - 1 do
    points = points + math.floor(current + 300 * 2 ^ (current / 7))
  end
  return math.floor(points / 4)
end

local function compact_rate(rate)
  if rate >= 1000 then
    return string.format("%.1fk", rate / 1000)
  end
  return tostring(math.floor(rate + 0.5))
end

local function format_eta(magic, rate)
  if magic.level >= 99 then
    return "Maxed"
  end
  if rate <= 0 then
    return "--"
  end
  local remaining = math.max(0, xp_for_level(magic.level + 1) - magic.xp)
  local seconds = math.floor(remaining * 3600 / rate + 0.5)
  if seconds < 60 then
    return tostring(seconds) .. "s"
  end
  if seconds < 3600 then
    return string.format("%dm %02ds", math.floor(seconds / 60), seconds % 60)
  end
  return string.format("%dh %02dm", math.floor(seconds / 3600), math.floor(seconds / 60) % 60)
end

local function begin(xp)
  start_xp = xp
end

local function show(target, state)
  local magic = gc.read("skills").magic
  local runtime_millis = gc.read("runtime").script_runtime_millis
  local gained = math.max(0, magic.xp - start_xp)
  local rate = runtime_millis <= 0 and 0 or gained * 3600000 / runtime_millis
  gc.overlay {
    {
      label = "Magic",
      value = tostring(magic.level) .. " / " .. tostring(target) .. "  +" .. tostring(gained) .. " XP",
    },
    { label = "XP/hour", value = compact_rate(rate) },
    { label = "Next level", value = format_eta(magic, rate) },
    { label = "State", value = state },
  }
end

return {
  begin = begin,
  show = show,
}
