-- genericclient-interface: 1

return function()
  gc.await { event = "game.tick" }

  for attempt = 1, 3 do
    local receipt = gc.await {
      action = {
        type = "walk.random",
      },
      timeout = {
        game_ticks = 8,
      },
    }

    gc.log("info", "walk-attempt", {
      attempt = attempt,
      status = receipt.status,
      result = receipt.result,
    })

    gc.await { ticks = 3 }
  end

  gc.log("info", "walk-stress-complete", {
    attempts = 3,
  })
end
