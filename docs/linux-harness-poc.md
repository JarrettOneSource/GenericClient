# Linux multi-instance Harness PoC

## Objective

Prove the difficult seams needed to run GenericClient as a supervised Linux
fleet while preserving the official Jagex Launcher as the eventual account and
character launcher.

This PoC does not require a Jagex login. The later live-login step should only
have to replace fake launcher credentials with the environment inherited from
the official Launcher.

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
- Dense rendering changes presentation frequency, not state progression.
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
