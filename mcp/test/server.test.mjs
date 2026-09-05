import assert from "node:assert/strict";
import test from "node:test";

import { Client, InMemoryTransport } from "@modelcontextprotocol/client";

import { createServer } from "../src/server.mjs";

test("MCP server exposes tools and forwards calls to GenericClient", async (context) => {
  const calls = [];
  const journey = {
    status: "completed",
    value: {
      status: "interrupted",
      reason: "dialogue",
      continuation: "walk-42",
      via_passed: 1,
      via_total: 2,
      active_game_ticks: 12,
      game_ticks: 40,
      click_receipts: [{ accepted: true, behavior_before: {}, behavior_after: {} }],
      edge_memory: [],
      transports: [{ id: "ladder.down", status: "interrupted", actions: [{ status: "dispatched" }] }],
      reached: { x: 2906, y: 9876, plane: 0 },
    },
  };
  const code = `return com.genericclient.script.Navigation.walk(new com.genericclient.script.Navigation.Journey(new org.dreambot.api.methods.map.Tile(2906,9876),0),Map.of("dialogue",true),null);`;
  const intentCode = `return Automation.intent("bank.open",()->ScriptScope.current().execute("npc.interact",Map.of("name","Banker","action","Bank"),120000));`;
  const intentReceipt = {
    status: "completed",
    value: { status: "dispatched", intent: "bank.open", click_count: 1, behavior_before: {}, behavior_after: {} },
  };
  const bridge = {
    async call(method, params = {}) {
      calls.push({ method, params });
      if (method === "status") {
        return { game_state: "LOGGED_IN" };
      }
      if (method === "screenshot.capture") {
        return {
          mime_type: "image/png",
          image_base64: "iVBORw0KGgo=",
          width: 765,
          height: 503,
        };
      }
      if (method === "java.eval") return params.code === code ? journey : intentReceipt;
      return "ok";
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
    "client_screenshot",
    "scene_highlight",
    "scene_clear",
    "account_snapshot",
    "account_note_get",
    "account_note_set",
    "behavior_profile",
    "behavior_status",
    "behavior_end_break",
    "random_event_status",
    "random_event_acknowledge",
    "random_event_complete",
    "automation_status",
    "automation_config_get",
    "automation_config_set",
    "automation_enable",
    "automation_pause",
    "automation_resume",
    "automation_reload",
    "session_logout",
    "session_login",
    "java_eval",
    "script_list",
    "script_get",
    "script_compile",
    "script_run",
    "script_stop",
    "script_action",
    "script_reload",
  ]);

  const response = await client.callTool({ name: "client_status", arguments: {} });
  assert.match(response.content[0].text, /LOGGED_IN/);
  const screenshot = await client.callTool({ name: "client_screenshot", arguments: {} });
  assert.equal(screenshot.content[0].type, "image");
  assert.equal(screenshot.content[0].mimeType, "image/png");
  assert.equal(screenshot.content[0].data, "iVBORw0KGgo=");
  assert.match(screenshot.content[1].text, /765/);
  const sceneMarkers = [
    { tile: { x: 2746, y: 2799, plane: 0 }, label: "Child safe spot" },
    { npc_id: 5270, color: "#ff00ff" },
    { object_id: 4749 },
    { ground_item_id: 1963 },
    { player_name: "genericBoss" },
  ];
  await client.callTool({ name: "scene_highlight", arguments: { markers: sceneMarkers } });
  await client.callTool({ name: "scene_clear", arguments: {} });
  await client.callTool({ name: "account_snapshot", arguments: {} });
  await client.callTool({ name: "account_note_get", arguments: {} });
  await client.callTool({ name: "account_note_set", arguments: { text: "Account Goal" } });
  await client.callTool({ name: "behavior_end_break", arguments: {} });
  await client.callTool({ name: "random_event_status", arguments: {} });
  await client.callTool({ name: "random_event_acknowledge", arguments: {} });
  await client.callTool({
    name: "random_event_complete",
    arguments: { reason: "solved_from_java", resume_interrupted: false },
  });
  await client.callTool({ name: "automation_status", arguments: {} });
  await client.callTool({ name: "automation_config_get", arguments: {} });
  const automationConfig = {
    schema: "genericclient_automation.v1",
    zone: "UTC",
    enabled: false,
    schedules: {},
    rules: [],
  };
  await client.callTool({
    name: "automation_config_set",
    arguments: { config: automationConfig },
  });
  await client.callTool({ name: "automation_enable", arguments: { enabled: true } });
  await client.callTool({ name: "automation_pause", arguments: {} });
  await client.callTool({ name: "automation_resume", arguments: {} });
  await client.callTool({ name: "automation_reload", arguments: {} });
  await client.callTool({
    name: "script_compile",
    arguments: {
      class_name: "MilesSolver",
      source: "public class MilesSolver {}",
    },
  });
  await client.callTool({
    name: "script_run",
    arguments: { id: "walker", inputs: { destination: "varrock_center" } },
  });
  await client.callTool({ name: "script_action", arguments: { action: "snapshot_now" } });
  const walked = await client.callTool({ name: "java_eval", arguments: { code } });
  assert.deepEqual(JSON.parse(walked.content[0].text), journey);
  const opened = await client.callTool({ name: "java_eval", arguments: { code: intentCode } });
  assert.deepEqual(JSON.parse(opened.content[0].text), intentReceipt);
  assert.deepEqual(calls, [
    { method: "status", params: {} },
    { method: "screenshot.capture", params: {} },
    { method: "scene.highlight", params: { markers: sceneMarkers } },
    { method: "scene.clear", params: {} },
    { method: "account.snapshot", params: {} },
    { method: "account.note.get", params: {} },
    { method: "account.note.set", params: { text: "Account Goal" } },
    { method: "behavior.break.end", params: {} },
    { method: "random_event.status", params: {} },
    { method: "random_event.acknowledge", params: {} },
    {
      method: "random_event.complete",
      params: { reason: "solved_from_java", resume_interrupted: false },
    },
    { method: "automation.status", params: {} },
    { method: "automation.config.get", params: {} },
    { method: "automation.config.set", params: { config: automationConfig } },
    { method: "automation.enable", params: { enabled: true } },
    { method: "automation.pause", params: {} },
    { method: "automation.resume", params: {} },
    { method: "automation.reload", params: {} },
    {
      method: "scripts.compile",
      params: {
        class_name: "MilesSolver",
        source: "public class MilesSolver {}",
      },
    },
    {
      method: "scripts.run",
      params: { id: "walker", inputs: { destination: "varrock_center" } },
    },
    { method: "scripts.action", params: { action: "snapshot_now" } },
    { method: "java.eval", params: { code } },
    { method: "java.eval", params: { code: intentCode } },
  ]);
});
