import assert from "node:assert/strict";
import { createServer as createHttpServer } from "node:http";
import test from "node:test";

import { GenericClientBridge } from "../src/bridge.mjs";

test("bridge sends an RPC request and returns its result", async (context) => {
  const requests = [];
  const server = createHttpServer((request, response) => {
    let body = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => {
      body += chunk;
    });
    request.on("end", () => {
      requests.push(JSON.parse(body));
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ ok: true, result: { game_state: "LOGGED_IN" } }));
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  context.after(() => server.close());
  const address = server.address();
  const bridge = new GenericClientBridge(`http://127.0.0.1:${address.port}`);

  const result = await bridge.call("status");

  assert.deepEqual(result, { game_state: "LOGGED_IN" });
  assert.deepEqual(requests, [{ method: "status", params: {} }]);
});

test("bridge surfaces GenericClient errors", async (context) => {
  const server = createHttpServer((_request, response) => {
    response.writeHead(409, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ ok: false, error: "The Lua REPL is busy" }));
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  context.after(() => server.close());
  const address = server.address();
  const bridge = new GenericClientBridge(`http://127.0.0.1:${address.port}`);

  await assert.rejects(() => bridge.call("lua.eval", { code: "return 1" }), /Lua REPL is busy/);
});
