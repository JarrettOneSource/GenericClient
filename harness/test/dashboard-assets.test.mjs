import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { DASHBOARD_COMMANDS } from "../src/fleet-controller.mjs";

const harnessDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const [html, css, javascript] = await Promise.all([
  readFile(path.join(harnessDirectory, "web/index.html"), "utf8"),
  readFile(path.join(harnessDirectory, "web/styles.css"), "utf8"),
  readFile(path.join(harnessDirectory, "web/app.js"), "utf8"),
]);

test("dashboard markup has unique IDs and satisfies every JavaScript ID selector", () => {
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
  assert.equal(new Set(ids).size, ids.length, "HTML IDs must be unique");
  const selectedIds = [...javascript.matchAll(/querySelector\("#([A-Za-z0-9_-]+)"\)/g)]
    .map((match) => match[1]);
  for (const id of selectedIds) {
    assert.ok(ids.includes(id), `JavaScript selector #${id} must exist in index.html`);
  }
  assert.match(html, /<template id="instance-card-template">/);
  assert.match(html, /<dialog[^>]+id="details-dialog"/);
  assert.match(html, /<dialog[^>]+id="start-dialog"/);
  assert.match(html, /aria-live="polite"/);
});

test("dashboard keeps code and style external and renders untrusted state as text", () => {
  assert.match(html, /<link rel="stylesheet" href="\/styles\.css">/);
  assert.match(html, /<script src="\/app\.js" type="module"><\/script>/);
  assert.doesNotMatch(html, /<script(?![^>]+src=)/);
  assert.doesNotMatch(html, /\sstyle="/);
  assert.doesNotMatch(javascript, /\.innerHTML\s*=/);
  assert.doesNotMatch(javascript, /insertAdjacentHTML|document\.write/);
  assert.match(javascript, /\.textContent\s*=/);
});

test("browser adapter uses only dashboard routes and the controller command allowlist", () => {
  assert.match(javascript, /new EventSource\("\/api\/events"\)/);
  assert.match(javascript, /api\("\/api\/fleet"\)/);
  assert.match(javascript, /api\("\/api\/instances"/);
  assert.match(javascript, /api\("\/api\/launcher\/requests"/);
  assert.match(javascript, /\/commands/);
  assert.match(javascript, /\/screenshot/);
  assert.doesNotMatch(javascript, /control_url|\/rpc|scripts\.eval/);
  assert.match(html, /Normal Jagex Launcher RuneLite is the default path/);
  assert.match(html, /id="jagex-launch-form"/);

  const commands = new Set([
    ...html.matchAll(/data-command="([^"]+)"/g),
    ...javascript.matchAll(/"((?:session|behavior|scripts|random_event|automation)\.[a-z.]+)"/g),
  ].map((match) => match[1]));
  for (const command of commands) {
    assert.ok(DASHBOARD_COMMANDS.includes(command), `${command} must be dashboard-allowlisted`);
  }
});

test("visual system has narrow and reduced-motion layouts", () => {
  assert.match(css, /@media \(max-width: 680px\)/);
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
  assert.match(css, /\.instance-grid/);
  assert.match(css, /\.details-layout/);
  assert.match(css, /button:focus-visible/);
});
