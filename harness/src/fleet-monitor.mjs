export class FleetMonitor {
  constructor(
    {
      controller,
      pollIntervalMs = 1_000,
      now = () => Date.now(),
      setTimeoutImpl = globalThis.setTimeout,
      clearTimeoutImpl = globalThis.clearTimeout,
    },
  ) {
    if (!controller || typeof controller.snapshot !== "function") {
      throw new Error("FleetMonitor requires a controller with snapshot()");
    }
    if (!Number.isSafeInteger(pollIntervalMs) || pollIntervalMs <= 0) {
      throw new Error("pollIntervalMs must be a positive integer");
    }
    this.controller = controller;
    this.pollIntervalMs = pollIntervalMs;
    this.now = now;
    this.setTimeoutImpl = setTimeoutImpl;
    this.clearTimeoutImpl = clearTimeoutImpl;
    this.listeners = new Set();
    this.running = false;
    this.timer = null;
    this.latest = null;
    this.lastFingerprint = null;
    this.lastPollEpochMillis = null;
    this.lastEventEpochMillis = null;
    this.lastError = null;
    this.refreshTail = Promise.resolve();
  }

  async start() {
    if (this.running) {
      return this.latest;
    }
    this.running = true;
    try {
      await this.refresh();
    } catch {
      // A transient first poll must not prevent the monitor from recovering.
    }
    if (this.running) {
      this.#schedule();
    }
    return this.latest;
  }

  refresh() {
    const request = this.refreshTail.then(() => this.#refreshNow());
    this.refreshTail = request.catch(() => {});
    return request;
  }

  async #refreshNow() {
    try {
      const snapshot = await this.controller.snapshot();
      const fingerprint = semanticFingerprint(snapshot);
      this.latest = snapshot;
      this.lastPollEpochMillis = this.now();
      this.lastError = null;
      if (fingerprint !== this.lastFingerprint) {
        this.lastFingerprint = fingerprint;
        this.lastEventEpochMillis = this.lastPollEpochMillis;
        this.#publish(snapshot);
      }
      return snapshot;
    } catch (error) {
      this.lastPollEpochMillis = this.now();
      this.lastError = {
        message: error?.message || String(error),
        at_epoch_millis: this.lastPollEpochMillis,
      };
      throw error;
    }
  }

  subscribe(listener) {
    if (typeof listener !== "function") {
      throw new Error("FleetMonitor subscriber must be a function");
    }
    this.listeners.add(listener);
    if (this.latest) {
      listener(this.latest);
    }
    return () => this.listeners.delete(listener);
  }

  status() {
    return {
      running: this.running,
      poll_interval_millis: this.pollIntervalMs,
      subscriber_count: this.listeners.size,
      has_snapshot: Boolean(this.latest),
      last_poll_epoch_millis: this.lastPollEpochMillis,
      last_event_epoch_millis: this.lastEventEpochMillis,
      last_error: this.lastError,
    };
  }

  close() {
    this.running = false;
    if (this.timer !== null) {
      this.clearTimeoutImpl(this.timer);
      this.timer = null;
    }
    this.listeners.clear();
  }

  async #poll() {
    if (!this.running) {
      return;
    }
    try {
      await this.refresh();
    } catch {
      // The error is exposed through status(); the next sample retries normally.
    } finally {
      if (this.running) {
        this.#schedule();
      }
    }
  }

  #schedule() {
    this.timer = this.setTimeoutImpl(() => {
      this.timer = null;
      void this.#poll();
    }, this.pollIntervalMs);
    this.timer?.unref?.();
  }

  #publish(snapshot) {
    for (const listener of this.listeners) {
      try {
        listener(snapshot);
      } catch {
        // One disconnected subscriber must not starve the rest of the fleet.
      }
    }
  }
}

function semanticFingerprint(snapshot) {
  return JSON.stringify({
    summary: snapshot?.summary,
    instances: snapshot?.instances,
    pending_launches: snapshot?.pending_launches,
    rejected: snapshot?.rejected,
  });
}
