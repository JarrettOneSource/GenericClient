-- genericclient-interface: 2

local places = {
  grand_exchange = {
    label = "Grand Exchange",
    destination = { x = 3164, y = 3487, plane = 0 },
    within = 4,
  },
  varrock_center = {
    label = "Varrock Center",
    destination = { x = 3210, y = 3424, plane = 0 },
    within = 3,
  },
  edgeville_bank = {
    label = "Edgeville Bank",
    destination = { x = 3094, y = 3492, plane = 0 },
    within = 3,
  },
  falador_center = {
    label = "Falador Center",
    destination = { x = 2965, y = 3379, plane = 0 },
    within = 4,
  },
  draynor_village = {
    label = "Draynor Village",
    destination = { x = 3105, y = 3251, plane = 0 },
    within = 4,
  },
  lumbridge_castle = {
    label = "Lumbridge Castle",
    destination = { x = 3222, y = 3218, plane = 0 },
    within = 4,
  },
}

return {
  inputs = {
    {
      id = "destination",
      label = "Destination",
      type = "choice",
      default = "grand_exchange",
      choices = {
        { value = "grand_exchange", label = "Grand Exchange" },
        { value = "varrock_center", label = "Varrock Center" },
        { value = "edgeville_bank", label = "Edgeville Bank" },
        { value = "falador_center", label = "Falador Center" },
        { value = "draynor_village", label = "Draynor Village" },
        { value = "lumbridge_castle", label = "Lumbridge Castle" },
      },
    },
  },

  run = function(input)
    local place = places[input.destination]
    assert(place, "Unknown Walker destination: " .. tostring(input.destination))

    gc.await { event = "game.tick" }
    gc.log("info", "walker-start", {
      place = place.label,
      destination = place.destination,
      from = gc.read("player").world,
    })

    local result = gc.await {
      action = {
        type = "walk.to",
        destination = place.destination,
        within = place.within,
      },
      timeout = { game_ticks = 600 },
    }

    if result.status == "arrived" then
      gc.phase("travel." .. input.destination .. ".arrived")
    end

    local idle = gc.await {
      action = { type = "mouse.offscreen" },
      breaks = false,
    }
    gc.log(result.status == "arrived" and "info" or "error", "walker-complete", {
      place = place.label,
      result = result,
      mouse = idle.status,
    })
    return result
  end,
}
