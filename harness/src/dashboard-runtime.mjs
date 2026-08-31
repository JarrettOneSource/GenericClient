import path from "node:path";

import { callInstance } from "./client.mjs";
import { DashboardServer } from "./dashboard-server.mjs";
import { FleetController } from "./fleet-controller.mjs";
import { FleetMonitor } from "./fleet-monitor.mjs";
import { LauncherBroker } from "./launcher-broker.mjs";
import { LauncherHandoffServer } from "./launcher-handoff-server.mjs";
import { defaultLauncherSocket } from "./launcher-handoff-path.mjs";
import { ProcessMetricsSampler } from "./process-metrics.mjs";
import { ProcessSupervisor } from "./process-supervisor.mjs";
import { InstanceRegistry } from "./registry.mjs";
import { ScreenshotCache } from "./screenshot-cache.mjs";

const LOOPBACK_HOSTS = new Set(["127.0.0.1", "::1", "localhost"]);

export function createDashboardRuntime(
  {
    runtimeDirectory,
    instanceDirectory,
    repositoryDirectory,
    harnessDirectory,
    host = "127.0.0.1",
    port = 3_765,
    pollIntervalMs = 1_000,
    screenshotTtlMs = 10_000,
    launcherSocket = defaultLauncherSocket(),
    launcherMode = "stock",
  },
  overrides = {},
) {
  if (!runtimeDirectory || !instanceDirectory || !repositoryDirectory || !harnessDirectory) {
    throw new Error(
      "Dashboard runtime, instance, repository, and Harness directories are required",
    );
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
  const launcherBroker = overrides.launcherBroker || new LauncherBroker({
    registry,
    supervisor,
    socketPath: launcherSocket,
    defaultMode: launcherMode,
  });
  const metricsSampler = overrides.metricsSampler || new ProcessMetricsSampler();
  const controller = overrides.controller || new FleetController({
    registry,
    supervisor,
    metricsSampler,
    launcherBroker,
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
  const launcherHandoffServer = overrides.launcherHandoffServer ||
    new LauncherHandoffServer({ socketPath: launcherSocket, broker: launcherBroker });
  const start = async () => {
    await launcherHandoffServer.start();
    try {
      return await server.start();
    } catch (error) {
      await launcherHandoffServer.close();
      throw error;
    }
  };
  const close = async () => {
    try {
      await launcherHandoffServer.close();
    } finally {
      await server.close();
    }
  };
  return {
    registry,
    supervisor,
    metricsSampler,
    controller,
    launcherBroker,
    launcherHandoffServer,
    screenshotCache,
    monitor,
    server,
    start,
    close,
  };
}
