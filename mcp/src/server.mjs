import { McpServer } from "@modelcontextprotocol/server";
import { serveStdio } from "@modelcontextprotocol/server/stdio";
import { pathToFileURL } from "node:url";
import * as z from "zod/v4";

import { GenericClientBridge } from "./bridge.mjs";

const VERSION = "0.12.0";

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

function screenshotResult(value) {
  if (value?.mime_type !== "image/png" || typeof value?.image_base64 !== "string") {
    throw new Error("GenericClient returned an invalid screenshot payload");
  }
  const { image_base64: data, mime_type: mimeType, ...metadata } = value;
  return {
    content: [
      { type: "image", data, mimeType },
      { type: "text", text: JSON.stringify(metadata, null, 2) },
    ],
  };
}

export function createServer(bridge = new GenericClientBridge()) {
  const server = new McpServer(
    { name: "genericclient", version: VERSION },
    {
      instructions:
        "GenericClient controls the live RuneLite client through Lua. Call client_status first, then account_snapshot before planning account work. " +
        "Use client_screenshot whenever structured state does not fully explain the visible game, widget, dialogue, camera, or menu state. " +
        "Use lua_eval for ad-hoc exploration; its code is the body of a persistent Lua function, so return a value to receive it. " +
        "Available Lua primitives are gc.read, gc.await, gc.log, gc.overlay, and gc.next_action. " +
        "Each composite client interaction uses the seeded behavior profile unless breaks=false; gc.phase(name) performs a heavier phase evaluation. " +
        "Use script_save for reusable standalone scripts, script_run with declared inputs, and script_action for declared buttons. " +
        "Use random_event_status whenever client_status reports attention_required; acknowledgement never releases the block, while completion does. " +
        "Use automation_status before changing scheduled rules; scheduled and manual scripts share the single manifest-script slot.",
    },
  );

  server.registerTool(
    "client_status",
    {
      title: "Read GenericClient status",
      description:
        "Read the live player position, game state, Lua state, behavior profile, latched random-event state, registered scripts, mouse profile, recent logs, and bounded chat/system messages. Call this before exploring or interacting.",
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
    "client_screenshot",
    {
      title: "Capture RuneLite screenshot",
      description:
        "Capture the next fully rendered RuneLite game canvas as a PNG image. Use this when snapshots or receipts do not fully explain visible UI, camera, menu, dialogue, or world state.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: false,
        openWorldHint: false,
      },
    },
    async () => screenshotResult(await bridge.call("screenshot.capture")),
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
    "behavior_end_break",
    {
      title: "End active break",
      description:
        "Manually end the active micro or long break. This is the MCP equivalent of the X on the in-client break banner; Lua scripts cannot call it.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async () => result(await bridge.call("behavior.break.end")),
  );

  server.registerTool(
    "random_event_status",
    {
      title: "Read random-event state",
      description:
        "Read GenericClient's latched random event, NPC identity and location, registered solver, presence, and attention state. The record remains available if the NPC despawns.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async () => result(await bridge.call("random_event.status")),
  );

  server.registerTool(
    "random_event_acknowledge",
    {
      title: "Acknowledge random event",
      description:
        "Mark the pending random-event alert as seen. This does not dismiss the event, release the input block, or resume the interrupted script.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async () => result(await bridge.call("random_event.acknowledge")),
  );

  server.registerTool(
    "random_event_complete",
    {
      title: "Complete random event",
      description:
        "Release a latched random event only after its solution has been observed. Optionally restart the interrupted standalone script from current game state.",
      inputSchema: z.object({
        reason: z
          .string()
          .min(1)
          .default("completed_via_mcp")
          .describe("Short observed completion receipt."),
        resume_interrupted: z
          .boolean()
          .default(true)
          .describe("Restart the interrupted manual script from current observed state."),
      }),
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
        openWorldHint: true,
      },
    },
    async ({ reason, resume_interrupted }) =>
      result(
        await bridge.call("random_event.complete", {
          reason,
          resume_interrupted,
        }),
      ),
  );

  server.registerTool(
    "automation_status",
    {
      title: "Read automation scheduler",
      description:
        "Read the active account's schedule windows, next transition, rule truth values and reasons, selected rule, active lease, cooldowns, and pause state.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async () => result(await bridge.call("automation.status")),
  );

  server.registerTool(
    "automation_config_get",
    {
      title: "Read automation rules",
      description: "Read the complete validated automation configuration for the active account profile.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async () => result(await bridge.call("automation.config.get")),
  );

  server.registerTool(
    "automation_config_set",
    {
      title: "Replace automation rules",
      description:
        "Validate and atomically replace the active account's complete automation configuration. Rules may combine named schedules with supported skill and complete-cash facts.",
      inputSchema: z.object({
        config: z.record(z.string(), z.unknown()).describe("Complete genericclient_automation.v1 object."),
      }),
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async ({ config }) => result(await bridge.call("automation.config.set", { config })),
  );

  server.registerTool(
    "automation_enable",
    {
      title: "Enable or disable scheduling",
      description:
        "Persistently enable or disable scheduled rule execution. Disabling stops a rule-owned script but never a manually owned script.",
      inputSchema: z.object({ enabled: z.boolean() }),
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: true,
      },
    },
    async ({ enabled }) => result(await bridge.call("automation.enable", { enabled })),
  );

  server.registerTool(
    "automation_pause",
    {
      title: "Pause scheduled scripts",
      description: "Pause scheduling for the active account and stop only a rule-owned script.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: true,
      },
    },
    async () => result(await bridge.call("automation.pause")),
  );

  server.registerTool(
    "automation_resume",
    {
      title: "Resume scheduled scripts",
      description: "Resume rule evaluation after a persisted pause or manual stop.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: true,
      },
    },
    async () => result(await bridge.call("automation.resume")),
  );

  server.registerTool(
    "automation_reload",
    {
      title: "Reload automation rules",
      description: "Reload and revalidate the active account's rule and runtime-state files from disk.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async () => result(await bridge.call("automation.reload")),
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
        id: z.string().min(1).describe("Manifest script id, for example account-auditor."),
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
        random_events: z
          .array(z.number().int().positive())
          .default([])
          .describe("Optional RuneLite random-event NPC IDs this standalone solver handles."),
      }),
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async ({ id, name, description, source, random_events }) =>
      result(
        await bridge.call("scripts.save", {
          id,
          name,
          description,
          source,
          random_events,
        }),
      ),
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
