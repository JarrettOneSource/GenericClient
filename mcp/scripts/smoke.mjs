import { fileURLToPath } from "node:url";

import { Client } from "@modelcontextprotocol/client";
import { StdioClientTransport } from "@modelcontextprotocol/client/stdio";

const serverFile = fileURLToPath(new URL("../src/server.mjs", import.meta.url));
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [serverFile],
  stderr: "inherit",
});
const client = new Client({ name: "genericclient-smoke", version: "1.0.0" });

async function call(name, args = {}) {
  const response = await client.callTool({ name, arguments: args });
  return JSON.parse(response.content[0].text);
}

try {
  await client.connect(transport);
  const tools = await client.listTools();
  const status = await call("client_status");
  const behaviorProfile = await call("behavior_profile");
  const behaviorStatus = await call("behavior_status");
  const lua = await call("lua_eval", {
    code: 'return { player = gc.read("player"), runtime = gc.read("runtime"), npcs = gc.read("npcs", { within = 8, limit = 5 }) }',
  });
  const replFirst = await call("lua_eval", {
    code: "smoke_counter = (smoke_counter or 0) + 1\nreturn smoke_counter",
  });
  const replSecond = await call("lua_eval", {
    code: "smoke_counter = smoke_counter + 1\nreturn smoke_counter",
  });
  const interaction = await call("lua_eval", {
    code:
      'return gc.await { action = { type = "walk.random" }, timeout = { game_ticks = 12 }, breaks = false }',
  });
  const phase = await call("lua_eval", {
    code: 'return gc.phase("diagnostics.mcp-smoke", { breaks = false })',
  });
  const saved = await call("script_save", {
    id: "mcp-location-check",
    name: "MCP location check",
    description: "Log the current player snapshot once.",
    source:
      'return { run = function(input)\n  gc.await { event = "game.tick" }\n  gc.log("info", "mcp-location-check", gc.read("player"))\nend }\n',
  });
  const started = await call("script_run", { id: "mcp-location-check" });
  await new Promise((resolve) => setTimeout(resolve, 1_500));
  const afterScript = await call("client_status");
  await call("script_run", { id: "npc-diagnostics" });

  status.lua.recent_logs = [];
  afterScript.lua.recent_logs = Array.isArray(afterScript.lua.recent_logs)
    ? afterScript.lua.recent_logs.filter((line) => line.includes("mcp-location-check"))
    : [];

  process.stdout.write(
    `${JSON.stringify(
      {
        tools: tools.tools.map((tool) => tool.name),
        status,
        behavior_profile: behaviorProfile,
        behavior_status: behaviorStatus,
        lua,
        repl_first: replFirst,
        repl_second: replSecond,
        interaction,
        phase,
        saved,
        started,
        after_script: afterScript,
      },
      null,
      2,
    )}\n`,
  );
} finally {
  await client.close();
}
