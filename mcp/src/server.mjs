import { McpServer } from "@modelcontextprotocol/server";
import { serveStdio } from "@modelcontextprotocol/server/stdio";
import { pathToFileURL } from "node:url";
import * as z from "zod/v4";

import { GenericClientBridge } from "./bridge.mjs";

const VERSION = "local";
const jsonValue = z.lazy(() =>
  z.union([
    z.string(),
    z.number(),
    z.boolean(),
    z.null(),
    z.array(jsonValue),
    z.record(z.string(), jsonValue),
  ]),
);

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

const sceneMarkerMetadata = {
  label: z.string().min(1).optional().describe("Optional caption shown above the target."),
  color: z
    .string()
    .regex(/^#[0-9a-fA-F]{6}$/)
    .optional()
    .describe("Optional marker color in #RRGGBB form."),
};
const worldPoint = z.object({
  x: z.number().int(),
  y: z.number().int(),
  plane: z.number().int().min(0).max(3),
});
const sceneMarker = z.union([
  z.object({ tile: worldPoint, ...sceneMarkerMetadata }),
  z.object({ npc_id: z.number().int().positive(), ...sceneMarkerMetadata }),
  z.object({ object_id: z.number().int().positive(), ...sceneMarkerMetadata }),
  z.object({ ground_item_id: z.number().int().positive(), ...sceneMarkerMetadata }),
  z.object({ player_name: z.string().min(1), ...sceneMarkerMetadata }),
  z.object({ mouse_tile: z.literal(true), ...sceneMarkerMetadata }),
]);

export function createServer(bridge = new GenericClientBridge()) {
  const server = new McpServer(
    { name: "genericclient", version: VERSION },
    {
      instructions:
        "GenericClient runs Java scripts through its DreamBot-compatible API. Call client_status first, then account_snapshot before planning account work. " +
        "Use client_screenshot when structured state does not explain the visible game. " +
        "Use java_eval for a Java method body that returns a diagnostic value; the active script must be stopped first. " +
        "Use Automation.activity(name, policy) and Automation.intent(name, supplier) for script behavior and grouped actions. " +
        "Diagnostics are plain unless humanize=true is explicit; standalone scripts use their declared policy. " +
        "Use Navigation.Journey for travel constraints and Navigation.walk for interruption and continuation receipts. " +
        "Use script_compile for annotated Java source, script_run for declared inputs, and script_action for cooperative script buttons. " +
        "Use random_event_status when attention is required and automation_status before changing scheduled rules.",
    },
  );

  server.registerTool(
    "client_status",
    {
      title: "Read GenericClient status",
      description:
        "Read the live player position, game state, script state, behavior profile, latched random-event state, registered scripts, mouse profile, recent logs, and bounded chat/system messages. Call this before exploring or interacting.",
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
    "scene_highlight",
    {
      title: "Highlight RuneLite scene targets",
      description:
        "Replace the MCP-owned scene highlights with one or more world tiles, NPC IDs, object IDs, ground-item IDs, players, or the current mouse tile. Highlights remain visible for screenshots until scene_clear is called.",
      inputSchema: z.object({
        markers: z.array(sceneMarker).min(1).describe("Targets to highlight together."),
      }),
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async ({ markers }) => result(await bridge.call("scene.highlight", { markers })),
  );

  server.registerTool(
    "scene_clear",
    {
      title: "Clear RuneLite scene highlights",
      description:
        "Clear markers created by scene_highlight without changing script markers or the Show mouse tile setting.",
      inputSchema: z.object({}),
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async () => result(await bridge.call("scene.clear")),
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
        "Manually end the active micro or long break. This is the MCP equivalent of the X on the in-client break banner; Java scripts cannot call it.",
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
        config: z
          .record(z.string(), jsonValue)
          .describe("Complete genericclient_automation.v1 object."),
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
    "java_eval",
    {
      title: "Execute Java in RuneLite",
      description:
        "Execute a Java method body against the live client. Return a value to receive it. Each invocation has its own state and runs with plain input behavior.",
      inputSchema: z.object({
        code: z.string().min(1).describe("Java method body returning a value."),
      }),
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
        openWorldHint: true,
      },
    },
    async ({ code }) => result(await bridge.call("java.eval", { code })),
  );

  server.registerTool(
    "script_list",
    {
      title: "List Java scripts",
      description: "List annotated scripts discovered in the external JAR catalog.",
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
      title: "Read Java script",
      description: "Read a script's class, metadata, inputs, and cooperative buttons.",
      inputSchema: z.object({
        id: z.string().min(1).describe("Script id, for example account-auditor."),
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
    "script_compile",
    {
      title: "Compile Java script",
      description: "Compile a Java class against the script SDK and reload the JAR catalog. Scripts use DreamBot's AbstractScript and ScriptManifest.",
      inputSchema: z.object({
        class_name: z.string().regex(/^[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*$/),
        source: z.string().min(1).describe("Complete Java source for the named class."),
      }),
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async ({ class_name, source }) => result(await bridge.call("scripts.compile", { class_name, source })),
  );

  server.registerTool(
    "script_run",
    {
      title: "Run Java script",
      description:
        "Start a registered standalone script by catalog id. Pass values for inputs declared by the script settings. It becomes the active script shown in the GenericClient dashboard.",
      inputSchema: z.object({
        id: z.string().min(1).describe("Script id."),
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
      title: "Stop active Java script",
      description: "Stop the active standalone Java script and cancel its active walk.",
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
        "Queue one action declared by the running Java script.",
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
    "script_reload",
    {
      title: "Reload script catalog",
      description:
        "Reload the script JAR catalog after files were edited outside GenericClient. The dashboard and MCP list update immediately.",
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
