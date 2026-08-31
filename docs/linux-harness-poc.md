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

The final receipts and exact commands are added after the executable PoC passes.

