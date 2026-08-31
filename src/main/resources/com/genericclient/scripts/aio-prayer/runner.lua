local config = gc.require("config")
local preparation = gc.require("preparation")
local progress = gc.require("progress")
local training = gc.require("training")

local function run(target_level, restock)
  gc.await { event = "game.tick" }
  local target_xp = assert(config.target_xp[tostring(target_level)])
  local start = gc.read("skills").prayer
  progress.begin(start.xp)
  if start.xp >= target_xp or start.level >= target_level then
    progress.show(target_level, target_xp, "Target already met")
    gc.await { action = { type = "mouse.offscreen" }, breaks = false }
    return { status = "already_complete", level = start.level, xp = start.xp }
  end

  local required_bones = math.ceil((target_xp - start.xp) / config.bone.xp)
  progress.show(target_level, target_xp, "Preparing")
  local prepared, preparation_error = preparation.prepare(
    required_bones,
    restock,
    target_level,
    target_xp)
  if not prepared then return preparation_error end

  gc.phase("prayer.prepared")
  local result = training.run(target_level, target_xp)
  if result.status == "complete" then
    result.start_xp = start.xp
    result.gained_xp = result.final_xp - start.xp
    result.required_bones = required_bones
    gc.phase("prayer.target_reached")
    gc.log("info", "prayer-complete", result)
  end
  return result
end

return { run = run }
