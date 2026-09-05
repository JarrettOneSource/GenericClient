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
  const account = await call("account_snapshot");
  const behaviorProfile = await call("behavior_profile");
  const behaviorStatus = await call("behavior_status");
  const diagnostic = await call("java_eval", {
    code: "return org.dreambot.api.Client.isLoggedIn();",
  });
  const compiled = await call("script_compile", {
    class_name: "McpSmoke",
    source: `import org.dreambot.api.script.*;
@ScriptManifest(name="MCP smoke",author="GenericClient",category=Category.UTILITY,version=1)
public class McpSmoke extends AbstractScript {
  public int onLoop() { log("mcp-smoke-complete"); return -1; }
}`,
  });
  const started = await call("script_run", { id: "McpSmoke" });
  await new Promise((resolve) => setTimeout(resolve, 500));
  const afterScript = await call("client_status");
  process.stdout.write(`${JSON.stringify({
    tools: tools.tools.map((tool) => tool.name),
    game_state: status.game_state,
    cash: account.cash,
    behavior_profile: behaviorProfile,
    behavior_status: behaviorStatus,
    diagnostic,
    compiled,
    started,
    script_status: afterScript.scripts.script_status,
  }, null, 2)}\n`);
} finally {
  await client.close();
}
