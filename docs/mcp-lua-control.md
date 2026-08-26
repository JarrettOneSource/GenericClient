# MCP and Lua control

GenericClient exposes the live RuneLite client to a local MCP server. The MCP
process uses standard input/output with Codex and forwards tool calls to the
plugin's loopback bridge.

```text
Codex
  -> GenericClient MCP server (stdio)
      -> http://127.0.0.1:17343/rpc
          -> Lua host
              -> snapshots, behavior controller, walker, and synthetic input
```

RuneLite must be running with GenericClient loaded. The dashboard and MCP tools
share the same Lua host and script registry.

## Install the MCP server

From the repository:

```bash
cd mcp
npm install
```

On this Windows + WSL installation, register the server with Windows Node so it
can reach RuneLite's Windows loopback address:

```bash
codex mcp add genericclient -- \
  "/mnt/c/Program Files/nodejs/node.exe" \
  '\\wsl.localhost\Ubuntu\home\user\GenericClient\mcp\src\server.mjs'
```

When Codex and RuneLite run on the same operating system, use that system's
`node` command and local path instead. `GENERICCLIENT_URL` optionally overrides
the default `http://127.0.0.1:17343` bridge address.

Confirm the saved entry:

```bash
codex mcp get genericclient
codex mcp list
```

Start a new Codex session after adding the server. Local Codex clients share
their MCP configuration through `~/.codex/config.toml`, as described by the
[official OpenAI MCP documentation](https://developers.openai.com/codex/extend/mcp).

If the RuneLite setting **MCP bridge port** changes, set `GENERICCLIENT_URL` to
the same port in the MCP server configuration.

## MCP tools

| Tool | Purpose |
| --- | --- |
| `client_status` | Read player position, game state, Lua state, scripts, mouse profile, and recent logs. |
| `behavior_profile` | Read the deterministic human-readable profile and numeric traits. |
| `behavior_status` | Read the current break, countdown, long pressure, and break counts. |
| `session_logout` | Deliberately log out through visible widgets with synthetic input. |
| `session_login` | Restore the active Jagex Launcher session and enter the world. |
| `lua_eval` | Execute an ad-hoc Lua snippet and receive its returned value. |
| `lua_repl_reset` | Clear globals created by previous REPL calls. |
| `script_list` | List scripts registered in the manifest. |
| `script_get` | Read one script's metadata and source. |
| `script_save` | Write a complete Lua file and register or update its manifest entry. |
| `script_run` | Start a registered script by id. |
| `script_stop` | Stop the active standalone script. |
| `script_reload_manifest` | Reload the manifest after external file edits. |

Start with `client_status` so coordinates and login state come from the current
client rather than assumptions.

## Lua REPL

`lua_eval` accepts the body of a Lua function. Use `return` to send a value back
to MCP:

```lua
return gc.read("player")
```

```lua
return gc.read("npcs", {
  within = 12,
  limit = 20,
})
```

The REPL keeps global variables between calls:

```lua
sample_count = (sample_count or 0) + 1
return sample_count
```

Locals belong only to the current call. `lua_repl_reset` clears the persistent
state.

REPL code can wait for ticks and semantic actions:

```lua
local result = gc.await {
  action = {
    type = "walk.to",
    destination = { x = 3210, y = 3424, plane = 0 },
    within = 3,
  },
  timeout = { game_ticks = 600 },
}

return result
```

Each composite client interaction uses the account behavior profile by default.
A time-sensitive task can bypass both break classes for all of its interactions:

```lua
return gc.await {
  action = { type = "walk.random" },
  breaks = false,
}
```

Major state transitions can request the profile's heavier evaluation:

```lua
return gc.phase("banking.complete")
```

`gc.read("behavior")` returns the same structured state exposed by
`behavior_status`.

## Standalone scripts

Standalone scripts live in:

```text
~/.runelite/genericclient/scripts/
```

`manifest.json` is the registry shown in the RuneLite Scripts tab and returned by
`script_list`:

```json
{
  "schema": "genericclient_scripts.v1",
  "scripts": [
    {
      "id": "where-am-i",
      "name": "Where am I?",
      "description": "Log the current player snapshot once.",
      "file": "where-am-i.lua"
    }
  ]
}
```

The matching `where-am-i.lua` is:

```lua
return function()
  gc.await { event = "game.tick" }
  gc.log("info", "player", gc.read("player"))
end
```

Every file returns one root function. Inside that function, scripts use only:

```lua
gc.read(subject, query)
gc.await(request)
gc.log(level, event, fields)
gc.phase(name, options)
```

The easiest programmatic path is `script_save`, which writes both the Lua file
and manifest entry. For manual editing, add the file and manifest row, then
press **Reload list** in the Scripts tab or call `script_reload_manifest`.

Only one standalone script is active at a time. Starting another replaces it.
The REPL is separate, so short interactive queries can run while a diagnostic
standalone script is waiting on ticks.

## Developer checks

```bash
./gradlew test
cd mcp
npm test
```

The Java tests cover the manifest, persistent REPL, and loopback RPC. The Node
tests cover bridge errors and MCP tool registration/forwarding.
