import { mkdir, rename, writeFile } from "node:fs/promises";
import path from "node:path";

const output = process.env.GENERICCLIENT_BRIDGE_PROBE_OUTPUT;
if (!output) {
  throw new Error("GENERICCLIENT_BRIDGE_PROBE_OUTPUT is required");
}

const receipt = {
  schema: "genericclient_bridge_probe.v1",
  native_platform: process.platform,
  native_arch: process.arch,
  pid: process.pid,
  argument_count: process.argv.length - 2,
  inherited: {
    JX_SESSION_ID: present("JX_SESSION_ID"),
    JX_CHARACTER_ID: present("JX_CHARACTER_ID"),
    JX_DISPLAY_NAME: present("JX_DISPLAY_NAME"),
    JX_ACCESS_TOKEN: present("JX_ACCESS_TOKEN"),
    JX_REFRESH_TOKEN: present("JX_REFRESH_TOKEN"),
  },
};

await mkdir(path.dirname(output), { recursive: true });
const temporary = `${output}.tmp-${process.pid}`;
await writeFile(temporary, `${JSON.stringify(receipt, null, 2)}\n`, { mode: 0o600 });
await rename(temporary, output);

function present(name) {
  return typeof process.env[name] === "string" && process.env[name].length > 0;
}
