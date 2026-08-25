-- genericclient-interface: 1

return function()
  while true do
    gc.await { event = "game.tick" }

    local runtime = gc.read("runtime")
    local npcs = gc.read("npcs", {
      within = 15,
      limit = 25,
    })

    gc.log("info", "npc-snapshot", {
      game_tick = runtime.game_tick,
      count = #npcs,
    })

    for _, npc in ipairs(npcs) do
      gc.log("debug", "npc", {
        id = npc.id,
        index = npc.index,
        name = npc.name,
        distance = npc.distance,
        combat_level = npc.combat_level,
        animation = npc.animation,
        interacting = npc.interacting,
        actions = npc.actions,
        world = npc.world,
      })
    end

    gc.await { ticks = 4 }
  end
end
