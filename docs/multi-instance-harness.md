# Multi-instance harness

## Decision

Run one RuneLite and GenericClient instance per JVM. Put discovery, process
lifecycle, account selection, routing, waits, and fleet-level UI in an external
harness. GenericClient remains a single-account client agent.

Do not host several injected clients in one JVM. RuneLite and the injected game
client contain substantial process-wide state, while separate processes already
isolate the client thread, Lua VM, synthetic input, overlays, safety controller,
and failures.

## Current state

GenericClient is close to being supervisable, but launching a second copy is not
turnkey yet:

- Every RuneLite profile currently defaults to control port `17343`. A second
  process will collide with the first listener.
- `GenericClientControlServer` already supports an operating-system-assigned
  port internally: Java's HTTP server accepts port `0`, and `getUrl()` returns
  the port actually bound. Only the configuration and discovery path are
  missing.
- The MCP bridge can target a different endpoint through `GENERICCLIENT_URL`,
  so two manually configured ports can be used for an early experiment.
- Scripts and mouse profiles live in shared directories. Sharing them for reads
  is desirable; concurrent catalog edits or mouse recording need one owner.
- Behavior and automation state are already keyed by the derived account
  profile. The harness must reject or explicitly flag two live clients using the
  same account profile.
- There is no instance identity, endpoint registry, PID/account mapping, lease,
  or stale-process cleanup.

The Jagex Launcher supports multiple instances of the same game for different
characters. RuneLite launcher configuration also supports client arguments such
as `--profile=<name>` when RuneLite is launched through Jagex Launcher:

- https://support.runescape.com/hc/en-gb/articles/34153164257425-Jagex-Launcher-FAQ
- https://github.com/runelite/runelite/wiki/RuneLite-Launcher-Configuration

## Target shape

```text
GenericClient Harness
  |-- stable MCP and CLI
  |-- Jagex Launcher adapter
  |-- instance registry, leases, waits, and logs
  |
  |-- RuneLite JVM A -- ephemeral loopback port
  |-- RuneLite JVM B -- ephemeral loopback port
  `-- RuneLite JVM C -- ephemeral loopback port
```

The harness owns the stable address used by Codex. Individual clients use
ephemeral loopback ports and may come and go without changing the MCP
configuration.

## Minimal client changes

Keep the client changes deliberately small:

1. Allow control port `0` and make automatic allocation the supervised-mode
   default.
2. Generate a process-scoped `instance_id` at plugin startup.
3. After the control server binds, atomically publish one descriptor under
   `~/.runelite/genericclient/instances/`.
4. Include `instance_id`, PID, bound endpoint, process start time, lifecycle,
   RuneLite profile, launcher display name, and observed account profile in
   `health` and `status`.
5. Update the descriptor when identity changes and delete it during clean
   shutdown. The harness validates PID and health before trusting a file, so a
   crash may safely leave a stale descriptor.
6. Keep all snapshots, actions, scripts, behavior, and overlays scoped to the
   existing single client. Do not add a client list to the plugin.

Example descriptor:

```json
{
  "schema": "genericclient_instance",
  "instance_id": "f5687417-971d-49fd-beb7-d1b8b330ca88",
  "pid": 23040,
  "started_epoch_millis": 1788180215223,
  "control_url": "http://127.0.0.1:49152",
  "lifecycle": "running",
  "runelite_profile": "genericBoss",
  "launcher_display_name": null,
  "account_profile_id": null
}
```

The descriptor contains routing metadata, not credentials or session tokens.

## Harness responsibilities

The first harness should be a small TypeScript service built around the current
MCP bridge. It owns:

- discovery and health validation;
- mapping `instance_id` to PID, window, endpoint, RuneLite profile, and account;
- launch, attach, focus, safe logout, restart, and termination;
- exactly one mutating-operation lease per client;
- explicit instance routing for every action;
- condition-based waits for script completion, break state, random-event
  attention, login state, and process exit;
- per-instance log and screenshot attribution;
- stale descriptor cleanup and reconnect after a client restart;
- a same-account duplicate guard;
- shared script-catalog installation only while no client is reloading it.

Mutating tools must require an explicit `instance_id` whenever more than one
client exists. A silent global "active client" is unsafe for concurrent agents.
Read-only tools may use the sole live instance when there is exactly one.

The long-term MCP surface should remain domain-oriented rather than exposing a
generic raw proxy. Existing tools can be retained with an `instance_id` routing
field, accompanied by a small fleet surface:

```text
instances_list
instance_status
instance_wait
instance_focus
instance_start
instance_stop
```

## Filesystem ownership

Use these ownership rules:

| Data | Ownership |
| --- | --- |
| Lua catalog | Shared, read-only to clients; harness installs updates |
| Mouse templates | Shared, read-only during normal operation |
| Mouse recording | Exclusive harness lease |
| Behavior profile and state | Per account profile |
| Automation rules and state | Per account profile |
| Active Lua coroutine, bank cache, snapshots | Per process, memory only |
| Instance descriptor and transient logs | Per process instance |

The external `GenericClientScripts` repository is already the correct catalog
boundary. The harness should deploy that catalog; the client should only load
the installed manifest.

## Jagex Launcher lifecycle

Do not bypass Jagex Launcher authentication. The first implementation can ask
the user to launch each selected character and then attach to the new
GenericClient descriptor. The next implementation can drive the existing
Windows launcher account selector and Play button, then associate the newly
created RuneLite PID with the requested account after the client registers.

Starting a process is not success. The harness waits for this sequence:

```text
launcher request
  -> new RuneLite PID
  -> GenericClient descriptor
  -> healthy control endpoint
  -> expected launcher/account identity
  -> login screen or playable world
```

Stopping follows the reverse safety sequence: stop the script, end or account
for active recovery, request logout when logged in, observe the login screen,
then terminate the process if requested.

## Implementation stages

### Stage 1: two-client proof

- Give two profiles fixed distinct ports.
- Run two MCP bridge processes with distinct `GENERICCLIENT_URL` values.
- Prove independent status, screenshot, Lua evaluation, script execution, and
  logout.
- Prove neither client moves the operating-system cursor.

This validates process isolation but is not the permanent operator interface.

### Stage 2: discovery and routing

- Add automatic ports and instance descriptors.
- Add the TypeScript instance registry and stable harness MCP.
- Require explicit instance routing and leases.
- Attach to manually launched Jagex clients.

### Stage 3: lifecycle supervision

- Add Jagex Launcher account selection and launch association.
- Add safe stop/restart, focus management, resource limits, and condition waits.
- Add a compact fleet dashboard only after the CLI/MCP lifecycle is reliable.

### Stage 4: later headless integration

Keep one game client per process. A future displayless mode changes how each
process renders; it does not change harness ownership or Jagex session
bootstrap.

## Acceptance criteria

The harness is ready when all of the following are live-proven:

1. Two different Jagex characters launch into two RuneLite processes.
2. Each process registers a unique instance ID and endpoint without a port
   collision.
3. The harness associates each endpoint with the correct account before any
   mutation.
4. Status and screenshots can be requested concurrently and remain correctly
   attributed.
5. A script can run in one client while the other remains idle or runs a
   different script.
6. Breaks, emergency recovery, and random-event attention stay isolated to the
   owning client.
7. Synthetic mouse and keyboard input do not steal the operating-system cursor
   or target another canvas.
8. Killing one client does not interrupt another, and stale registration is
   removed or ignored.
9. Safe logout, restart, and reattachment work for each instance independently.
10. Concurrent clients cannot overwrite shared scripts or the same account's
    behavior/automation state.

## Non-goals

- Multiple injected clients in one JVM
- Fleet policy inside the RuneLite plugin
- Credential or OTP storage in the harness
- Folding the deferred headless implementation into the first harness slice
