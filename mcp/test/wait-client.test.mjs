import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("wait-client exits distinctly before treating attention as normal script completion", async () => {
  const source = await readFile(new URL("../scripts/wait-client.ps1", import.meta.url), "utf8");

  assert.match(source, /random_event/);
  assert.match(source, /attention_required/);
  assert.match(source, /exit 3/);
  assert.match(source, /Ceiling\(\$breakRemainingMillis \/ 60000\.0\)/);
  assert.doesNotMatch(source, /break_ms\s*=\s*\$status\.behavior\.break_remaining_millis/);
  assert.ok(
    source.indexOf("exit 3") < source.indexOf("$requestedRunStillVisible"),
    "attention-required exit must win over ordinary terminal-run handling",
  );
});
