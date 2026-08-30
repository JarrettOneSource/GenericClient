-- genericclient-interface: 2

return {
  run = function(input)
    gc.activity("travel")
    gc.overlay {
      { label = "State", value = "Starting" },
      { label = "Attempts", value = "0 / 3" },
    }
    gc.await { event = "game.tick" }
    gc.phase("diagnostics.walk-stress", { breaks = false })

    for attempt = 1, 3 do
      gc.overlay {
        { label = "State", value = "Walking" },
        { label = "Attempts", value = (attempt - 1) .. " / 3" },
      }
      local receipt = gc.await {
        action = {
          type = "walk.random",
        },
        timeout = {
          game_ticks = 8,
        },
        breaks = false,
      }

      gc.log("info", "walk-attempt", {
        attempt = attempt,
        status = receipt.status,
        result = receipt.result,
      })

      gc.await { ticks = 3 }
    end
    gc.overlay {
      { label = "State", value = "Complete" },
      { label = "Attempts", value = "3 / 3" },
    }

    gc.log("info", "walk-stress-complete", {
      attempts = 3,
    })
  end,
}
