# AIO Prayer

`aio-prayer` trains Prayer to a declared level from live XP and stops as soon as
that target is reached. The first method is deliberately self-contained:
purchase the exact dragon-bone deficit, collect it directly into the bank, then
bury verified 27-bone inventories beside the Grand Exchange bank. It does not
depend on a house-party host or a Wilderness route.

## Modules

- `aio-prayer.lua`: dashboard inputs and stop-after-bone action.
- `aio-prayer/config.lua`: target XP, bone facts, price ceiling, and reserve.
- `aio-prayer/preparation.lua`: ring travel, bank audit, and JIT GE purchase.
- `aio-prayer/training.lua`: bank trips, individual Bury actions, and XP checks.
- `aio-prayer/progress.lua`: level, gained XP, XP/hour, and ETA overlay rows.
- `aio-prayer/runner.lua`: target calculation and phase ownership.

The initial targets are 43, 70, and the account hard cap of 77. Every bone is a
normal eligible skilling interaction, so the behavior profile remains active.
Banking and trading retain their framework-level no-break policy.

## Large GE collection

`ge.buy` accepts an optional `collect_mode` value:

```lua
gc.await {
  action = {
    type = "ge.buy",
    item_id = 536,
    item_name = "Dragon bones",
    quantity = 700,
    maximum_unit_price = 5000,
    minimum_cash_reserve = 5000000,
    collect_mode = "bank",
  },
}
```

The modes are `items` (default), `notes`, and `bank`. A resumed matching offer
can finish collection without passing a new-spend cash preflight. Bank mode
accepts disappearance of the requested item from the collection panel, then
collects any remaining coin refund before requiring the offer slot to clear.

## Live evidence

On 2026-08-30 `genericBoss` bought exactly 700 dragon bones for 2,288,656 coins.
The `Bank` collection action placed all 700 in the bank without consuming an
inventory slot. A resumed matching offer then recognized the banked bones,
collected the remaining 1,211,344-coin refund, and cleared its offer slot.

The trainer buried all 700 bones, gained exactly 50,400 Prayer XP, and stopped
at level 43 with a terminal `complete` receipt. Normal per-bone behavior rolls
remained active. A 22-minute profile-selected long break logged out and later
resumed the same run successfully.
