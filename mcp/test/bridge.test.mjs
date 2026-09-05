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
    response.end(JSON.stringify({ ok: false, error: "A script is already running" }));
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  context.after(() => server.close());
  const address = server.address();
  const bridge = new GenericClientBridge(`http://127.0.0.1:${address.port}`);

  await assert.rejects(() => bridge.call("java.eval", { code: "return 1;" }), /A script is already running/);
});

test("bridge resolves one selected Harness instance on every call", async (context) => {
  const calls = [];
  const first = await startServer(context, async (request, response) => {
    calls.push(JSON.parse(await readBody(request)));
    json(response, 200, { ok: true, result: "first" });
  });
  const second = await startServer(context, async (request, response) => {
    calls.push(JSON.parse(await readBody(request)));
    json(response, 200, { ok: true, result: "second" });
  });
  const endpoints = [first, second];
  const requestedIds = [];
  const bridge = new GenericClientBridge({
    instanceId: "main-client",
    registry: {
      resolve: async (instanceId) => {
        requestedIds.push(instanceId);
        return { control_url: endpoints.shift() };
      },
    },
  });

  assert.equal(await bridge.call("status"), "first");
  assert.equal(await bridge.call("status"), "second");
  assert.deepEqual(requestedIds, ["main-client", "main-client"]);
  assert.deepEqual(calls, [
    { method: "status", params: {} },
    { method: "status", params: {} },
  ]);
});

async function startServer(context, handler) {
  const server = createHttpServer((request, response) => {
    void handler(request, response);
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  context.after(() => server.close());
  return `http://127.0.0.1:${server.address().port}`;
}

async function readBody(request) {
  let value = "";
  request.setEncoding("utf8");
  for await (const chunk of request) {
    value += chunk;
  }
  return value;
}

function json(response, status, value) {
  response.writeHead(status, { "Content-Type": "application/json" });
  response.end(JSON.stringify(value));
}
