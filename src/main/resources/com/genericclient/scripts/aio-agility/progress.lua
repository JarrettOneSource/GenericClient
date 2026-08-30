local config = gc.require("config")

local start_xp = 0

local function compact_rate(rate)
  if rate >= 1000 then return string.format("%.1fk", rate / 1000) end
  return tostring(math.floor(rate + 0.5))
end

local function format_seconds(seconds)
  if seconds < 60 then return tostring(math.floor(seconds + 0.5)) .. "s" end
  if seconds < 3600 then
    return string.format("%dm %02ds", math.floor(seconds / 60), math.floor(seconds) % 60)
  end
  return string.format("%dh %02dm", math.floor(seconds / 3600), math.floor(seconds / 60) % 60)
end

local function begin(xp)
  start_xp = xp
end

local function show(target, state)
  local agility = gc.read("skills").agility
  local runtime_millis = gc.read("runtime").script_runtime_millis
  local gained = math.max(0, agility.xp - start_xp)
  local rate = runtime_millis <= 0 and 0 or gained * 3600000 / runtime_millis
  local next_xp = agility.level >= 99 and agility.xp or config.xp_for_level(agility.level + 1)
  local next_eta = rate <= 0 and "--" or format_seconds(math.max(0, next_xp - agility.xp) * 3600 / rate)
  gc.overlay {
    {
      label = "Agility",
      value = tostring(agility.level) .. " / " .. tostring(target) .. "  +" .. tostring(gained) .. " XP",
    },
    { label = "XP/hour", value = compact_rate(rate) },
    { label = "Next level", value = next_eta },
    { label = "State", value = state },
  }
end

return { begin = begin, show = show }
