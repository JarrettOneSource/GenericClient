-- genericclient-interface: 2

local config = gc.require("config")
local runner = gc.require("runner")

return {
  inputs = {
    {
      id = "target_level",
      label = "Target level",
      type = "choice",
      default = "25",
      choices = {
        { value = "10", label = "10" },
        { value = "20", label = "20" },
        { value = "25", label = "25" },
      },
    },
    {
      id = "method",
      label = "Method",
      type = "choice",
      default = "auto",
      choices = {
        { value = "auto", label = "Auto" },
        { value = "gnome_stronghold", label = "Gnome Stronghold" },
      },
    },
  },

  actions = {
    { id = "stop_after_obstacle", label = "Stop after obstacle" },
  },

  run = function(input)
    local target = assert(tonumber(input.target_level), "Invalid Agility target")
    assert(config.target_xp[input.target_level], "Unsupported Agility target")
    local method = input.method == "auto" and "gnome_stronghold" or input.method
    assert(method == "gnome_stronghold", "Unsupported Agility method: " .. tostring(method))
    return runner.run(target, method)
  end,
}
