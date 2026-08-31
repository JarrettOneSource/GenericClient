# Linux multi-instance Harness PoC

## Objective

Prove the difficult seams needed to run GenericClient as a supervised Linux
fleet while preserving the official Jagex Launcher as the eventual account and
character launcher.

This PoC does not require a Jagex login. The later live-login step should only
have to replace fake launcher credentials with the environment inherited from
the official Launcher.

The official Jagex Launcher remains the only session bootstrap. The Harness
does not mint, store, refresh, or reconstruct launcher credentials.

## Proof boundaries

| Seam | PoC receipt |
| --- | --- |
| Official-launcher-compatible handoff | Wine starts an executable shell bridge named `RuneLite.exe`; the native child observes the presence of the `JX_SESSION_ID`, `JX_CHARACTER_ID`, and `JX_DISPLAY_NAME` variables without recording their values. |
| Native Linux client | The bridge executes a native Linux JVM rather than a Windows JVM under Wine. |
| Dense RuneLite runtime | A custom RuneLite startup path retains the injected Jagex client, RuneLite events, GenericClient, synthetic input, and on-demand screenshots while omitting the normal plugin/UI startup graph. |
| Isolated process ownership | Each client has its own home, PID, instance ID, ephemeral loopback endpoint, descriptor, and lifecycle. |
| Harness discovery | The Harness rejects stale or unhealthy descriptors and maps a healthy instance ID to the endpoint that reports the same identity. |
| Independent control | Two clients answer status and screenshot requests independently; stopping one does not stop the other. |
| Memory evidence | Linux `smaps_rollup` receipts report RSS, PSS, USS, anonymous/file/shared proportional memory, and the marginal PSS of the second client. |
| Shared runtime eligibility | Both clients use the same JRE and GenericClient JAR inode; an AppCDS archive is attempted only when it is compatible with the exact runtime. |
| Fleet operator surface | One loopback web page discovers all healthy instances, streams changed status over SSE, proxies cached screenshots, reports Linux PSS/USS/CPU, and routes lifecycle or domain commands through explicit instance IDs. |
| Normal-launch preservation | When no Harness is running, the Linux bridge starts full stock RuneLite through `GenericClientLauncher`, with GenericClient and the normal MCP port. When the Harness is running, the bridge transfers the official handoff over a private Unix socket and the Harness starts stock RuneLite by default. |

## Live-login work deliberately deferred

- Installing and signing in to the official Jagex Launcher under Wine.
- Selecting multiple real characters.
- Replacing the fake `JX_*` variables in the bridge test with real inherited
  values.
- Proving account identity after entering a playable world.

The PoC must never print or persist launcher credential values. Instance
descriptors contain routing and lifecycle metadata only.

## Acceptance scenarios

### One client

1. Build the exact shadow JAR.
2. Launch the dense client under Xvfb with an isolated home and port `0`.
3. Wait for an atomic descriptor and healthy endpoint.
4. Observe `LOGIN_SCREEN` through `status`.
5. Capture a PNG from the next rendered frame.
6. Record the client PID and Linux memory receipt.
7. Stop the client and observe descriptor removal.

### Two clients

1. Launch two dense clients with separate homes and the same JRE/JAR files.
2. Observe unique instance IDs, PIDs, descriptors, and bound endpoints.
3. Route status and screenshots explicitly to both instances.
4. Stop one instance and prove the survivor remains healthy.
5. Record fleet PSS and the second client's marginal PSS.

### Wine bridge

1. Set fake launcher environment variables.
2. Ask Wine to execute the bridge named `RuneLite.exe`.
3. Confirm the native probe reports that all required variables were inherited.
4. Confirm the probe output contains no supplied value.

## Reliability invariants

- Every 20 ms client cycle and every server-tick event remains owned by the
  injected client.
- `GameTick`, `PostClientTick`, deferred events, and `ClientThread` work retain
  stock ordering.
- Dense mode suppresses ordinary canvas presentation, not state progression.
- Mutating control always resolves an explicit `instance_id` when more than one
  client is live.
- A descriptor is never trusted until both PID and endpoint health agree.
- Process exit, endpoint loss, or identity mismatch makes an instance
  unavailable immediately.

## Validation commands

### Prerequisites

The tested host was Ubuntu 24.04 on WSL2 with OpenJDK 21, Xvfb, Wine 9, and
Node.js 22. RuneLite requires the non-headless Java desktop package, not only
`openjdk-21-jre-headless`:

```bash
sudo apt-get install -y openjdk-21-jre
```

Wine is needed only for the launcher-bridge compatibility receipt. Dense
RuneLite itself is a native Linux Java process.

### Build and test

```bash
./gradlew test shadowJar
npm --prefix mcp test
npm --prefix harness test
./gradlew qualityReport
git diff --check
```

### Launch one dense client

```bash
runtime_dir=$(mktemp -d /tmp/genericclient-harness-poc.XXXXXX)
node harness/src/cli.mjs launch-dense \
  --runtime "$runtime_dir" \
  --instance poc-one \
  --jar "$PWD/build/libs/GenericClient.jar"

node harness/src/cli.mjs wait \
  --runtime "$runtime_dir" \
  --instance poc-one \
  --state LOGIN_SCREEN \
  --timeout 60000
```

The live receipt reached `LOGIN_SCREEN`, published protocol-1 status and an
atomic `genericclient_instance.v1` descriptor, and bound an ephemeral loopback
port. `screenshot.capture` returned a valid 765x503 RGBA PNG.

The unchanged stock `GenericClientLauncher` path was also launched under Xvfb
with an isolated home. It reached `LOGIN_SCREEN`, reported `dense=false`, bound
an ephemeral endpoint, and removed its descriptor on shutdown. Dense-mode
changes therefore did not replace or break the normal client path.

### Run the fleet dashboard

Build the JAR, then start the Harness server against the same runtime directory
used by every supervised client:

```bash
./gradlew shadowJar

npm --prefix harness run dashboard -- \
  --runtime "$runtime_dir" \
  --port 3765
```

Open `http://127.0.0.1:3765`. The page works with an empty registry and adds or
removes cards as clients register and exit. Each card shows a cached game
frame, identity, lifecycle, game state, activity, PSS, USS, CPU, and uptime.
The inspector exposes recent client activity, normalized behavior/automation/
safety state, the installed script catalog, and only the Harness command
allowlist.

The default descriptor directory is the same registry used by ordinary
RuneLite:

```text
~/.runelite/genericclient/instances/
```

This gives the operator two compatible paths:

1. Open the official Jagex Launcher normally and press Play. If the Harness is
   absent, the bridge starts full RuneLite directly. If the Harness is running,
   it receives the handoff and supervises the same full RuneLite process.
2. In the web page, arm an instance ID and optional exact display name, then
   press Play for that character in the official Launcher. Repeating this for
   more characters creates independently routed instances. The local dense
   form remains an advanced test path and does not create a Jagex session.

The official Launcher has no documented character-selection CLI on Linux. The
current Harness owns the secure Play handoff and process association; automating
the launcher's character picker remains a later adapter that requires an
installed, signed-in Launcher. Jagex still documents the official Launcher as
unsupported on Linux, so this project uses Jagex's Windows launcher through
Wine rather than a third-party account launcher:
<https://support.runescape.com/hc/en-gb/articles/33992563142673-Downloading-the-Jagex-Launcher-on-Linux>.

The server binds loopback only because the first version has mutating controls
and no user authentication. Use an SSH or private-network tunnel rather than
binding it to a public interface. The browser never receives a client's
ephemeral control URL and has no raw RPC route.

Useful server options are:

```text
--runtime <directory>        fleet runtime root
--directory <directory>      descriptor directory override
--host <loopback>            127.0.0.1, ::1, or localhost
--port <0-65535>             HTTP port; 0 selects an ephemeral test port
--poll <milliseconds>        serialized fleet sample interval
--screenshot-ttl <millis>    per-instance PNG cache lifetime
--launcher-socket <path>     private official-launcher handoff socket
--jagex-mode <stock|dense>   default supervised Play mode; stock is default
```

`GET /api/events` carries only changed fleet snapshots plus heartbeat events;
the browser does not poll instance status. Screenshots remain out-of-band and
are refreshed explicitly because capturing and PNG encoding have a real client
cost. The complete route and state contract is in
[`harness-dashboard.md`](harness-dashboard.md).

The 2026-08-31 live acceptance run launched `dash-a` and `dash-b` through the
HTTP lifecycle API with separate JVM PIDs. Both appeared as healthy
`dense-x11` clients at `LOGIN_SCREEN`; the fleet snapshot reported two healthy
instances, no rejected descriptors, and Linux PSS/USS/CPU samples. Forced
screenshots for both were independent 765x503 RGBA PNGs with different SHA-256
digests. An SSE subscriber observed the fleet transition from
`[dash-a, dash-b]` to `[dash-b]` after the API stopped `dash-a`; `dash-b` kept
its PID, health, state, and screenshot. Starting `dash-c` through the same API
restored a two-client healthy fleet.

Headless Chrome rendered the live page at 1440x1100 and 420x900. A DevTools
runtime check found two instance cards, a live SSE connection, a working
instance inspector with a 765-pixel frame and nine normalized facts, a working
launch dialog with focused identity input, and no JavaScript runtime
exceptions.

After adding official-launch ownership, the Harness armed two simultaneous
stock requests, rendered both exact and wildcard character associations, and
reported a stock Unix handoff ready while the fleet was empty. Browser
inspection confirmed that **New client** opens the normal Jagex form first,
selects full RuneLite by default, focuses the instance identity, keeps dense
mode collapsed, and raises no runtime exceptions. A real shell bridge and Wine
CreateProcess both transferred fake `JX_*` values into an in-memory Harness
handoff without printing or persisting them. The actual signed-in Launcher
remains deliberately untested until it is installed and the user is ready to
log into characters.

The final end-to-end handoff receipt armed `handoff-dense`, invoked the real
shell bridge with fake session and character values, matched the exact launcher
display name, started the dense client through the Harness, registered its
distinct PID and endpoint, and reached `LOGIN_SCREEN`. Searching the complete
runtime directory found neither fake session nor character value. The browser
snapshot contained only the allowed display name and normalized instance state.

The post-rebase run also exposed and fixed a first-capture race: one dense
client initially supplied its all-black initialization buffer. Screenshot
capture now copies the frame synchronously before asynchronous PNG encoding and
skips one blank initialization frame. A fresh-process live check returned the
rendered 765x503 login screen on its first dashboard request; a unit contract
also preserves legitimately black clients by returning the second blank frame.

### What dense headless means

`dense-x11` is displayless, but it is not Java true-headless. Each process still
creates the injected Jagex client, AWT event queue, RuneLite canvas, and a
software-rendered frame under Xvfb. Dense startup enables low-memory mode and a
1 FPS unlocked rendering target. The dense callback forwards completed buffers
to on-demand screenshot listeners but suppresses ordinary canvas presentation.
The 20 ms client cycle, game ticks, deferred events, client-thread work,
snapshots, and synthetic input retain their normal ordering. This is the
current reliability-preserving mode and is suitable for the multi-client
Harness PoC.

Running with `java.awt.headless=true` cannot host the current RuneLite/Jagex
canvas. A materially leaner next mode should gate expensive final raster and
presentation work inside the injected client while preserving state updates
and on-demand frames. An AWT-free protocol-native client is a separate client
implementation with a much larger revision, authentication, cache, script, and
semantic maintenance burden; it is not a near-term extension of this Harness.

### Launch two clients

The accepted two-client run registered:

| Instance | PID | Endpoint |
| --- | ---: | --- |
| `fleet-a` | 2808955 | `http://127.0.0.1:43205` |
| `fleet-b` | 2808981 | `http://127.0.0.1:34029` |

Both reached `LOGIN_SCREEN` and returned independent PNG screenshots. After
`fleet-a` was stopped, `fleet-b` remained healthy at its original PID and
endpoint. Both descriptors disappeared during clean shutdown.

### Linux memory receipt

The memory run used separate Xvfb processes, the same JRE/JAR inodes, 512 MiB
maximum heaps, two visible login screens, no AppCDS archive, and no logged-in
accounts. Values are one observed PoC run, not production limits.

| Measurement | Bytes | MiB |
| --- | ---: | ---: |
| One-client fleet PSS | 304052224 | 290.0 |
| One-client fleet USS | 291082240 | 277.6 |
| Two-client fleet PSS | 589465600 | 562.2 |
| Two-client fleet USS | 567656448 | 541.4 |
| Marginal second-client PSS | 285413376 | 272.2 |
| Marginal second-client USS | 276574208 | 263.8 |

Use the Harness to reproduce the receipt:

```bash
node harness/src/cli.mjs memory --runtime "$runtime_dir"
```

The useful capacity number is marginal PSS, not summed RSS.

### Wine handoff receipt

```bash
node harness/src/cli.mjs probe-bridge --wine
```

Wine executed `harness/bin/GenericClient-RuneLite.exe` as a native Linux shell
bridge. The child observed all three Jagex-account launcher variables and
reported `native_platform=linux`. The receipt stores only presence booleans;
the supplied values were absent from both output and descriptor data.

### AppCDS receipt

```bash
node harness/src/cli.mjs launch-dense \
  --runtime "$runtime_dir" \
  --instance cds-train \
  --archive-output "$runtime_dir/dense.jsa"

# Stop cds-train after it reaches LOGIN_SCREEN, then:
node harness/src/cli.mjs launch-dense \
  --runtime "$runtime_dir" \
  --instance cds-use \
  --archive "$runtime_dir/dense.jsa"
```

OpenJDK 21 generated a 42 MiB dynamic archive. The validation process opened
both the JDK base archive and `dense.jsa`, mapped its dynamic read-write,
read-only, and bitmap regions, then reached `LOGIN_SCREEN`. Multi-process PSS
comparison is still required before treating AppCDS as a capacity improvement.

## Current limitations

- The official Jagex Launcher was not signed in during this PoC. Wine's
  CreateProcess-compatible shell handoff and the injected client's expected
  environment were proven with non-secret probe values.
- Each dense instance currently owns one Xvfb process. A shared or sharded Xvfb
  experiment may reduce the marginal footprint further.
- `dense-x11` is not `java.awt.headless=true`; it intentionally retains the AWT
  canvas and low-rate software renderer so screenshots and client semantics
  remain reliable. A measured on-demand render gate is still future work.
- Dense callbacks intentionally omit normal RuneLite overlays, infoboxes,
  Discord, telemetry, Plugin Hub, and the sidebar.
- Dense callback access to RuneLite's package-private client-thread drains is
  revision-sensitive and must remain covered by both unit and live startup
  tests when RuneLite updates.
- The initial run reported unavailable audio on the server, but logic, status,
  control, and screenshots were healthy.
- Per-instance network profiles, KSM, compact object headers, and centralized
  immutable mouse/collision mappings are later optimization layers.

## Next official-Launcher validation

1. Install the official Jagex Launcher into a dedicated Wine prefix.
2. Register the RuneLite install location in that prefix as the directory
   containing `harness/bin/GenericClient-RuneLite.exe`.
3. Sign in normally and select one character.
4. Press Play and verify the bridge-created descriptor's launcher display name,
   PID, endpoint, and `LOGIN_SCREEN` state before calling `session.login`.
5. Repeat with a second character only after the single-character receipt is
   stable.
