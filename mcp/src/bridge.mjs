const DEFAULT_URL = "http://127.0.0.1:17343";
const DEFAULT_TIMEOUT_MS = 430_000;

export class GenericClientBridge {
  constructor(url = process.env.GENERICCLIENT_URL || DEFAULT_URL) {
    this.url = url.replace(/\/$/, "");
  }

  async call(method, params = {}, timeoutMs = DEFAULT_TIMEOUT_MS) {
    let response;
    try {
      response = await fetch(`${this.url}/rpc`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ method, params }),
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch (error) {
      throw new Error(
        `Cannot reach GenericClient at ${this.url}. Start RuneLite with the GenericClient plugin. ${error.message}`,
      );
    }

    const body = await response.json().catch(() => null);
    if (!response.ok || !body?.ok) {
      throw new Error(body?.error || `GenericClient returned HTTP ${response.status}`);
    }
    return body.result;
  }
}
