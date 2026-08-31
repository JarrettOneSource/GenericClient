export async function callInstance(instance, method, params = {}, timeoutMs = 30_000) {
  const response = await fetch(`${instance.control_url.replace(/\/$/, "")}/rpc`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ method, params }),
    signal: AbortSignal.timeout(timeoutMs),
  });
  const body = await response.json().catch(() => null);
  if (!response.ok || !body?.ok) {
    throw new Error(body?.error || `GenericClient returned HTTP ${response.status}`);
  }
  return body.result;
}
