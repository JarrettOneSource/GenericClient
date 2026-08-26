local varrock = { x = 3210, y = 3424, plane = 0 }

return function()
  gc.await { event = "game.tick" }
  gc.phase("travel.varrock.start")

  local player = gc.read("player")
  gc.log("info", "lumbridge-varrock-start", {
    from = player and player.world or nil,
    destination = varrock,
  })

  local result = gc.await {
    action = {
      type = "walk.to",
      destination = varrock,
      within = 3,
    },
    timeout = { game_ticks = 600 },
  }

  if result.status == "arrived" then
    gc.phase("travel.varrock.arrived")
  end

  gc.log(result.status == "arrived" and "info" or "error",
    "lumbridge-varrock-complete", result)
end
