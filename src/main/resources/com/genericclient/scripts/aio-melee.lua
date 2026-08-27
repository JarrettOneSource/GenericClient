-- genericclient-interface: 2

local targets = {
  ["2"] = 83,
  ["5"] = 388,
  ["10"] = 1154,
  ["20"] = 4470,
  ["30"] = 13363,
  ["40"] = 37224,
  ["50"] = 101333,
  ["60"] = 273742,
  ["70"] = 737627,
  ["75"] = 1210421,
  ["80"] = 1986068,
  ["90"] = 5346332,
  ["99"] = 13034431,
}

local skills = {
  attack = { label = "Attack", style = 0, cap = 80 },
  strength = { label = "Strength", style = 1, cap = 99 },
  defence = { label = "Defence", style = 3, cap = 75 },
}

local methods = {
  lumbridge_goblins = {
    label = "Lumbridge goblins",
    destination = { x = 3245, y = 3245, plane = 0 },
    within = 8,
    npc = "Goblin",
    npc_radius = 15,
    maximum_level = 30,
  },
}

local function distance(a, b)
  if a.plane ~= b.plane then
    return 99999
  end
  return math.max(math.abs(a.x - b.x), math.abs(a.y - b.y))
end

local function overlay(skill, level, target, method, state)
  gc.overlay {
    { label = skill.label, value = tostring(level) .. " / " .. tostring(target) },
    { label = "Method", value = method.label },
    { label = "State", value = state },
  }
end

local function leave_combat(reason)
  gc.log("info", "melee-disengage", { reason = reason })
  gc.await {
    action = { type = "walk.random" },
    breaks = false,
    timeout = { game_ticks = 12 },
  }
  return gc.await {
    action = { type = "mouse.offscreen" },
    breaks = false,
  }
end

return {
  inputs = {
    {
      id = "skill",
      label = "Skill",
      type = "choice",
      default = "attack",
      choices = {
        { value = "attack", label = "Attack" },
        { value = "strength", label = "Strength" },
        { value = "defence", label = "Defence" },
      },
    },
    {
      id = "target_level",
      label = "Target level",
      type = "choice",
      default = "2",
      choices = {
        { value = "2", label = "2" },
        { value = "5", label = "5" },
        { value = "10", label = "10" },
        { value = "20", label = "20" },
        { value = "30", label = "30" },
        { value = "40", label = "40" },
        { value = "50", label = "50" },
        { value = "60", label = "60" },
        { value = "70", label = "70" },
        { value = "75", label = "75" },
        { value = "80", label = "80" },
        { value = "90", label = "90" },
        { value = "99", label = "99" },
      },
    },
    {
      id = "method",
      label = "Method",
      type = "choice",
      default = "auto",
      choices = {
        { value = "auto", label = "Auto" },
        { value = "lumbridge_goblins", label = "Lumbridge goblins" },
      },
    },
  },

  actions = {
    { id = "stop_after_kill", label = "Stop after kill" },
  },

  run = function(input)
    local skill = assert(skills[input.skill], "Unknown melee skill")
    local target_level = assert(tonumber(input.target_level), "Invalid target level")
    local target_xp = assert(targets[input.target_level], "Unsupported target level")
    assert(target_level <= skill.cap, skill.label .. " target exceeds the account cap")

    local method_id = input.method
    if method_id == "auto" then
      method_id = "lumbridge_goblins"
    end
    local method = assert(methods[method_id], "Unknown melee method")
    assert(target_level <= method.maximum_level,
      method.label .. " is not implemented above level " .. tostring(method.maximum_level))

    gc.await { event = "game.tick" }
    local start = gc.read("skills")[input.skill]
    local start_xp = start.xp
    overlay(skill, start.level, target_level, method, "Securing combat")
    local retaliate = gc.await {
      action = { type = "combat.set_auto_retaliate", enabled = false },
      breaks = false,
      timeout = { game_ticks = 20 },
    }
    if retaliate.status ~= "set" and retaliate.status ~= "unchanged" then
      gc.log("error", "melee-auto-retaliate-failed", retaliate)
      return { status = "auto_retaliate_failed", receipt = retaliate }
    end

    if start.level >= target_level or start.xp >= target_xp then
      overlay(skill, start.level, target_level, method, "Target already met")
      leave_combat("target_already_met")
      return {
        status = "already_complete",
        skill = input.skill,
        level = start.level,
        xp = start.xp,
      }
    end

    overlay(skill, start.level, target_level, method, "Setting style")
    local style = gc.await {
      action = { type = "combat.set_style", style = skill.style },
      breaks = false,
      timeout = { game_ticks = 20 },
    }
    if style.status ~= "set" and style.status ~= "unchanged" then
      gc.log("error", "melee-style-failed", style)
      return { status = "style_failed", receipt = style }
    end

    local player = gc.read("player")
    if distance(player.world, method.destination) > method.within then
      overlay(skill, start.level, target_level, method, "Travelling")
      local walk = gc.await {
        action = {
          type = "walk.to",
          destination = method.destination,
          within = method.within,
        },
        timeout = { game_ticks = 900 },
      }
      if walk.status ~= "arrived" then
        gc.log("error", "melee-travel-failed", walk)
        return { status = "travel_failed", receipt = walk }
      end
      gc.phase("melee." .. method_id .. ".arrived")
    end

    local stop_requested = false
    local low_hitpoints = false
    while true do
      local current = gc.read("skills")[input.skill]
      if current.level >= target_level or current.xp >= target_xp then
        break
      end
      local hitpoints = gc.read("skills").hitpoints.boosted_level
      if hitpoints <= 4 then
        overlay(skill, current.level, target_level, method, "Low hitpoints")
        leave_combat("low_hitpoints")
        return {
          status = "low_hitpoints",
          skill = input.skill,
          level = current.level,
          xp = current.xp,
          hitpoints = hitpoints,
        }
      end

      local queued = gc.next_action()
      if queued == "stop_after_kill" then
        stop_requested = true
      end
      if stop_requested then
        overlay(skill, current.level, target_level, method, "Stopping")
        leave_combat("requested")
        return {
          status = "stopped",
          skill = input.skill,
          level = current.level,
          xp = current.xp,
        }
      end

      overlay(skill, current.level, target_level, method, "Finding target")
      local attack = gc.await {
        action = {
          type = "npc.interact",
          name = method.npc,
          action = "Attack",
          within = method.npc_radius,
        },
        breaks = false,
        timeout = { game_ticks = 30 },
      }

      if attack.status == "dispatched" then
        overlay(skill, current.level, target_level, method, "In combat")
        gc.await { ticks = 2 }
        local idle_ticks = 0
        while idle_ticks < 2 do
          gc.await { event = "game.tick" }
          current = gc.read("skills")[input.skill]
          if current.level >= target_level or current.xp >= target_xp then
            break
          end
          if gc.read("skills").hitpoints.boosted_level <= 4 then
            low_hitpoints = true
            break
          end
          local player_state = gc.read("player")
          if player_state.interacting then
            idle_ticks = 0
          else
            idle_ticks = idle_ticks + 1
          end
          if gc.next_action() == "stop_after_kill" then
            stop_requested = true
          end
        end
        if low_hitpoints then
          local hp = gc.read("skills").hitpoints.boosted_level
          overlay(skill, current.level, target_level, method, "Low hitpoints")
          leave_combat("low_hitpoints")
          return {
            status = "low_hitpoints",
            skill = input.skill,
            level = current.level,
            xp = current.xp,
            hitpoints = hp,
          }
        end
      else
        gc.log("warn", "melee-target-retry", attack)
        gc.await { ticks = 2 }
      end
    end

    local final = gc.read("skills")[input.skill]
    overlay(skill, final.level, target_level, method, "Complete")
    leave_combat("target_reached")
    gc.phase("melee." .. input.skill .. ".target_reached")
    local result = {
      status = "complete",
      skill = input.skill,
      target_level = target_level,
      start_xp = start_xp,
      final_xp = final.xp,
      gained_xp = final.xp - start_xp,
      final_level = final.level,
      method = method_id,
    }
    gc.log("info", "melee-complete", result)
    return result
  end,
}
