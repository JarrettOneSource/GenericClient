import { createHash } from "node:crypto";

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

export class ScreenshotCache {
  constructor(
    {
      registry,
      callInstance,
      ttlMs = 10_000,
      maxEntries = 20,
      now = () => Date.now(),
    },
  ) {
    this.registry = registry;
    this.callInstance = callInstance;
    this.ttlMs = ttlMs;
    this.maxEntries = maxEntries;
    this.now = now;
    this.entries = new Map();
    this.inFlight = new Map();
  }

  async get(instanceId, { refresh = false } = {}) {
    if (!instanceId) {
      throw new Error("instance_id is required");
    }
    const cached = this.entries.get(instanceId);
    if (!refresh && cached && this.now() - cached.cached_at_epoch_millis < this.ttlMs) {
      this.#touch(instanceId, cached);
      return cached;
    }
    if (this.inFlight.has(instanceId)) {
      return this.inFlight.get(instanceId);
    }
    const request = this.#capture(instanceId);
    this.inFlight.set(instanceId, request);
    try {
      return await request;
    } finally {
      this.inFlight.delete(instanceId);
    }
  }

  invalidate(instanceId) {
    this.entries.delete(instanceId);
  }

  clearMissing(instanceIds) {
    const active = new Set(instanceIds);
    for (const instanceId of this.entries.keys()) {
      if (!active.has(instanceId)) {
        this.entries.delete(instanceId);
      }
    }
  }

  async #capture(instanceId) {
    const descriptor = await this.registry.resolve(instanceId);
    const result = await this.callInstance(descriptor, "screenshot.capture", {}, 20_000);
    validateScreenshot(result);
    const buffer = Buffer.from(result.image_base64, "base64");
    if (buffer.length < PNG_SIGNATURE.length ||
        !buffer.subarray(0, PNG_SIGNATURE.length).equals(PNG_SIGNATURE)) {
      throw new Error("GenericClient returned an invalid PNG payload");
    }
    const entry = Object.freeze({
      instance_id: instanceId,
      buffer,
      mime_type: result.mime_type,
      width: result.width,
      height: result.height,
      captured_at_epoch_millis: result.captured_at_epoch_millis,
      cached_at_epoch_millis: this.now(),
      etag: `"${createHash("sha256").update(buffer).digest("hex")}"`,
    });
    this.#touch(instanceId, entry);
    while (this.entries.size > this.maxEntries) {
      this.entries.delete(this.entries.keys().next().value);
    }
    return entry;
  }

  #touch(instanceId, entry) {
    this.entries.delete(instanceId);
    this.entries.set(instanceId, entry);
  }
}

function validateScreenshot(result) {
  if (!result || result.mime_type !== "image/png") {
    throw new Error("GenericClient returned an invalid screenshot MIME type");
  }
  if (typeof result.image_base64 !== "string" || result.image_base64.length === 0) {
    throw new Error("GenericClient returned no screenshot data");
  }
  if (!Number.isSafeInteger(result.width) || result.width <= 0 ||
      !Number.isSafeInteger(result.height) || result.height <= 0) {
    throw new Error("GenericClient returned invalid screenshot dimensions");
  }
}
