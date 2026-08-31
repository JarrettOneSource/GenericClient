const elements = {
  connectionLabel: document.querySelector("#connection-label"),
  streamStatus: document.querySelector("#stream-status"),
  generation: document.querySelector("#fleet-generation"),
  sequence: document.querySelector("#fleet-sequence"),
  grid: document.querySelector("#instance-grid"),
  empty: document.querySelector("#empty-state"),
  cardTemplate: document.querySelector("#instance-card-template"),
  registryWarning: document.querySelector("#registry-warning"),
  rejectedList: document.querySelector("#rejected-list"),
  detailsDialog: document.querySelector("#details-dialog"),
  startDialog: document.querySelector("#start-dialog"),
  startForm: document.querySelector("#start-form"),
  detailTitle: document.querySelector("#details-title"),
  detailSubtitle: document.querySelector("#details-subtitle"),
  detailFrame: document.querySelector("#detail-frame"),
  detailFrameStatus: document.querySelector("#detail-frame-status"),
  detailFacts: document.querySelector("#detail-facts"),
  detailScriptState: document.querySelector("#detail-script-state"),
  detailControls: document.querySelector("#detail-controls"),
  scriptActionForm: document.querySelector("#script-action-form"),
  scriptList: document.querySelector("#script-list"),
  eventLog: document.querySelector("#event-log"),
  behaviorJson: document.querySelector("#behavior-json"),
  automationJson: document.querySelector("#automation-json"),
  safetyJson: document.querySelector("#safety-json"),
  detailJson: document.querySelector("#detail-json"),
  toastRegion: document.querySelector("#toast-region"),
};

const summaryElements = {
  total: document.querySelector("#summary-total"),
  healthy: document.querySelector("#summary-healthy"),
  degraded: document.querySelector("#summary-degraded"),
  starting: document.querySelector("#summary-running"),
  attention: document.querySelector("#summary-attention"),
  attentionCell: document.querySelector(".summary-attention"),
  breaking: document.querySelector("#summary-breaking"),
  scripting: document.querySelector("#summary-scripting"),
  loggedIn: document.querySelector("#summary-logged-in"),
  pss: document.querySelector("#summary-pss"),
  uss: document.querySelector("#summary-uss"),
  cpu: document.querySelector("#summary-cpu"),
  rejected: document.querySelector("#summary-rejected"),
};

const state = {
  fleet: null,
  selectedId: null,
  cards: new Map(),
  eventSource: null,
  connection: "connecting",
  lastContact: null,
};

bindEvents();
void loadInitialFleet();
connectEventStream();
setInterval(updateConnectionAge, 1_000).unref?.();

function bindEvents() {
  document.querySelector("#open-start").addEventListener("click", openStartDialog);
  document.querySelector("#empty-start").addEventListener("click", openStartDialog);
  document.querySelector("#close-start").addEventListener("click", () => elements.startDialog.close());
  document.querySelector("#cancel-start").addEventListener("click", () => elements.startDialog.close());
  document.querySelector("#close-details").addEventListener("click", () => elements.detailsDialog.close());
  document.querySelector("#refresh-frame").addEventListener("click", (event) => {
    if (state.selectedId) {
      refreshScreenshot(state.selectedId, event.currentTarget);
    }
  });
  document.querySelector("#detail-stop").addEventListener("click", (event) => {
    if (state.selectedId) {
      void stopInstance(state.selectedId, event.currentTarget);
    }
  });
  elements.grid.addEventListener("click", handleCardAction);
  elements.detailControls.addEventListener("click", handleDetailCommand);
  elements.scriptList.addEventListener("click", handleScriptRun);
  elements.scriptActionForm.addEventListener("submit", handleScriptAction);
  elements.startForm.addEventListener("submit", handleStart);
  elements.detailsDialog.addEventListener("close", () => {
    state.selectedId = null;
  });
}

async function loadInitialFleet() {
  try {
    const fleet = await api("/api/fleet");
    acceptFleet(fleet);
  } catch (error) {
    showToast(`Initial fleet load failed: ${error.message}`, true);
  }
}

function connectEventStream() {
  const source = new EventSource("/api/events");
  state.eventSource = source;
  setConnection("connecting");
  source.addEventListener("open", () => {
    touchConnection();
    setConnection("live");
  });
  source.addEventListener("fleet", (event) => {
    touchConnection();
    try {
      acceptFleet(JSON.parse(event.data));
      setConnection("live");
    } catch (error) {
      showToast(`Fleet event rejected: ${error.message}`, true);
    }
  });
  source.addEventListener("heartbeat", () => {
    touchConnection();
    setConnection("live");
  });
  source.addEventListener("error", () => {
    setConnection("reconnecting");
  });
}

function acceptFleet(fleet) {
  if (!fleet || fleet.schema !== "genericclient_fleet.v1" || !Array.isArray(fleet.instances)) {
    throw new Error("unsupported fleet snapshot");
  }
  if (state.fleet && Number.isFinite(fleet.sequence) && fleet.sequence < state.fleet.sequence) {
    return;
  }
  state.fleet = fleet;
  renderSummary(fleet);
  renderRejected(fleet.rejected || []);
  renderCards(fleet.instances);
  elements.generation.textContent = `Snapshot ${formatTimestamp(fleet.generated_at_epoch_millis)}`;
  elements.sequence.textContent = `SEQ ${String(fleet.sequence).padStart(4, "0")}`;
  if (state.selectedId) {
    const selected = findInstance(state.selectedId);
    if (selected) {
      renderDetails(selected);
    } else {
      const departedId = state.selectedId;
      elements.detailsDialog.close();
      showToast(`Instance ${departedId} left the fleet`);
    }
  }
}

function renderSummary(fleet) {
  const summary = fleet.summary || {};
  const memory = summary.memory || {};
  setText(summaryElements.total, summary.total_instances ?? fleet.instances.length);
  setText(summaryElements.healthy, summary.healthy ?? 0);
  setText(summaryElements.degraded, `${summary.degraded ?? 0} degraded`);
  setText(summaryElements.starting, `${summary.starting ?? 0} starting`);
  setText(summaryElements.attention, summary.attention_required ?? 0);
  summaryElements.attentionCell.classList.toggle("has-attention", (summary.attention_required || 0) > 0);
  setText(summaryElements.breaking, `${summary.breaking ?? 0} on break`);
  setText(summaryElements.scripting, summary.scripting ?? 0);
  setText(summaryElements.loggedIn, `${summary.logged_in ?? 0} logged in`);
  setText(summaryElements.pss, formatBytes(memory.pss_bytes));
  setText(summaryElements.uss, `${formatBytes(memory.uss_bytes)} private`);
  setText(summaryElements.cpu, formatPercent(summary.cpu_percent));
  setText(summaryElements.rejected, `${summary.rejected ?? 0} rejected`);
}

function renderRejected(rejected) {
  elements.registryWarning.hidden = rejected.length === 0;
  elements.rejectedList.replaceChildren();
  for (const entry of rejected) {
    const item = document.createElement("li");
    const reason = document.createElement("strong");
    reason.textContent = humanize(entry.reason || "rejected");
    item.append(reason, document.createTextNode(` · ${entry.detail || entry.descriptor_path || "No detail"}`));
    elements.rejectedList.append(item);
  }
}

function renderCards(instances) {
  const activeIds = new Set(instances.map((instance) => instance.instance_id));
  for (const [instanceId, card] of state.cards) {
    if (!activeIds.has(instanceId)) {
      card.remove();
      state.cards.delete(instanceId);
    }
  }
  instances.forEach((instance, index) => {
    let card = state.cards.get(instance.instance_id);
    if (!card) {
      card = elements.cardTemplate.content.firstElementChild.cloneNode(true);
      card.dataset.instanceId = instance.instance_id;
      const frame = card.querySelector(".js-frame");
      frame.addEventListener("load", () => card.classList.remove("frame-error"));
      frame.addEventListener("error", () => card.classList.add("frame-error"));
      frame.src = screenshotUrl(instance.instance_id);
      state.cards.set(instance.instance_id, card);
    }
    updateCard(card, instance, index);
    elements.grid.append(card);
  });
  elements.empty.hidden = instances.length !== 0;
  elements.grid.hidden = instances.length === 0;
}

function updateCard(card, instance, index) {
  card.dataset.health = instance.health || "degraded";
  setText(card.querySelector(".js-index"), String(index + 1).padStart(2, "0"));
  setText(card.querySelector(".js-name"), instance.display_name || instance.instance_id);
  setText(card.querySelector(".js-id"), instance.instance_id);
  setText(card.querySelector(".js-state"), humanize(instance.game_state || instance.lifecycle || instance.health));
  setText(card.querySelector(".js-mode"), instance.mode || "unknown");
  setText(card.querySelector(".js-pid"), `PID ${instance.pid ?? "—"}`);
  setText(card.querySelector(".js-activity"), activityLabel(instance));
  setText(card.querySelector(".js-location"), formatLocation(instance.player?.world));
  setText(card.querySelector(".js-pss"), formatBytes(instance.memory?.pss_bytes));
  setText(card.querySelector(".js-uss"), formatBytes(instance.memory?.uss_bytes));
  setText(card.querySelector(".js-cpu"), formatPercent(instance.cpu_percent));
  setText(card.querySelector(".js-uptime"), formatDuration(instance.uptime_millis));
  const frame = card.querySelector(".js-frame");
  frame.alt = `${instance.display_name || instance.instance_id} client frame`;
  const warning = card.querySelector(".js-warning");
  warning.hidden = !instance.warnings?.length;
  warning.textContent = instance.warnings?.map((entry) => entry.message).join(" · ") || "";
  const session = card.querySelector(".js-session");
  if (instance.controls?.can_logout) {
    session.textContent = "Log out";
    session.dataset.command = "session.logout";
    session.disabled = false;
  } else if (instance.controls?.can_login) {
    session.textContent = "Log in";
    session.dataset.command = "session.login";
    session.disabled = false;
  } else {
    session.textContent = "Session";
    session.dataset.command = "";
    session.disabled = true;
  }
  card.querySelector(".js-end-break").hidden = !instance.controls?.can_end_break;
}

async function handleCardAction(event) {
  const button = event.target.closest("button[data-action]");
  const card = button?.closest(".instance-card");
  if (!button || !card) {
    return;
  }
  const instanceId = card.dataset.instanceId;
  switch (button.dataset.action) {
    case "details":
      openDetails(instanceId);
      break;
    case "stop":
      await stopInstance(instanceId, button);
      break;
    case "session":
      if (button.dataset.command) {
        await runCommand(instanceId, button.dataset.command, {}, button);
      }
      break;
    case "end-break":
      await runCommand(instanceId, "behavior.break.end", {}, button);
      break;
  }
}

function openDetails(instanceId) {
  const instance = findInstance(instanceId);
  if (!instance) {
    showToast(`Instance ${instanceId} is no longer available`, true);
    return;
  }
  state.selectedId = instanceId;
  elements.detailFrame.src = screenshotUrl(instanceId);
  elements.detailFrameStatus.textContent = "Cached frame";
  renderDetails(instance);
  if (!elements.detailsDialog.open) {
    elements.detailsDialog.showModal();
  }
}

function renderDetails(instance) {
  elements.detailTitle.textContent = instance.display_name || instance.instance_id;
  elements.detailSubtitle.textContent = `${instance.instance_id} · PID ${instance.pid} · ${instance.mode}`;
  elements.detailScriptState.textContent = instance.active_script
    ? `${instance.active_script} · ${humanize(instance.script_state)}`
    : "Idle";
  renderFacts(instance);
  renderControlAvailability(instance);
  renderScripts(instance);
  renderLog(instance);
  elements.behaviorJson.textContent = pretty(instance.behavior);
  elements.automationJson.textContent = pretty(instance.automation);
  elements.safetyJson.textContent = pretty({
    safety: instance.safety,
    random_event: instance.random_event,
  });
  elements.detailJson.textContent = pretty(instance);
}

function renderFacts(instance) {
  const facts = [
    ["Game state", humanize(instance.game_state || "unknown")],
    ["Location", formatLocation(instance.player?.world)],
    ["Activity", activityLabel(instance)],
    ["PSS", formatBytes(instance.memory?.pss_bytes)],
    ["Private", formatBytes(instance.memory?.uss_bytes)],
    ["CPU", formatPercent(instance.cpu_percent)],
    ["Uptime", formatDuration(instance.uptime_millis)],
    ["Profile", instance.runelite_profile || "Default"],
    ["Lifecycle", humanize(instance.lifecycle || "unknown")],
  ];
  elements.detailFacts.replaceChildren();
  for (const [label, value] of facts) {
    const wrapper = document.createElement("div");
    const term = document.createElement("dt");
    const detail = document.createElement("dd");
    term.textContent = label;
    detail.textContent = value;
    detail.title = value;
    wrapper.append(term, detail);
    elements.detailFacts.append(wrapper);
  }
}

function renderControlAvailability(instance) {
  const automationAvailable = Boolean(instance.automation?.available);
  for (const button of elements.detailControls.querySelectorAll("button[data-command]")) {
    const command = button.dataset.command;
    button.hidden =
      command === "session.login" && !instance.controls?.can_login ||
      command === "session.logout" && !instance.controls?.can_logout ||
      command === "behavior.break.end" && !instance.controls?.can_end_break ||
      command === "scripts.stop" && !instance.controls?.can_stop_script ||
      command === "random_event.acknowledge" && !instance.controls?.can_acknowledge_random_event ||
      command === "random_event.complete" && !instance.controls?.can_acknowledge_random_event ||
      command === "automation.pause" &&
        (!automationAvailable || Boolean(instance.automation?.paused)) ||
      command === "automation.resume" &&
        (!automationAvailable || !instance.automation?.paused);
  }
  elements.scriptActionForm.hidden = !instance.scripting;
}

function renderScripts(instance) {
  elements.scriptList.replaceChildren();
  if (!instance.scripts?.length) {
    appendEmpty(elements.scriptList, "No scripts reported by this client");
    return;
  }
  for (const script of instance.scripts) {
    const item = document.createElement("li");
    const copy = document.createElement("div");
    const name = document.createElement("strong");
    const detail = document.createElement("small");
    const run = document.createElement("button");
    const scriptId = script.id || script.name;
    name.textContent = script.name || scriptId || "Unnamed script";
    detail.textContent = `${scriptId || "unknown"} · ${humanize(script.status || "available")}`;
    copy.append(name, detail);
    run.type = "button";
    run.className = "button button-quiet";
    run.dataset.action = "run-script";
    run.dataset.scriptId = scriptId || "";
    run.textContent = instance.active_script === scriptId ? "Active" : "Run";
    run.disabled = !scriptId || instance.active_script === scriptId;
    item.append(copy, run);
    elements.scriptList.append(item);
  }
}

function renderLog(instance) {
  const messages = [
    ...(instance.recent_logs || []).map((value) => String(value)),
    ...(instance.recent_messages || []).map(formatMessage),
  ].slice(-20).reverse();
  if (instance.last_status) {
    messages.unshift(instance.last_status);
  }
  elements.eventLog.replaceChildren();
  if (messages.length === 0) {
    appendEmpty(elements.eventLog, "No recent client activity");
    return;
  }
  for (const message of messages.slice(0, 20)) {
    const item = document.createElement("li");
    item.textContent = message;
    elements.eventLog.append(item);
  }
}

async function handleDetailCommand(event) {
  const button = event.target.closest("button[data-command]");
  if (button && state.selectedId) {
    await runCommand(state.selectedId, button.dataset.command, {}, button);
  }
}

async function handleScriptRun(event) {
  const button = event.target.closest("button[data-action='run-script']");
  if (button && state.selectedId && button.dataset.scriptId) {
    await runCommand(
      state.selectedId,
      "scripts.run",
      { id: button.dataset.scriptId, inputs: {} },
      button,
    );
  }
}

async function handleScriptAction(event) {
  event.preventDefault();
  const action = new FormData(elements.scriptActionForm).get("action")?.trim();
  if (!state.selectedId || !action) {
    return;
  }
  const button = elements.scriptActionForm.querySelector("button[type='submit']");
  await runCommand(state.selectedId, "scripts.action", { action }, button);
  elements.scriptActionForm.reset();
}

async function runCommand(instanceId, command, params, button) {
  await withBusy(button, async () => {
    await api(`/api/instances/${encodeURIComponent(instanceId)}/commands`, {
      method: "POST",
      body: { command, params },
    });
    showToast(`${humanize(command)} sent to ${instanceId}`);
  });
}

async function stopInstance(instanceId, button) {
  if (!window.confirm(`Stop GenericClient instance “${instanceId}”?`)) {
    return;
  }
  await withBusy(button, async () => {
    await api(`/api/instances/${encodeURIComponent(instanceId)}/stop`, {
      method: "POST",
      body: {},
    });
    if (elements.detailsDialog.open && state.selectedId === instanceId) {
      elements.detailsDialog.close();
    }
    showToast(`Stopped ${instanceId}`);
  });
}

async function handleStart(event) {
  event.preventDefault();
  if (!elements.startForm.reportValidity()) {
    return;
  }
  const form = new FormData(elements.startForm);
  const spec = {};
  for (const [key, rawValue] of form) {
    const value = rawValue.trim();
    if (value) {
      spec[key] = value;
    }
  }
  const button = elements.startForm.querySelector("button[type='submit']");
  await withBusy(button, async () => {
    const receipt = await api("/api/instances", { method: "POST", body: spec });
    elements.startDialog.close();
    elements.startForm.reset();
    elements.startForm.elements.heap.value = "384m";
    showToast(`Launching ${receipt.result.instance_id}; waiting for health registration`);
  });
}

function openStartDialog() {
  if (!elements.startDialog.open) {
    elements.startDialog.showModal();
    requestAnimationFrame(() => elements.startForm.elements.instance_id.focus());
  }
}

function refreshScreenshot(instanceId, button) {
  const nonce = Date.now();
  const url = `${screenshotUrl(instanceId)}?refresh=1&nonce=${nonce}`;
  const card = state.cards.get(instanceId);
  card?.classList.remove("frame-error");
  if (card) {
    card.querySelector(".js-frame").src = url;
  }
  if (state.selectedId === instanceId) {
    elements.detailFrame.src = url;
    elements.detailFrameStatus.textContent = "Capturing fresh frame…";
    elements.detailFrame.addEventListener("load", () => {
      elements.detailFrameStatus.textContent = `Captured ${new Date().toLocaleTimeString()}`;
    }, { once: true });
    elements.detailFrame.addEventListener("error", () => {
      elements.detailFrameStatus.textContent = "Frame capture failed";
      showToast(`Screenshot failed for ${instanceId}`, true);
    }, { once: true });
  }
  button.blur();
}

async function withBusy(button, operation) {
  if (!button || button.disabled) {
    return;
  }
  const label = button.textContent;
  button.disabled = true;
  button.textContent = "Working…";
  try {
    await operation();
  } catch (error) {
    showToast(error.message, true);
  } finally {
    if (button.isConnected) {
      button.disabled = false;
      button.textContent = label;
    }
  }
}

async function api(url, { method = "GET", body } = {}) {
  const response = await fetch(url, {
    method,
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(payload?.error || `Dashboard returned HTTP ${response.status}`);
  }
  return payload;
}

function setConnection(connection) {
  state.connection = connection;
  document.documentElement.dataset.connection = connection;
  const labels = {
    connecting: "Connecting",
    live: "Live",
    reconnecting: "Reconnecting",
    stale: "Stale",
  };
  elements.connectionLabel.textContent = labels[connection] || humanize(connection);
  updateConnectionAge();
}

function touchConnection() {
  state.lastContact = Date.now();
}

function updateConnectionAge() {
  if (!state.lastContact) {
    elements.streamStatus.textContent = state.connection === "reconnecting"
      ? "Event stream reconnecting"
      : "Opening event stream";
    return;
  }
  const ageSeconds = Math.max(0, Math.floor((Date.now() - state.lastContact) / 1_000));
  if (ageSeconds > 35 && state.connection === "live") {
    state.connection = "stale";
    document.documentElement.dataset.connection = "stale";
    elements.connectionLabel.textContent = "Stale";
  }
  elements.streamStatus.textContent = state.connection === "live"
    ? `Event stream live · contact ${ageSeconds}s ago`
    : `${humanize(state.connection)} · last contact ${ageSeconds}s ago`;
}

function showToast(message, error = false) {
  const toast = document.createElement("div");
  toast.className = `toast${error ? " error" : ""}`;
  toast.textContent = message;
  elements.toastRegion.append(toast);
  setTimeout(() => toast.remove(), 4_500);
}

function findInstance(instanceId) {
  return state.fleet?.instances.find((instance) => instance.instance_id === instanceId) || null;
}

function screenshotUrl(instanceId) {
  return `/api/instances/${encodeURIComponent(instanceId)}/screenshot`;
}

function activityLabel(instance) {
  if (instance.active_script) {
    return `${instance.active_script} · ${humanize(instance.activity || instance.script_state)}`;
  }
  if (instance.breaking) {
    return humanize(instance.behavior?.state || "break");
  }
  if (instance.attention_required) {
    return "Random event attention";
  }
  return humanize(instance.activity || "idle");
}

function formatLocation(world) {
  if (!world || !Number.isFinite(world.x) || !Number.isFinite(world.y)) {
    return "No player location";
  }
  return `${world.x}, ${world.y} · P${world.plane ?? 0}`;
}

function formatMessage(message) {
  if (typeof message === "string") {
    return message;
  }
  const origin = message.name || message.sender || message.type || "game";
  return `${origin}: ${message.text || pretty(message)}`;
}

function formatBytes(value) {
  if (!Number.isFinite(value)) {
    return "—";
  }
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let amount = Math.max(0, value);
  let unit = 0;
  while (amount >= 1_024 && unit < units.length - 1) {
    amount /= 1_024;
    unit++;
  }
  const digits = amount >= 100 || unit === 0 ? 0 : amount >= 10 ? 1 : 2;
  return `${amount.toFixed(digits)} ${units[unit]}`;
}

function formatPercent(value) {
  return Number.isFinite(value) ? `${value.toFixed(value >= 10 ? 1 : 2)}%` : "—";
}

function formatDuration(milliseconds) {
  if (!Number.isFinite(milliseconds)) {
    return "—";
  }
  const seconds = Math.floor(milliseconds / 1_000);
  if (seconds < 60) {
    return `${seconds}s`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes}m`;
  }
  const hours = Math.floor(minutes / 60);
  return hours < 24 ? `${hours}h ${minutes % 60}m` : `${Math.floor(hours / 24)}d ${hours % 24}h`;
}

function formatTimestamp(value) {
  if (!Number.isFinite(value)) {
    return "unknown time";
  }
  return new Date(value).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

function humanize(value) {
  if (value == null || value === "") {
    return "Unknown";
  }
  return String(value)
    .replace(/[._-]+/g, " ")
    .replace(/\b\w/g, (character) => character.toUpperCase());
}

function pretty(value) {
  return value == null ? "Unavailable" : JSON.stringify(value, null, 2);
}

function appendEmpty(parent, message) {
  const item = document.createElement("li");
  item.className = "empty-log";
  item.textContent = message;
  parent.append(item);
}

function setText(element, value) {
  element.textContent = value;
}
