local start_xp = 0

local function compact_rate(rate)
  if rate >= 1000 then return string.format("%.1fk", rate / 1000) end
  return tostring(math.floor(rate + 0.5))
end

local function format_eta(target_xp, current_xp, rate)
  if current_xp >= target_xp then return "Complete" end
  if rate <= 0 then return "--" end
  local seconds = math.floor((target_xp - current_xp) * 3600 / rate + 0.5)
  if seconds < 60 then return tostring(seconds) .. "s" end
  if seconds < 3600 then
    return string.format("%dm %02ds", math.floor(seconds / 60), seconds % 60)
  end
  return string.format("%dh %02dm", math.floor(seconds / 3600), math.floor(seconds / 60) % 60)
end

local function begin(xp)
  start_xp = xp
end

local function show(target_level, target_xp, state)
  local prayer = gc.read("skills").prayer
  local runtime_millis = gc.read("runtime").script_runtime_millis
  local gained = math.max(0, prayer.xp - start_xp)
  local rate = runtime_millis <= 0 and 0 or gained * 3600000 / runtime_millis
  gc.overlay {
    {
      label = "Prayer",
      value = tostring(prayer.level) .. " / " .. tostring(target_level) ..
        "  +" .. tostring(gained) .. " XP",
    },
    { label = "XP/hour", value = compact_rate(rate) },
    { label = "ETA", value = format_eta(target_xp, prayer.xp, rate) },
    { label = "State", value = state },
  }
end

return { begin = begin, show = show }
