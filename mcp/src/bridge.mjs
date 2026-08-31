import os from "node:os";
import path from "node:path";

import { InstanceRegistry } from "../../harness/src/registry.mjs";

const DEFAULT_URL = "http://127.0.0.1:17343";
const DEFAULT_TIMEOUT_MS = 430_000;

export class GenericClientBridge {
  constructor(value = {}) {
    if (typeof value === "string") {
      this.url = value.replace(/\/$/, "");
      this.registry = null;
      this.instanceId = null;
      return;
    }
    const environment = value.environment || process.env;
    const explicitUrl = value.url || environment.GENERICCLIENT_URL;
    const instanceId = value.instanceId || environment.GENERICCLIENT_INSTANCE_ID || null;
    const directory = value.instanceDirectory || environment.GENERICCLIENT_INSTANCE_DIRECTORY ||
      (instanceId
        ? path.join(os.homedir(), ".runelite", "genericclient", "instances")
        : null);
    this.url = (explicitUrl || DEFAULT_URL).replace(/\/$/, "");
    this.registry = value.registry || (explicitUrl || !directory
      ? null
      : new InstanceRegistry(path.resolve(directory)));
    this.instanceId = instanceId;
  }

  async call(method, params = {}, timeoutMs = DEFAULT_TIMEOUT_MS) {
    const endpoint = await this.#endpoint();
    let response;
    try {
      response = await fetch(`${endpoint}/rpc`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ method, params }),
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch (error) {
      throw new Error(
        `Cannot reach GenericClient at ${endpoint}. ` +
          `Start RuneLite with the GenericClient plugin. ${error.message}`,
      );
    }

    const body = await response.json().catch(() => null);
    if (!response.ok || !body?.ok) {
      throw new Error(body?.error || `GenericClient returned HTTP ${response.status}`);
    }
    return body.result;
  }

  async #endpoint() {
    if (!this.registry) {
      return this.url;
    }
    const instance = await this.registry.resolve(this.instanceId);
    return instance.control_url.replace(/\/$/, "");
  }
}
