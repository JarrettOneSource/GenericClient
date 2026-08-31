import path from "node:path";

import { callInstance } from "./client.mjs";
import { DashboardServer } from "./dashboard-server.mjs";
import { FleetController } from "./fleet-controller.mjs";
import { FleetMonitor } from "./fleet-monitor.mjs";
import { ProcessMetricsSampler } from "./process-metrics.mjs";
import { ProcessSupervisor } from "./process-supervisor.mjs";
import { InstanceRegistry } from "./registry.mjs";
import { ScreenshotCache } from "./screenshot-cache.mjs";

const LOOPBACK_HOSTS = new Set(["127.0.0.1", "::1", "localhost"]);

export function createDashboardRuntime(
  {
    runtimeDirectory,
    instanceDirectory = path.join(runtimeDirectory, "instances"),
    repositoryDirectory,
    harnessDirectory,
    host = "127.0.0.1",
    port = 3_765,
    pollIntervalMs = 1_000,
    screenshotTtlMs = 10_000,
  },
  overrides = {},
) {
  if (!runtimeDirectory || !repositoryDirectory || !harnessDirectory) {
    throw new Error("Dashboard runtime, repository, and Harness directories are required");
  }
  if (!LOOPBACK_HOSTS.has(host)) {
    throw new Error("dashboard host must be a loopback address");
  }
  const registry = overrides.registry || new InstanceRegistry(path.resolve(instanceDirectory));
  const supervisor = overrides.supervisor || new ProcessSupervisor({
    runtimeDirectory,
    instanceDirectory,
    repositoryDirectory,
    harnessDirectory,
  });
  const metricsSampler = overrides.metricsSampler || new ProcessMetricsSampler();
  const controller = overrides.controller || new FleetController({
    registry,
    supervisor,
    metricsSampler,
  });
  const screenshotCache = overrides.screenshotCache || new ScreenshotCache({
    registry,
    callInstance,
    ttlMs: screenshotTtlMs,
  });
  const monitor = overrides.monitor || new FleetMonitor({ controller, pollIntervalMs });
  const server = overrides.server || new DashboardServer({
    controller,
    monitor,
    screenshotCache,
    host,
    port,
  });
  return {
    registry,
    supervisor,
    metricsSampler,
    controller,
    screenshotCache,
    monitor,
    server,
    start: () => server.start(),
    close: () => server.close(),
  };
}
