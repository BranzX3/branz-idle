# Idle Code Architecture

This document defines the intended runtime boundaries and invariants. The game
design documents remain the authority for player-facing behavior.

## Runtime layers

| Layer | Packages | Responsibility |
|---|---|---|
| Domain records | `node`, `worker` | Runtime state and value types |
| Persistence | `storage` | Database schema, ordered writes, indexes and scoped state |
| Gameplay | `service` | Validate actions, apply game rules and coordinate durable settlement |
| Game design | `service.design` | Typed services over `GameStateStore`: focus, commissions, Chronicle, discoveries, node builds, projects, seasons, telemetry |
| Delivery | `command`, `gui`, `listener` | Authenticate the actor, collect input and render results |
| Scheduling | `task`, scheduled services | Production, payout, exploration and weekly lifecycle |

Delivery code must not mutate durable maps or records without calling a
gameplay service. A service must re-check ownership and state even when the GUI
already filtered the action (`WorkerService.eject` and
`GlobalExpeditionService.commit` re-verify node ownership for exactly this
reason).

`GameDesignService` is a facade: delivery code keeps one entry point while
each concern lives in its own `service.design` class sharing the scoped
`GameStateStore`. The facade routes gameplay events (claim, collect, produce,
expedition) to the interested services and owns the two cross-cutting reward
sinks (node EXP via ExplorationService, Coins via PlayerDataStore). New
design-package features get a new typed service, not a new branch in the
facade.

## Composition root

`IdlePlugin.onEnable` is the composition root: services are constructed
in dependency order and receive collaborators through constructors. Exactly
two late binds are allowed, both cycles by design:

- `ExplorationService.setGameDesignService` — events grant design rewards
  while the design service grants exploration EXP.
- `GuiManager.setAdminTools` — AdminCommands opens admin menus through the
  GuiManager that needs AdminCommands.

Do not add new `set*` late-injection methods; extend the constructor and the
composition root instead.

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

For cross-aggregate settlement, `GameStateStore.stage`/`Row` and
`WarehouseService.prepareWithdraw`/`snapshot` mutate the runtime cache and
hand the caller immutable rows to persist inside one transaction
(`GameStateStore.write`, `WarehouseService.write`). Blocking flows build
`Row`s without touching the cache and call `applyCommitted` after success.

Cross-row gameplay invariants:

- Exploration Warehouse deposit and event deletion commit together.
- Credit checkout ledger, Credit wallet and Coin balance commit together.
- Global Expedition rewards and score purge commit together.
- Daily streak state and its Coin reward commit together.
- Supply commission, project and Server Project contributions and expedition
  preparation commit their Warehouse withdrawal with the state they buy.
- A paid node respec commits the Coin charge with the new build.
- Node convert commits its Coin cost with the type change; unclaim commits
  the node delete with its refund.
- A fuse consumes both materials and mints the result in one transaction.
- Each offered trade stack is journaled in `idle_trade_escrow` before it
  leaves the player's inventory. Settlement commits the receipt and changes
  every escrow recipient in one transaction. Unfinished `OPEN` rows become
  owner refunds on startup; `PENDING_DELIVERY` rows replay on login.

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

Daily commissions are authored in `commissions.yml`. `CommissionService`
selects three eligible templates from the Focused Node level with a stable
owner/day seed; server restarts must not change an active board. One free
reroll is tracked per Bangkok game day.

Chronicle definitions are authored in `achievements.yml`. Threshold
requirements reference account counters fed by gameplay hooks; adding a
threshold achievement must not require another claim-time code branch.

World-construction visuals are authored in `project-stages.yml`.
`ProjectWorldService` renders 25/50/75/100% stages as tagged persistent Block
Displays, so visuals never overwrite terrain. Personal structures anchor to
the owner's oldest Residential Node; the Server Project monument anchors near
world spawn. Chunk-load reconciliation restores the authoritative stage after
a restart and removes stale displays before replacing them.

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
