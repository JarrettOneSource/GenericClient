import http from "node:http";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const DEFAULT_WEB_DIRECTORY = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "web",
);
const INSTANCE_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const STATIC_ASSETS = new Map([
  ["/", ["index.html", "text/html; charset=utf-8"]],
  ["/index.html", ["index.html", "text/html; charset=utf-8"]],
  ["/styles.css", ["styles.css", "text/css; charset=utf-8"]],
  ["/app.js", ["app.js", "text/javascript; charset=utf-8"]],
]);

export class DashboardServer {
  constructor(
    {
      controller,
      monitor,
      screenshotCache,
      webDirectory = DEFAULT_WEB_DIRECTORY,
      host = "127.0.0.1",
      port = 0,
      bodyLimitBytes = 64 * 1_024,
      keepaliveMs = 15_000,
      createServer = http.createServer,
    },
  ) {
    if (!controller || !monitor || !screenshotCache) {
      throw new Error("DashboardServer requires controller, monitor, and screenshotCache");
    }
    if (!Number.isSafeInteger(port) || port < 0 || port > 65_535) {
      throw new Error("dashboard port must be an integer from 0 through 65535");
    }
    if (!Number.isSafeInteger(bodyLimitBytes) || bodyLimitBytes <= 0) {
      throw new Error("bodyLimitBytes must be a positive integer");
    }
    if (!Number.isSafeInteger(keepaliveMs) || keepaliveMs <= 0) {
      throw new Error("keepaliveMs must be a positive integer");
    }
    this.controller = controller;
    this.monitor = monitor;
    this.screenshotCache = screenshotCache;
    this.webDirectory = path.resolve(webDirectory);
    this.host = host;
    this.port = port;
    this.bodyLimitBytes = bodyLimitBytes;
    this.keepaliveMs = keepaliveMs;
    this.createServer = createServer;
    this.server = null;
    this.connections = new Map();
    this.unsubscribeCache = null;
  }

  async start() {
    if (this.server) {
      return this.address();
    }
    await this.monitor.start();
    this.unsubscribeCache = this.monitor.subscribe((snapshot) => {
      this.screenshotCache.clearMissing(
        (snapshot.instances || []).map((instance) => instance.instance_id),
      );
    });
    const server = this.createServer((request, response) => {
      void this.#handle(request, response);
    });
    this.server = server;
    server.on("clientError", (_error, socket) => {
      socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
    });
    try {
      await new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(this.port, this.host, () => {
          server.off("error", reject);
          resolve();
        });
      });
    } catch (error) {
      this.server = null;
      this.unsubscribeCache?.();
      this.unsubscribeCache = null;
      this.monitor.close();
      throw error;
    }
    return this.address();
  }

  address() {
    const address = this.server?.address();
    if (!address || typeof address === "string") {
      return null;
    }
    const host = address.family === "IPv6" ? `[${address.address}]` : address.address;
    return {
      host: address.address,
      port: address.port,
      url: `http://${host}:${address.port}`,
    };
  }

  async close() {
    this.unsubscribeCache?.();
    this.unsubscribeCache = null;
    for (const response of [...this.connections.keys()]) {
      this.#closeEventStream(response);
    }
    this.monitor.close();
    const server = this.server;
    this.server = null;
    if (!server) {
      return;
    }
    await new Promise((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve());
    });
  }

  async #handle(request, response) {
    applySecurityHeaders(response);
    try {
      const requestUrl = new URL(request.url || "/", `http://${request.headers.host || "localhost"}`);
      const { pathname } = requestUrl;

      if (pathname === "/favicon.ico") {
        requireMethod(request, ["GET"]);
        response.writeHead(204, { "Cache-Control": "public, max-age=86400" });
        response.end();
        return;
      }
      if (STATIC_ASSETS.has(pathname)) {
        requireMethod(request, ["GET"]);
        await this.#static(response, pathname);
        return;
      }
      if (pathname === "/health") {
        requireMethod(request, ["GET"]);
        sendJson(response, 200, {
          ok: true,
          schema: "genericclient_dashboard.v1",
          monitor: this.monitor.status(),
          launcher: this.controller.launcherStatus(),
        });
        return;
      }
      if (pathname === "/api/fleet") {
        requireMethod(request, ["GET"]);
        const fleet = this.monitor.latest || await this.monitor.refresh();
        sendJson(response, 200, fleet);
        return;
      }
      if (pathname === "/api/events") {
        requireMethod(request, ["GET"]);
        this.#eventStream(response);
        return;
      }
      if (pathname === "/api/instances") {
        requireMethod(request, ["POST"]);
        const spec = await readJsonObject(request, this.bodyLimitBytes);
        const result = await this.controller.start(spec);
        await this.#refreshAfterMutation();
        sendJson(response, 202, { ok: true, result });
        return;
      }
      if (pathname === "/api/launcher/requests") {
        requireMethod(request, ["POST"]);
        const spec = await readJsonObject(request, this.bodyLimitBytes);
        const result = await this.controller.armLauncher(spec);
        await this.#refreshAfterMutation();
        sendJson(response, 202, { ok: true, result });
        return;
      }
      const launcherCancel = parseLauncherCancelRoute(pathname);
      if (launcherCancel) {
        requireMethod(request, ["POST"]);
        await readJsonObject(request, this.bodyLimitBytes, { allowEmpty: true });
        const result = this.controller.cancelLauncher(launcherCancel.instanceId);
        await this.#refreshAfterMutation();
        sendJson(response, 200, { ok: true, result });
        return;
      }

      const route = parseInstanceRoute(pathname);
      if (!route) {
        throw new HttpError(404, "Route not found");
      }
      if (route.action === null) {
        requireMethod(request, ["GET"]);
        sendJson(response, 200, await this.controller.get(route.instanceId));
        return;
      }
      if (route.action === "screenshot") {
        requireMethod(request, ["GET"]);
        const entry = await this.screenshotCache.get(route.instanceId, {
          refresh: requestUrl.searchParams.get("refresh") === "1",
        });
        sendScreenshot(request, response, entry);
        return;
      }
      if (route.action === "stop") {
        requireMethod(request, ["POST"]);
        await readJsonObject(request, this.bodyLimitBytes, { allowEmpty: true });
        const result = await this.controller.stop(route.instanceId);
        this.screenshotCache.invalidate(route.instanceId);
        await this.#refreshAfterMutation();
        sendJson(response, 200, { ok: true, result });
        return;
      }
      if (route.action === "commands") {
        requireMethod(request, ["POST"]);
        const command = await readJsonObject(request, this.bodyLimitBytes);
        const result = await this.controller.command(route.instanceId, command);
        await this.#refreshAfterMutation();
        sendJson(response, 200, { ok: true, result });
        return;
      }
      throw new HttpError(404, "Route not found");
    } catch (error) {
      if (response.headersSent) {
        response.destroy();
        return;
      }
      const normalized = normalizeHttpError(error);
      sendJson(response, normalized.status, { ok: false, error: normalized.message }, normalized.headers);
    }
  }

  async #static(response, pathname) {
    const [filename, contentType] = STATIC_ASSETS.get(pathname);
    let body;
    try {
      body = await readFile(path.join(this.webDirectory, filename));
    } catch (error) {
      if (error?.code === "ENOENT") {
        throw new HttpError(404, "Dashboard asset not found");
      }
      throw error;
    }
    response.writeHead(200, {
      "Content-Type": contentType,
      "Content-Length": body.length,
      "Cache-Control": filename === "index.html" ? "no-cache" : "public, max-age=300",
    });
    response.end(body);
  }

  #eventStream(response) {
    response.writeHead(200, {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no",
    });
    response.write(": connected\n\n");
    const unsubscribe = this.monitor.subscribe((snapshot) => {
      if (!response.destroyed) {
        response.write(encodeFleetEvent(snapshot));
      }
    });
    const keepalive = setInterval(() => {
      if (!response.destroyed) {
        response.write(`event: heartbeat\ndata: {\"epoch_millis\":${Date.now()}}\n\n`);
      }
    }, this.keepaliveMs);
    keepalive.unref?.();
    this.connections.set(response, { unsubscribe, keepalive });
    response.once("close", () => this.#closeEventStream(response));
  }

  #closeEventStream(response) {
    const connection = this.connections.get(response);
    if (!connection) {
      return;
    }
    this.connections.delete(response);
    clearInterval(connection.keepalive);
    connection.unsubscribe();
    if (!response.destroyed) {
      response.end();
    }
  }

  async #refreshAfterMutation() {
    try {
      await this.monitor.refresh();
    } catch {
      // The mutation receipt remains authoritative; monitor status exposes refresh failures.
    }
  }
}

class HttpError extends Error {
  constructor(status, message, headers = {}) {
    super(message);
    this.status = status;
    this.headers = headers;
  }
}

function applySecurityHeaders(response) {
  response.setHeader(
    "Content-Security-Policy",
    "default-src 'self'; connect-src 'self'; img-src 'self' data:; " +
      "script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'; " +
      "frame-ancestors 'none'; form-action 'self'",
  );
  response.setHeader("X-Content-Type-Options", "nosniff");
  response.setHeader("X-Frame-Options", "DENY");
  response.setHeader("Referrer-Policy", "no-referrer");
  response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
}

function requireMethod(request, allowed) {
  if (!allowed.includes(request.method)) {
    throw new HttpError(405, "Method not allowed", { Allow: allowed.join(", ") });
  }
}

async function readJsonObject(request, limitBytes, { allowEmpty = false } = {}) {
  const contentType = String(request.headers["content-type"] || "").split(";", 1)[0].trim();
  if (contentType !== "application/json") {
    throw new HttpError(415, "Content-Type must be application/json");
  }
  const declaredLength = Number(request.headers["content-length"] || 0);
  if (Number.isFinite(declaredLength) && declaredLength > limitBytes) {
    request.resume();
    throw new HttpError(413, `JSON body exceeds ${limitBytes} bytes`);
  }
  const chunks = [];
  let size = 0;
  let tooLarge = false;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > limitBytes) {
      tooLarge = true;
      chunks.length = 0;
    } else if (!tooLarge) {
      chunks.push(chunk);
    }
  }
  if (tooLarge) {
    throw new HttpError(413, `JSON body exceeds ${limitBytes} bytes`);
  }
  if (size === 0 && allowEmpty) {
    return {};
  }
  let value;
  try {
    value = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new HttpError(400, "Request body must be valid JSON");
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpError(400, "Request body must be a JSON object");
  }
  return value;
}

function parseInstanceRoute(pathname) {
  const match = pathname.match(/^\/api\/instances\/([^/]+)(?:\/(screenshot|stop|commands))?$/);
  if (!match) {
    return null;
  }
  let instanceId;
  try {
    instanceId = decodeURIComponent(match[1]);
  } catch {
    throw new HttpError(400, "Instance ID encoding is invalid");
  }
  if (!INSTANCE_ID_PATTERN.test(instanceId)) {
    throw new HttpError(400, "Instance ID is invalid");
  }
  return { instanceId, action: match[2] || null };
}

function parseLauncherCancelRoute(pathname) {
  const match = pathname.match(/^\/api\/launcher\/requests\/([^/]+)\/cancel$/);
  if (!match) {
    return null;
  }
  let instanceId;
  try {
    instanceId = decodeURIComponent(match[1]);
  } catch {
    throw new HttpError(400, "Instance ID encoding is invalid");
  }
  if (!INSTANCE_ID_PATTERN.test(instanceId)) {
    throw new HttpError(400, "Instance ID is invalid");
  }
  return { instanceId };
}

function encodeFleetEvent(snapshot) {
  return `id: ${snapshot.sequence}\nevent: fleet\ndata: ${JSON.stringify(snapshot)}\n\n`;
}

function sendScreenshot(request, response, entry) {
  if (request.headers["if-none-match"] === entry.etag) {
    response.writeHead(304, { ETag: entry.etag, "Cache-Control": "no-cache" });
    response.end();
    return;
  }
  response.writeHead(200, {
    "Content-Type": entry.mime_type,
    "Content-Length": entry.buffer.length,
    "Cache-Control": "no-cache",
    ETag: entry.etag,
    "X-Captured-At": String(entry.captured_at_epoch_millis),
    "X-Image-Width": String(entry.width),
    "X-Image-Height": String(entry.height),
  });
  response.end(entry.buffer);
}

function sendJson(response, status, value, headers = {}) {
  const body = Buffer.from(JSON.stringify(value));
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": body.length,
    "Cache-Control": "no-store",
    ...headers,
  });
  response.end(body);
}

function normalizeHttpError(error) {
  if (error instanceof HttpError) {
    return error;
  }
  const message = error?.message || String(error);
  if (/No healthy GenericClient instance/.test(message)) {
    return new HttpError(404, message);
  }
  if (/already running/.test(message)) {
    return new HttpError(409, message);
  }
  if (/required|invalid|must be|not allowed|unsupported/.test(message)) {
    return new HttpError(400, message);
  }
  return new HttpError(500, "Dashboard request failed");
}
