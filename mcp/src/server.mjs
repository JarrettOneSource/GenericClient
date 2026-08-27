import { McpServer } from "@modelcontextprotocol/server";
import { serveStdio } from "@modelcontextprotocol/server/stdio";
import { pathToFileURL } from "node:url";
import * as z from "zod/v4";

import { GenericClientBridge } from "./bridge.mjs";

const VERSION = "0.8.0";

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
        "GenericClient controls the live RuneLite client through Lua. Call client_status first. " +
        "Use lua_eval for ad-hoc exploration; its code is the body of a persistent Lua function, so return a value to receive it. " +
        "Available Lua primitives are gc.read(subject, query), gc.await(request), and gc.log(level, event, fields). " +
        "Each composite client interaction uses the seeded behavior profile unless breaks=false; gc.phase(name) performs a heavier phase evaluation. " +
        "Use script_save for reusable standalone scripts, then script_run by id. Only one manifest script and one REPL execution run at a time.",
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
        "Create or replace a standalone Lua script and register it in scripts/manifest.json. Source must return one root function. Use a short lowercase id such as inspect-varrock-npcs.",
      inputSchema: z.object({
        id: z
          .string()
          .regex(/^[a-z0-9][a-z0-9_-]*$/)
          .describe("Stable lowercase script id."),
        name: z.string().min(1).describe("Human-readable name shown in the RuneLite Scripts tab."),
        description: z.string().min(1).describe("One sentence explaining what the script does."),
        source: z.string().min(1).describe("Complete Lua file returning one root function."),
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
        "Start a registered standalone script by manifest id. It becomes the active script shown in the GenericClient dashboard.",
      inputSchema: z.object({
        id: z.string().min(1).describe("Manifest script id."),
      }),
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
        openWorldHint: true,
      },
    },
    async ({ id }) => result(await bridge.call("scripts.run", { id })),
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
