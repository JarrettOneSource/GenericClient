-- genericclient-interface: 2

local config = gc.require("config")
local runner = gc.require("runner")

return {
  inputs = {
    {
      id = "target_level",
      label = "Target level",
      type = "choice",
      default = "43",
      choices = {
        { value = "43", label = "43" },
        { value = "70", label = "70" },
        { value = "77", label = "77" },
      },
    },
    {
      id = "restock",
      label = "Restock",
      type = "choice",
      default = "ge",
      choices = {
        { value = "ge", label = "Grand Exchange" },
        { value = "bank_only", label = "Bank only" },
      },
    },
  },

  actions = {
    { id = "stop_after_bone", label = "Stop after bone" },
  },

  run = function(input)
    local target = assert(tonumber(input.target_level), "Invalid Prayer target")
    assert(config.target_xp[input.target_level], "Unsupported Prayer target")
    return runner.run(target, input.restock)
  end,
}
