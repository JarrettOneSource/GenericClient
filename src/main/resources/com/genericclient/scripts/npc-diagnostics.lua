-- genericclient-interface: 2

return {
  actions = {
    { id = "snapshot_now", label = "Snapshot now" },
  },

  run = function(input)
    local ticks_until_snapshot = 0
    while true do
      gc.await { event = "game.tick" }
      local action = gc.next_action()
      if action == "snapshot_now" then
        gc.log("info", "script-action", { id = action })
        ticks_until_snapshot = 0
      end

      if ticks_until_snapshot <= 0 then
        local runtime = gc.read("runtime")
        local npcs = gc.read("npcs", {
          within = 15,
          limit = 25,
        })

        gc.overlay {
          { label = "Nearby NPCs", value = #npcs },
          { label = "Last tick", value = runtime.game_tick },
        }
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
        ticks_until_snapshot = 4
      else
        ticks_until_snapshot = ticks_until_snapshot - 1
      end
    end
  end,
}
