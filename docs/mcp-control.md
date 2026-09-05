# MCP and loopback control

The Node MCP bridge routes to GenericClient's loopback `/rpc` endpoint. The
normal port is `17343`; supervised instances can use an assigned port and an
instance descriptor. Select a specific instance when several clients exist.

Start with `client_status`, then `account_snapshot` before planning account work.
Status contains `scripts`, `behavior`, `automation`, `random_event`, and input
state. `scripts.active` describes the current or last run. The bridge also exposes
screenshots, scene highlights, account notes, session controls, and schedule
configuration.

| MCP tool | Operation |
| --- | --- |
| `java_eval` | Compile and run one Java diagnostic body |
| `script_list` | List discovered annotated entry points |
| `script_get` | Read one script's metadata, inputs, and buttons |
| `script_compile` | Compile `class_name` and `source`, then publish the validated catalog |
| `script_run` | Start an ID with its input values |
| `script_action` | Queue a declared cooperative button |
| `script_stop` | Stop the current run and revoke its input |
| `script_reload` | Rescan external JARs |

For example, a Java diagnostic body can return:

```java
return com.genericclient.script.SnapshotData.read("player");
```

Diagnostics require an idle script worker. An unresolved event may remain latched
while the operator inspects or solves it. Complete the event through
`random_event_complete` after observing its outcome; acknowledgement alone does
not release the interrupted run. Automatic event solvers execute as Java scripts
with exclusive ownership.

Source compilation requires a JDK in the client process. Externally compiled
JARs can be loaded without it. See [Java scripting](java-scripting.md) for the SDK
and lifecycle contracts.

HTTP RPC accepts JSON objects containing `method` and `params`. It is loopback
only and rejects browser-originated requests. Java execution is trusted code;
control access must stay with the local operator or selected harness instance.

`mcp/scripts/wait-client.ps1` distinguishes completion, disappearance, death,
attention, and script faults. Its status checks use the same `scripts` object as
the dashboard. The MCP smoke script compiles a Java diagnostic entry point; run
it only against the intended client instance.
