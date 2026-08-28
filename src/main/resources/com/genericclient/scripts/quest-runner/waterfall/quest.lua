local function execute(phase)
  return {
    status = "rejected",
    result = "waterfall_handler_pending:" .. tostring(phase),
  }
end

return { execute = execute }
