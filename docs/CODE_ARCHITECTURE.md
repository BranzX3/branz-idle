# IdleFarm Code Architecture

This document defines the intended runtime boundaries and invariants. The game
design documents remain the authority for player-facing behavior.

## Runtime layers

| Layer | Packages | Responsibility |
|---|---|---|
| Domain records | `node`, `worker` | Runtime state and value types |
| Persistence | `storage` | Database schema, ordered writes, indexes and scoped state |
| Gameplay | `service` | Validate actions, apply game rules and coordinate durable settlement |
| Delivery | `command`, `gui`, `listener` | Authenticate the actor, collect input and render results |
| Scheduling | `task`, scheduled services | Production, payout, exploration and weekly lifecycle |

Delivery code must not mutate durable maps or records without calling a
gameplay service. A service must re-check ownership and state even when the GUI
already filtered the action.

## Persistence model

`Database` owns one ordered writer. Runtime caches are authoritative while the
server is running, and queued writes must capture immutable snapshots.

Use:

- `submitWrite` for one independent row.
- `submitTransaction` for an asynchronous action spanning multiple rows.
- `executeTransaction` only when an entitlement must not be shown until its
  commit succeeds, such as checkout and weekly settlement.

Never mix a direct connection write with related queued writes. Never call
`executeTransaction` from the database writer thread.

Cross-row gameplay invariants:

- Exploration Warehouse deposit and event deletion commit together.
- Credit checkout ledger, Credit wallet and Coin balance commit together.
- Global Expedition rewards and score purge commit together.
- Daily streak state and its Coin reward commit together.

Player balance snapshots carry a revision. An older asynchronous save cannot
mark or overwrite a newer in-memory mutation as clean.

## Gameplay invariants

### Nodes and production

- Tier changes quantity and worker slots.
- Exploration Level changes content brackets.
- An unstaffed node advances its time anchor but produces nothing.
- A full buffer halts production while passive research continues at the
  configured reduced rate.
- Fractional production time and fractional research time survive scheduler
  ticks.

### Warehouse and rewards

- Callers receive an immutable Warehouse view.
- A bundle deposit is all-or-nothing.
- A full Warehouse leaves completed Exploration loot claimable.
- Rare caps are account-wide and apply to passive and event output.

### Workers

- A Worker UUID has one authoritative database record.
- Bag ownership is checked inside gameplay services.
- Assigned, committed, Starter and favorite workers cannot be consumed by
  disallowed flows.
- The same Worker UUID cannot occupy both Fuse inputs.
- EXP below a level-up is persisted.

### Time

Daily and weekly gameplay boundaries use `Asia/Bangkok`. System-default time
must not decide commissions, caps, streaks, seasons or expedition settlement.

## Extensibility

Flexible commission, achievement and season progress belongs in
`GameStateStore`, keyed by owner, scope, scope ID and state name. Add a typed
service or definition catalog over it; do not add unrelated branches to
`GameDesignService`.

Balance values belong in config-backed catalogs such as
`ProgressionRewards`. UI text must read the same catalog used by settlement.

Content changes must pass `DropTableService.validate`. Published tables cannot
be empty and runtime fallback is only a final corruption guard, not a valid
content configuration.

## Test gates

Before deployment:

1. Run `gradlew clean test shadowJar`.
2. Run a local Paper smoke test and confirm plugin enable/disable without
   persistence errors.
3. For schema or settlement changes, test SQLite and the production MySQL
   configuration.
4. For balance changes, run the 30-day progression cohort simulation and
   compare the result with the Balance Bible.
