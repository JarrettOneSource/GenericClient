# Automation scheduling

GenericClient can select one registered Lua script from declarative time and
account-state rules. The scheduler sits above the Lua host:

```text
named schedule windows -> three-valued rule engine -> script lease -> Lua host
```

Schedules answer **when** a rule may operate. Conditions answer **whether** the
account currently needs that work. The scheduler chooses **which** matching rule
owns GenericClient's single standalone-script slot.

## Files and account binding

Rules and runtime state are isolated by the same derived 16-character account
profile ID used by the behavior system. Raw account hashes are not written.

```text
~/.runelite/genericclient/automation/rules-<profile>.json
~/.runelite/genericclient/automation/state-<profile>.json
```

The Schedules dashboard page and `automation_status` report the active profile,
configuration path, current mode, next wall-clock transition, rule decisions,
and active run owner. A missing rules file is a valid disabled configuration.

## Complete example

```json
{
  "schema": "genericclient_automation.v1",
  "zone": "America/New_York",
  "enabled": true,
  "schedules": {
    "work-hours": {
      "days": [
        "MONDAY",
        "TUESDAY",
        "WEDNESDAY",
        "THURSDAY",
        "FRIDAY"
      ],
      "windows": [
        {
          "from": "08:00",
          "until": "17:00"
        }
      ]
    }
  },
  "rules": [
    {
      "id": "restore-cash",
      "priority": 100,
      "when": {
        "all": [
          { "schedule": "work-hours" },
          { "fact": "cash.known_total_value", "lt": 5000000 }
        ]
      },
      "run": {
        "script": "money-maker",
        "inputs": {
          "method": "auto"
        }
      },
      "retry_after": "PT10M"
    },
    {
      "id": "train-strength",
      "priority": 50,
      "when": {
        "all": [
          { "schedule": "work-hours" },
          { "fact": "skills.strength.level", "lt": 30 }
        ]
      },
      "run": {
        "script": "aio-melee",
        "inputs": {
          "skill": "strength",
          "target_level": "30",
          "method": "auto"
        }
      },
      "retry_after": "PT10M"
    }
  ]
}
```

`money-maker` is an example script ID; it must exist in `scripts/manifest.json`
and declare the supplied inputs before this configuration is accepted. Rule
configuration is rejected atomically if a script or input value is invalid.

Use separate rules when different conditions require different scripts. Use an
`any` condition only when several conditions should select the same script:

```json
{
  "all": [
    { "schedule": "work-hours" },
    {
      "any": [
        { "fact": "cash.known_total_value", "lt": 5000000 },
        { "fact": "skills.magic.level", "lt": 30 }
      ]
    }
  ]
}
```

## Schedule semantics

- `zone` is a required IANA time zone. Java zone rules handle daylight-saving
  transitions.
- Days are full `DayOfWeek` names.
- Times use 24-hour `HH:mm`.
- A window includes `from` and excludes `until`.
- If `until` is earlier than `from`, the window crosses midnight. The listed
  day is the day on which that window starts.
- Multiple windows may overlap; the scheduler merges their active interval and
  reports the next real change in membership.
- The scheduler arms a wall-clock transition in addition to evaluating game
  ticks, so a window can close while the client is logged out.

This is an eligibility-window model, not cron. `08:00`-`17:00` means a rule may
remain eligible throughout the interval; it does not mean “fire once at 08:00.”

## Conditions and facts

Conditions support `all`, `any`, and `not`. A fact condition has exactly one of
`eq`, `ne`, `lt`, `lte`, `gt`, or `gte`.

Supported skill facts are:

```text
skills.total_level
skills.<skill>.level
skills.<skill>.boosted_level
skills.<skill>.xp
```

Supported cash facts are:

```text
cash.bank_known
cash.inventory_coins
cash.inventory_platinum_tokens
cash.inventory_value
cash.bank_coins
cash.bank_platinum_tokens
cash.bank_value
cash.known_total_value
cash.complete
```

The rule engine returns `true`, `false`, or `unknown`:

- an idle rule starts only on `true`;
- `false` makes it ineligible;
- `unknown` blocks a new start and reports the missing fact;
- a running rule keeps its lease through temporary `unknown` snapshots, such as
  logout, but stops when its condition is definitely `false`.

Bank-dependent cash facts are `unknown` until the bank cache has been observed.
Partial inventory wealth is never compared as total account wealth. A cold
login can use a separate high-priority reconciliation script that opens a bank
before cash-dependent work is eligible.

## Selection and lifecycle

- The highest numeric priority wins when the Lua host is idle. Source order
  breaks equal-priority ties.
- The winning rule receives a sticky `rule:<id>` script lease.
- A running scheduled rule is not preempted merely because a higher-priority
  rule later becomes true.
- Manual scripts always win. Scheduled starts are idle-only and cannot replace
  them.
- A manual Stop persists a scheduler pause so the same rule cannot restart on
  the next tick. Resume explicitly from Schedules or MCP.
- Disabling or pausing stops only a rule-owned script.
- When a schedule closes or a rule becomes false, GenericClient cancels the
  rule-owned script and its active semantic action. Scripts remain responsible
  for resumable phases and their normal action-level safety checks.
- A terminal run records `retry_after` before that rule can start again. The
  cooldown survives client restart. Durations use ISO-8601 syntax and must be
  between one second and 30 days.

Rules are eligibility policy, not action authorization. Exact XP caps, cash
reserves, item restrictions, and encounter safety remain enforced inside the
selected Lua script.

## Control surfaces

The loopback control server provides:

```text
automation.status
automation.config.get
automation.config.set
automation.enable
automation.pause
automation.resume
automation.reload
```

The MCP server exposes matching `automation_*` tools. `automation_config_set`
validates the complete replacement before writing it atomically.

## Process lifetime

The scheduler can manage login-independent wall-clock windows only while the
GenericClient process is running. Starting RuneLite when it is completely
closed is a separate operating-system responsibility, such as Windows Task
Scheduler. That supervisor can launch GenericClient; the in-client rules remain
the sole authority for script selection.
