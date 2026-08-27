import { McpServer } from "@modelcontextprotocol/server";
import { serveStdio } from "@modelcontextprotocol/server/stdio";
import { pathToFileURL } from "node:url";
import * as z from "zod/v4";

import { GenericClientBridge } from "./bridge.mjs";

const VERSION = "0.11.0";

function result(value) {
  return {
    content: [
      {
        type: "text",
        text: JSON.stringify(value, null, 2),
      },
    ],
  };
}

export function createServer(bridge = new GenericClientBridge()) {
  const server = new McpServer(
    { name: "genericclient", version: VERSION },
    {
      instructions:
        "GenericClient controls the live RuneLite client through Lua. Call client_status first, then account_snapshot before planning account work. " +
        "Use lua_eval for ad-hoc exploration; its code is the body of a persistent Lua function, so return a value to receive it. " +
        "Available Lua primitives are gc.read, gc.await, gc.log, gc.overlay, and gc.next_action. " +
        "Each composite client interaction uses the seeded behavior profile unless breaks=false; gc.phase(name) performs a heavier phase evaluation. " +
        "Use script_save for reusable standalone scripts, script_run with declared inputs, and script_action for declared buttons. Only one manifest script and one REPL execution run at a time.",
    },
  );

  server.registerTool(
    "client_status",
    {
      title: "Read GenericClient status",
      description:
        "Read the live player position, game state, Lua state, behavior state/profile, registered scripts, mouse profile, and recent logs. Call this before exploring or interacting.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async () => result(await bridge.call("status")),
  );

  server.registerTool(
    "account_snapshot",
    {
      title: "Read account snapshot",
      description:
        "Read one immutable live frame containing player location, skills and exact XP, inventory, equipment, bank cache state, quests, Grand Exchange offers, and known cash. An unknown bank is not treated as empty; open it once to populate the cache.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async () => result(await bridge.call("account.snapshot")),
  );

  server.registerTool(
    "account_note_get",
    {
      title: "Read account note",
      description: "Read the RuneLite Notes text from the profile currently bound to this account.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async () => result(await bridge.call("account.note.get")),
  );

  server.registerTool(
    "account_note_set",
    {
      title: "Update account note",
      description:
        "Replace the RuneLite Notes text in the profile currently bound to this account. Preserve the account goal and add only verified milestones or current plans.",
      inputSchema: z.object({
        text: z.string().min(1).max(20_000).describe("Complete replacement note text."),
      }),
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async ({ text }) => result(await bridge.call("account.note.set", { text })),
  );

  server.registerTool(
    "behavior_profile",
    {
      title: "Read behavior profile",
      description:
        "Read the active account's deterministic human-readable behavior profile, break tendencies, durations, phase sensitivity, long-break preference, and idle edge.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async () => result(await bridge.call("behavior.profile")),
  );

  server.registerTool(
    "behavior_status",
    {
      title: "Read behavior status",
      description:
        "Read current break state, remaining time, active long-break pressure, break counts, and the active behavior profile.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async () => result(await bridge.call("behavior.status")),
  );

  server.registerTool(
    "session_logout",
    {
      title: "Log out the game session",
      description:
        "Deliberately log out through the visible RuneLite widgets using synthetic client input.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: true,
        openWorldHint: true,
      },
    },
    async () => result(await bridge.call("session.logout")),
  );

  server.registerTool(
    "session_login",
    {
      title: "Restore the Jagex game session",
      description:
        "Use the active Jagex Launcher session to press Play Now and dismiss click-to-play with synthetic client input.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: true,
      },
    },
    async () => result(await bridge.call("session.login")),
  );

  server.registerTool(
    "lua_eval",
    {
      title: "Execute Lua in RuneLite",
      description:
        "Execute a Lua snippet against the live client and wait for its returned value. Use `return gc.read(\"player\")` for a simple query. Globals persist between calls. The snippet may use gc.await and semantic actions such as walk.to.",
      inputSchema: z.object({
        code: z.string().min(1).describe("Lua function body. Use return to send a structured value back."),
      }),
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
        openWorldHint: true,
      },
    },
    async ({ code }) => result(await bridge.call("lua.eval", { code })),
  );

  server.registerTool(
    "lua_repl_reset",
    {
      title: "Reset Lua REPL",
      description: "Clear globals created by earlier lua_eval calls and create a fresh REPL state.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async () => result(await bridge.call("lua.reset")),
  );

  server.registerTool(
    "script_list",
    {
      title: "List Lua scripts",
      description: "List every standalone Lua script registered in scripts/manifest.json.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async () => result(await bridge.call("scripts.list")),
  );

  server.registerTool(
    "script_get",
    {
      title: "Read Lua script",
      description: "Read one registered script's manifest metadata and complete Lua source.",
      inputSchema: z.object({
        id: z.string().min(1).describe("Manifest script id, for example npc-diagnostics."),
      }),
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async ({ id }) => result(await bridge.call("scripts.get", { id })),
  );

  server.registerTool(
    "script_save",
    {
      title: "Save Lua script",
      description:
        "Create or replace a standalone Lua script and register it in scripts/manifest.json. Source must return a descriptor table with a run function and optional inputs/actions. Use a short lowercase id such as inspect-varrock-npcs.",
      inputSchema: z.object({
        id: z
          .string()
          .regex(/^[a-z0-9][a-z0-9_-]*$/)
          .describe("Stable lowercase script id."),
        name: z.string().min(1).describe("Human-readable name shown in Automations."),
        description: z.string().min(1).describe("One sentence explaining what the script does."),
        source: z
          .string()
          .min(1)
          .describe("Complete Lua file returning { inputs = {...}, run = function(input) ... end }."),
      }),
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async ({ id, name, description, source }) =>
      result(await bridge.call("scripts.save", { id, name, description, source })),
  );

  server.registerTool(
    "script_run",
    {
      title: "Run Lua script",
      description:
        "Start a registered standalone script by manifest id. Pass values for inputs declared by the script descriptor. It becomes the active script shown in the GenericClient dashboard.",
      inputSchema: z.object({
        id: z.string().min(1).describe("Manifest script id."),
        inputs: z
          .record(z.string(), z.string())
          .optional()
          .describe("Values keyed by the input ids returned by script_get."),
      }),
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
        openWorldHint: true,
      },
    },
    async ({ id, inputs = {} }) => result(await bridge.call("scripts.run", { id, inputs })),
  );

  server.registerTool(
    "script_stop",
    {
      title: "Stop active Lua script",
      description: "Stop the active standalone Lua script and cancel its active walk.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async () => result(await bridge.call("scripts.stop")),
  );

  server.registerTool(
    "script_action",
    {
      title: "Trigger active script action",
      description:
        "Queue one action declared by the running script. The Lua coroutine consumes it cooperatively with gc.next_action().",
      inputSchema: z.object({
        action: z.string().min(1).describe("Declared action id from client_status or script_get."),
      }),
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
        openWorldHint: true,
      },
    },
    async ({ action }) => result(await bridge.call("scripts.action", { action })),
  );

  server.registerTool(
    "script_reload_manifest",
    {
      title: "Reload script manifest",
      description:
        "Reload scripts/manifest.json after files were edited outside GenericClient. The dashboard and MCP list update immediately.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async () => result(await bridge.call("scripts.reload")),
  );

  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const handle = serveStdio(() => createServer());
  console.error("GenericClient MCP server listening on stdio");

  process.on("SIGINT", () => {
    void handle.close();
  });
}
