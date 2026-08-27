import assert from "node:assert/strict";
import test from "node:test";

import { Client, InMemoryTransport } from "@modelcontextprotocol/client";

import { createServer } from "../src/server.mjs";

test("MCP server exposes tools and forwards calls to GenericClient", async (context) => {
  const calls = [];
  const bridge = {
    async call(method, params = {}) {
      calls.push({ method, params });
      return method === "status" ? { game_state: "LOGGED_IN" } : "ok";
    },
  };
  const server = createServer(bridge);
  const client = new Client({ name: "genericclient-test", version: "1.0.0" });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await server.connect(serverTransport);
  await client.connect(clientTransport);
  context.after(async () => {
    await client.close();
    await server.close();
  });

  const tools = await client.listTools();
  const names = tools.tools.map((tool) => tool.name);
  assert.deepEqual(names, [
    "client_status",
    "behavior_profile",
    "behavior_status",
    "session_logout",
    "session_login",
    "lua_eval",
    "lua_repl_reset",
    "script_list",
    "script_get",
    "script_save",
    "script_run",
    "script_stop",
    "script_action",
    "script_reload_manifest",
  ]);

  const response = await client.callTool({ name: "client_status", arguments: {} });
  assert.match(response.content[0].text, /LOGGED_IN/);
  await client.callTool({
    name: "script_run",
    arguments: { id: "walker", inputs: { destination: "varrock_center" } },
  });
  await client.callTool({ name: "script_action", arguments: { action: "snapshot_now" } });
  assert.deepEqual(calls, [
    { method: "status", params: {} },
    {
      method: "scripts.run",
      params: { id: "walker", inputs: { destination: "varrock_center" } },
    },
    { method: "scripts.action", params: { action: "snapshot_now" } },
  ]);
});
