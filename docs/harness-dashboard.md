# Harness fleet dashboard

## Objective

Serve one local web page from the Harness that shows every discovered
GenericClient instance in real time and routes all management through explicit
instance identity.

The browser never contacts RuneLite endpoints directly. It talks only to the
Harness, which owns discovery, health validation, process lifecycle, memory and
CPU sampling, GenericClient RPC, screenshot caching, and event fan-out.

## Module shape

```text
Browser adapters: HTML, HTTP, SSE
                 |
                 v
FleetController
  snapshot() / get(id) / start(spec) / stop(id) / command(id, request)
                 |
       +---------+----------+-------------+
       |                    |             |
InstanceRegistry    ProcessSupervisor   Client RPC
       |                    |             |
Linux metrics        dense bootstrap   status/actions
```

The web server is an adapter over `FleetController`. The existing CLI and the
controller share `InstanceRegistry` and `ProcessSupervisor`; neither calls the
other or duplicates process lifecycle rules.

## Runtime modes

The dashboard uses exact mode names:

| Mode | Meaning |
| --- | --- |
| `stock` | Normal RuneLite presentation and plugin startup. |
| `dense-x11` | Current dense runtime: native Linux JVM, minimal RuneLite graph, AWT canvas under Xvfb, software rendering limited to approximately 1 FPS. |
| `render-gated` | Reserved for a later injected-client draw gate. |
| `protocol-native` | Reserved for a future AWT-free client kernel. |

`dense-x11` is displayless, not true headless.

## Fleet snapshot

One snapshot contains:

- generation timestamp and monotonically increasing sequence;
- healthy and rejected instance counts;
- logged-in, starting, attention-required, breaking, and scripting counts;
- total PSS, USS, RSS, swap, and sampled CPU;
- one normalized record per instance;
- rejected descriptor reasons.

Each instance record contains descriptor identity, mode, lifecycle, game state,
launcher display name, player/world/location, active script/activity, behavior,
automation, safety, random-event attention, recent messages, control health,
PSS/USS/RSS/swap, CPU, uptime, and supported controls.

## HTTP interface

| Method | Route | Result |
| --- | --- | --- |
| `GET` | `/api/fleet` | Complete normalized fleet snapshot. |
| `GET` | `/api/events` | Server-sent `fleet` events; initial snapshot is immediate. |
| `GET` | `/api/instances/:id` | One normalized instance record. |
| `GET` | `/api/instances/:id/screenshot` | Cached PNG; `?refresh=1` forces a client frame. |
| `POST` | `/api/instances` | Start a dense instance from a validated spec. |
| `POST` | `/api/instances/:id/stop` | Stop the selected instance. |
| `POST` | `/api/instances/:id/commands` | Execute one allowlisted domain command. |
| `GET` | `/health` | Harness health and monitor state. |

The command allowlist covers session login/logout, active-break end, script
run/stop/action, random-event acknowledge/complete, and automation
pause/resume. Screenshot refresh has its own typed image route. There is no
generic raw-RPC web route.

JSON request bodies are bounded. Instance IDs use the descriptor-safe
identifier grammar. The server binds `127.0.0.1` by default and emits restrictive
content-security, frame, MIME, and referrer headers.

## Event cadence

- Registry and status: once per second by default.
- PSS/USS/RSS and CPU: included in each monitor sample for the initial fleet;
  cadence remains configurable.
- SSE transmits only changed fleet snapshots plus periodic keepalives.
- Screenshots are not part of SSE. Cards load cached thumbnails independently.
- A forced screenshot is an explicit action because PNG encoding and a fresh
  rendered frame consume client resources.
- The client copies the delivered frame before asynchronous PNG encoding. If a
  dense client exposes an all-black initialization buffer, capture waits for
  one more frame; a legitimately black second frame is still returned.

## Page layout

The page contains:

1. Fleet summary bar with health, attention, scripting, PSS, and CPU totals.
2. Responsive instance-card grid with cached screenshot, identity, state,
   script/activity, memory, CPU, and lifecycle controls.
3. Detail drawer with full status, recent receipts/messages, safety, behavior,
   automation, script controls, and refreshed screenshot.
4. Start dialog for instance ID, heap size, JAR, RuneLite profile, and optional
   AppCDS archive. The server owns the fleet runtime directory.
5. Empty, reconnecting, degraded, and rejected-descriptor states.

Every mutating control names its instance. Bulk mutation is not part of the
first dashboard.

## Acceptance

1. With no clients, the page loads and displays a stable empty state.
2. Two dense clients appear without a page refresh and have distinct identity,
   endpoints, status, memory, CPU, and screenshots.
3. SSE updates state without browser polling.
4. Stopping one card removes only that instance; the survivor stays healthy.
5. Starting a replacement through the page/API yields a new descriptor and
   card.
6. Session and script commands are explicitly routed and unknown commands fail.
7. Screenshot refresh returns a valid PNG and does not expose the client RPC
   endpoint to browser code.
8. The dashboard renders correctly in headless Chrome at desktop and narrow
   viewport sizes.
9. Tests, PMD/coverage, MCP tests, Harness tests, and live receipts pass from a
   clean tree based on newest `origin/main`.
