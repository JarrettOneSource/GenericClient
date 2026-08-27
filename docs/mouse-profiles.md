# Mouse profiles

GenericClient owns its mouse matcher and recording format. It does not depend
on the Pernasua Mouse application, service, DreamBot integration, training UI,
or neural model.

## Default data

The bundled profile was converted from the archived on-device Template Match
model on the NFO host:

- source: `/root/projects/dreambot/archive/pmouse-pre-cutover/2026-08-02/runtime-data/models/template/active-model.json`
- source model: `2dc51a509928416f8b1d020cd64547f4`
- source SHA-256: `a11339c946f7b056a6b4536798151cd33db8457bdbf69a747972d3a807e4952e`
- converted profile SHA-256: `9f0e49d0b3c374f25bef810153db3c9d136ebfb45a1273783dadd5b69e6b89be`
- templates: 6,069

Only trajectory data was converted. The profile contains no Pernasua Mouse
runtime code or application state.

### Parity receipt

The archived matcher JAR has SHA-256
`1ed6bf764d529575cce09f9254a4e29d6a5260a2647628f55cb4c4aca43506fc`.
For start `(100, 200)`, target `(700, 500)`, viewport `1920x1080`, duration
`432`, and seed `7`, that JAR and GenericClient produced identical hexadecimal
floating-point values for all 128 `(time, x, y)` output points. Both output
files had SHA-256
`7005b20b44a9e2561718c0f07eeb29ed75f642ee4e3a611faaee8b5ffaa15b`.

## Runtime location

On first startup, GenericClient installs the bundled profile at:

```text
~/.runelite/genericclient/mouse-profiles/default.json
```

The Settings page selects one filename from that directory. Copy another profile
into the directory, select it, and press **Reload profile**. A failed load leaves the
currently active profile unchanged and writes the parse error to `client.log`.

The active account behavior profile controls the playback duration supplied to
the matcher. Seeded values range from 300 through 650 milliseconds in 25 ms
steps. **Move duration** in the Behavior section saves an account-specific
override; **Use seeded** restores the derived value.

## Cursor effects

Settings offers three client-only effects:

- **Off** disables the trail/path decoration.
- **Trail** draws PMouse's green 1.8-second fading cursor trail.
- **Path** draws the current generated route in cyan and completed progress in
  green.

The synthetic cursor itself is always visible. Inside the canvas it is drawn as
a white pointer with a green origin mark. Off-canvas idle is represented by a
small directional badge pinned to the matching canvas edge. The account keeps
the same seeded idle side, but every return randomizes the off-screen depth and
the coordinate along that edge before generating the recorded path. Re-entry
therefore does not retrace the exact point where the cursor previously left.

The port keeps the original PMouse colors, line weights, point limits, and
cosine fade. It draws in a RuneLite overlay and does not move the operating
system cursor.

## Recording a profile

The Settings page has one **Record** button that turns into **Stop recording** while a session is active.
While recording, manually move and click inside the RuneLite canvas. Generated
GenericClient movement is excluded. The recorder:

1. splits movement after a 240 millisecond idle gap or a 360 pixel jump;
2. keeps movements from 40 through 1,800 pixels with at least four samples;
3. converts each movement into 32 destination-relative points;
4. preserves arc-length geometry and normalized timing;
5. marks a movement as a click approach when a nearby left press follows it;
6. writes a new `recorded-<timestamp>.json` and selects it.

Recording starts a fresh profile. It never overwrites another profile file.
Profile files can be copied between GenericClient installations.

## File contract

| Field | Type | Required |
| --- | --- | --- |
| `schema` | string, exactly `genericclient_mouse_profile.v1` | yes |
| `profile_id` | non-empty string | yes |
| `templates` | non-empty array | yes |
| `distance_px` | positive number | per template |
| `duration_ms` | positive number | per template |
| `angle_rad` | finite number | per template |
| `path` | 64 finite numbers | per template |
| `time_norm` | 32 normalized numbers | no |
| `start_norm` | normalized `[x, y]` | no |
| `target_norm` | normalized `[x, y]` | no |
| `approach` | boolean | no |

`path` must contain 64 numbers: 32 `(forward, lateral)` pairs relative to the
movement's start and destination. Its first pair is `(0, 0)` and final pair is
`(1, 0)`. `time_norm` contains 32 monotonically increasing values from zero to
one. `start_norm`, `target_norm`, and `approach` are optional; missing values use
the neutral center zone and click-approach behavior. Missing `time_norm` uses the
minimum-jerk timing curve required by the converted default data.

## Matcher

For each requested move, GenericClient scores templates by log-scaled distance
and duration, angular distance, start/target screen zones, path quality, and
whether the source movement approached a click. It samples from the best twelve
templates whose transformed paths remain inside the canvas, smooths the chosen
shape, limits excessive backtracking, transforms it to the requested endpoints,
and schedules 128 real cursor positions using the template timing.
