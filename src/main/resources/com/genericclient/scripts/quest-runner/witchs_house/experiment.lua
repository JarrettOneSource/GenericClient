local config = gc.require("witch_config")

local experiment = config.experiment

local function in_zone(world, zone)
  return world and world.plane == zone.plane and world.x >= zone.x1 and world.x <= zone.x2 and
    world.y >= zone.y1 and world.y <= zone.y2
end

local function inventory_quantity(id)
  local total = 0
  for _, item in ipairs(gc.read("inventory").items) do
    if item.id == id then total = total + item.quantity end
  end
  return total
end

local function npc(id)
  return gc.read("npcs", { id = id, within = 12, limit = 1 })[1]
end

local function current_form()
  for index, id in ipairs(experiment.forms) do
    local found = npc(id)
    if found then return index, found end
  end
  return nil, nil
end

local function walk(world, timeout)
  return gc.await {
    action = { type = "walk.to", destination = world, within = 0 },
    breaks = false,
    timeout = { game_ticks = timeout or 60 },
  }
end

local function wait_for_shed(expected, ticks)
  for _ = 1, ticks do
    gc.await { event = "game.tick" }
    local player = gc.read("player")
    if in_zone(player.world, config.zones.shed) == expected then return true end
  end
  return false
end

local function wait_for_form(id, ticks)
  for _ = 1, ticks do
    local found = npc(id)
    if found then return found end
    gc.await { event = "game.tick" }
  end
  return nil
end

local function heal_for_next_lure()
  local receipts = {}
  for _ = 1, 6 do
    local player = gc.read("player")
    if player.current_hitpoints >= player.max_hitpoints then
      return true, receipts
    end
    if inventory_quantity(1993) == 0 then
      return nil, { status = "combat_food_exhausted", receipts = receipts }
    end
    local receipt = gc.await {
      action = { type = "item.interact", id = 1993, action = "Drink" },
      breaks = false,
    }
    table.insert(receipts, receipt)
    if receipt.status ~= "dispatched" then
      return nil, { status = "combat_food_failed", receipt = receipt, receipts = receipts }
    end
    gc.await { event = "game.tick" }
  end
  return nil, { status = "combat_heal_timeout", receipts = receipts }
end

local function enter_shed()
  if in_zone(gc.read("player").world, config.zones.shed) then
    return { status = "complete", result = "already_in_shed" }
  end
  if inventory_quantity(2411) == 0 then
    return { status = "shed_key_missing" }
  end
  local unlock = gc.await {
    action = {
      type = "item.use_on_object",
      item_id = 2411,
      object_id = experiment.door.id,
      world = experiment.door.world,
      within = 3,
    },
    breaks = false,
  }
  if unlock.status ~= "dispatched" then
    return { status = "shed_unlock_failed", receipt = unlock }
  end
  if wait_for_shed(true, 8) then
    return { status = "complete", result = "shed_entered", unlock = unlock }
  end
  local opened = gc.await {
    action = {
      type = "object.interact",
      id = experiment.door.id,
      action = "Open",
      world = experiment.door.world,
      within = 3,
    },
    breaks = false,
  }
  if opened.status ~= "dispatched" then
    return { status = "shed_open_failed", unlock = unlock, receipt = opened }
  end
  local crossed = walk(experiment.spawn, 30)
  if crossed.status ~= "arrived" or not in_zone(gc.read("player").world, config.zones.shed) then
    return { status = "shed_entry_unverified", unlock = unlock, open = opened, walk = crossed }
  end
  return { status = "complete", result = "shed_entered", unlock = unlock, open = opened, walk = crossed }
end

local function ensure_first_form()
  local index, found = current_form()
  if index then return index, found end
  local attempt = gc.await {
    action = {
      type = "ground_item.take",
      id = experiment.ball.id,
      world = experiment.ball.world,
      within = 10,
    },
    breaks = false,
  }
  if attempt.status ~= "dispatched" then
    return nil, { status = "experiment_spawn_failed", receipt = attempt }
  end
  found = wait_for_form(experiment.forms[1], 20)
  if not found then
    return nil, { status = "experiment_spawn_unverified", receipt = attempt }
  end
  return 1, found
end

local function wait_at(id, world, ticks)
  for _ = 1, ticks do
    gc.await { event = "game.tick" }
    local found = npc(id)
    if found and found.world.x == world.x and found.world.y == world.y then
      return found
    end
  end
  return nil
end

local function wait_at_or_transition(id, next_id, world, ticks)
  for _ = 1, ticks do
    gc.await { event = "game.tick" }
    local found = npc(id)
    if found and found.world.x == world.x and found.world.y == world.y then
      return "arrived"
    end
    if next_id and npc(next_id) then return "transitioned" end
  end
  return "timeout"
end

local function wait_for_north_displacement(id, next_id, ticks)
  local previous = nil
  local stable = 0
  for _ = 1, ticks do
    gc.await { event = "game.tick" }
    if next_id and npc(next_id) then return "transitioned" end
    local found = npc(id)
    if found then
      if found.world.x == experiment.first_form_start.x and
        found.world.y == experiment.first_form_start.y then
        return "complete"
      end
      local key = found.world.x .. ":" .. found.world.y
      stable = key == previous and stable + 1 or 1
      previous = key
      if stable >= 2 and (found.world.x ~= experiment.first_form_walk.x or
        found.world.y ~= experiment.first_form_walk.y) then
        return "wrong"
      end
    end
  end
  return "timeout"
end

local function wait_player_at(world, ticks, stable_ticks)
  local stable = 0
  local observed = gc.read("player")
  for _ = 1, ticks do
    gc.await { event = "game.tick" }
    observed = gc.read("player")
    if observed.world.x == world.x and observed.world.y == world.y and
      observed.world.plane == world.plane then
      stable = stable + 1
      if stable >= (stable_ticks or 1) then return observed end
    else
      stable = 0
    end
  end
  return nil, observed
end

local function wait_stable(id, ticks)
  local previous = nil
  local stable = 0
  for _ = 1, ticks do
    gc.await { event = "game.tick" }
    local found = npc(id)
    if found then
      local key = found.world.x .. ":" .. found.world.y
      if key == previous then
        stable = stable + 1
      else
        previous = key
        stable = 1
      end
      if stable >= 2 then return found end
    end
  end
  return nil
end

local function attack_stable(id)
  local receipt = nil
  for _ = 1, 3 do
    if not wait_stable(id, 30) then
      return nil, { step = "stable_target_timeout" }
    end
    receipt = gc.await {
      action = {
        type = "npc.interact",
        id = id,
        action = "Attack",
        within = 12,
      },
      breaks = false,
      timeout = { game_ticks = 40 },
    }
    if receipt.status == "dispatched" then return receipt end
  end
  return nil, { step = "initial_aggro", receipt = receipt }
end

local function dismiss_open_dialogue()
  local dialogue = gc.read("dialogue")
  if dialogue.type ~= "continue" then return false end
  local continued = gc.await {
    action = { type = "dialogue.continue" },
    breaks = false,
    timeout = { game_ticks = 20 },
  }
  if continued.status ~= "dispatched" then return nil, continued end
  gc.await { event = "game.tick" }
  return true
end

local function dismiss_combat_dialogue(id)
  local dismissed, failure = dismiss_open_dialogue()
  if dismissed == nil then return nil, failure end
  if not dismissed then return true end
  local attack, attack_failure = attack_stable(id)
  if not attack then return nil, attack_failure end
  return true
end

local function establish_north_safespot(form_index)
  local id = experiment.forms[form_index]
  local next_id = experiment.forms[form_index + 1]
  local cycles = {}
  for cycle = 1, 8 do
    if next_id and npc(next_id) then
      return { status = "transitioned", cycles = cycles }
    end
    local dismissed, dialogue_failure = dismiss_open_dialogue()
    if dismissed == nil then
      return nil, { status = "combat_dialogue_failed", receipt = dialogue_failure }
    end
    local start = walk(experiment.first_form_start, 40)
    if start.status ~= "arrived" then
      return nil, { status = "north_start_failed", cycle = cycle, receipt = start }
    end
    local healed, heal = heal_for_next_lure()
    if not healed then return nil, heal end
    local attack, attack_failure = attack_stable(id)
    if not attack then
      if next_id and npc(next_id) then
        return { status = "transitioned", cycles = cycles }
      end
      return nil, { status = "north_aggro_failed", cycle = cycle, receipt = attack_failure }
    end
    local approach = wait_at_or_transition(id, next_id, experiment.first_form_walk, 24)
    if approach == "transitioned" then
      return { status = "transitioned", cycles = cycles }
    end
    if approach ~= "arrived" then
      return nil, { status = "north_approach_failed", cycle = cycle }
    end
    local under = walk(experiment.first_form_walk, 20)
    if under.status ~= "arrived" then
      return nil, { status = "north_walk_under_failed", cycle = cycle, receipt = under }
    end
    local displacement = wait_for_north_displacement(id, next_id, 6)
    table.insert(cycles, {
      cycle = cycle,
      displacement = displacement,
      hitpoints = gc.read("player").current_hitpoints,
      npc = npc(id) and npc(id).world or nil,
    })
    if displacement == "transitioned" then
      return { status = "transitioned", cycles = cycles }
    end
    if displacement == "complete" then
      local diagonal = walk(experiment.first_form_safe, 20)
      if diagonal.status ~= "arrived" then
        return nil, { status = "north_safe_walk_failed", receipt = diagonal, cycles = cycles }
      end
      local player, observed = wait_player_at(experiment.first_form_safe, 6, 2)
      if not player then
        return nil, { status = "north_safe_unstable", player = observed, cycles = cycles }
      end
      local found = npc(id)
      if not found or found.world.x ~= experiment.first_form_start.x or
        found.world.y ~= experiment.first_form_start.y then
        return nil, { status = "north_corner_unverified", player = player, npc = found, cycles = cycles }
      end
      return { status = "complete", cycles = cycles, player = player, npc = found }
    end
  end
  return nil, {
    status = "north_displacement_exhausted",
    player = gc.read("player"),
    npc = npc(id),
    cycles = cycles,
  }
end

local function defeat_form(form_index, safe_world, ticks)
  local id = experiment.forms[form_index]
  local next_id = experiment.forms[form_index + 1]
  if next_id and npc(next_id) then return { status = "transitioned" } end
  local attack, attack_failure = attack_stable(id)
  if not attack then
    if next_id and npc(next_id) then return { status = "transitioned" } end
    return nil, { status = "form_attack_failed", form = form_index, receipt = attack_failure }
  end
  local minimum_hp = gc.read("player").current_hitpoints
  for _ = 1, ticks do
    gc.await { event = "game.tick" }
    if next_id and npc(next_id) then
      return { status = "transitioned", minimum_hitpoints = minimum_hp }
    end
    if not next_id then
      local vars = gc.read("vars", { varps = { 226 } })
      if vars.varps[226] >= 6 and not npc(id) then
        return { status = "complete", varp = vars.varps[226], minimum_hitpoints = minimum_hp }
      end
    end
    if npc(id) then
      local resumed, resume_failure = dismiss_combat_dialogue(id)
      if not resumed then
        return nil, { status = "form_dialogue_resume_failed", form = form_index, receipt = resume_failure }
      end
    end
    local player = gc.read("player")
    minimum_hp = math.min(minimum_hp, player.current_hitpoints)
    if not in_zone(player.world, config.zones.shed) then
      return nil, { status = "form_left_shed", form = form_index, player = player }
    end
    if player.world.x ~= safe_world.x or player.world.y ~= safe_world.y then
      return nil, { status = "form_position_lost", form = form_index, player = player }
    end
  end
  return nil, { status = "form_timeout", form = form_index, minimum_hitpoints = minimum_hp }
end

local function establish_south_safespot(expected_id)
  local moved = walk(experiment.south_safespot, 30)
  if moved.status ~= "arrived" then
    return nil, { status = "south_safe_walk_failed", receipt = moved }
  end
  local player, observed = wait_player_at(experiment.south_safespot, 6, 2)
  if not player then return nil, { status = "south_safe_unstable", player = observed } end
  for _ = 1, 12 do
    gc.await { event = "game.tick" }
    player = gc.read("player")
    if player.world.x ~= experiment.south_safespot.x or
      player.world.y ~= experiment.south_safespot.y or not npc(expected_id) then
      return nil, { status = "south_safe_unverified", player = player, npc = npc(expected_id) }
    end
  end
  return { status = "complete", player = player, npc = npc(expected_id) }
end

local function run_all_forms()
  local entered = enter_shed()
  if entered.status ~= "complete" then return entered end
  local index, found = ensure_first_form()
  if not index then return found end
  local receipts = { entered = entered, started_form = index }

  for form_index = index, 2 do
    if npc(experiment.forms[form_index]) then
      local safe, safe_failure = establish_north_safespot(form_index)
      if not safe then return safe_failure end
      receipts["form_" .. form_index .. "_safe"] = safe
      if safe.status ~= "transitioned" then
        local fight, fight_failure = defeat_form(
          form_index, experiment.first_form_safe, form_index == 1 and 300 or 420)
        if not fight then return fight_failure end
        receipts["form_" .. form_index .. "_fight"] = fight
      end
    end
  end

  if not npc(experiment.forms[3]) and npc(experiment.forms[2]) then
    return { status = "bear_transition_missing", receipts = receipts }
  end
  if npc(experiment.forms[3]) then
    local safe, safe_failure = establish_south_safespot(experiment.forms[3])
    if not safe then return safe_failure end
    receipts.form_3_safe = safe
    local fight, fight_failure = defeat_form(3, experiment.south_safespot, 600)
    if not fight then return fight_failure end
    receipts.form_3_fight = fight
  end
  if not npc(experiment.forms[4]) then
    return { status = "wolf_transition_missing", receipts = receipts }
  end
  local safe, safe_failure = establish_south_safespot(experiment.forms[4])
  if not safe then return safe_failure end
  receipts.form_4_safe = safe
  local fight, fight_failure = defeat_form(4, experiment.south_safespot, 720)
  if not fight then return fight_failure end
  receipts.form_4_fight = fight
  gc.await { action = { type = "mouse.offscreen" }, breaks = false }
  return {
    status = "experiment_complete",
    varp = gc.read("vars", { varps = { 226 } }).varps[226],
    player = gc.read("player"),
    receipts = receipts,
  }
end

return { run_all_forms = run_all_forms }
