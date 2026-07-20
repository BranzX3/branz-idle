# Code and Gameplay Audit — 2026-07-20

## Second pass (same day): structure and settlement refactor

- `GameDesignService` was split into typed services under `service.design`
  (focus, worker metadata, commissions, Chronicle, discoveries, node builds,
  projects, seasons, telemetry) behind an unchanged facade API.
- `IdleFarmPlugin.onEnable` became a composition root; every removable
  `set*` late-injection method was replaced with constructor dependencies.
  The two remaining late binds are documented cycles.
- Warehouse-consuming settlements (supply commission, project and Server
  Project contributions, expedition preparation) now commit the Warehouse
  row and the purchased state in one transaction via staged
  `GameStateStore.Row`s and `WarehouseService.prepareWithdraw`/`write`.
- Paid respec, node convert cost, unclaim refund, fuse consumption/minting
  and the trade receipt are now transactional (audit priority 2 and part of
  3/4 closed).
- `WorkerService.eject` and `GlobalExpeditionService.commit` now re-check
  node ownership inside the service, closing two authorization gaps.
- Offline exploration-event expiry no longer inflates: the remaining window
  is frozen by the elapsed tick instead of re-derived from the spawn time.
- Integration tests added for unclaim-refund settlement and restart-safe
  fuse settlement.
- Chronicle now includes Journey, per-node Mastery, Discovery, Worker,
  Territory, Expedition, Social and hidden Feat tracks backed by gameplay
  counters.
- Daily commissions now use a config-driven catalog, stable Focused
  Node/level-aware selection and one free reroll per Bangkok day.
- Personal and Server Projects now render non-destructive physical stages at
  25%, 50%, 75% and completion, with restart/chunk-load reconciliation.

## Corrected in this audit

- Exploration loot and Warehouse persistence now share one transaction.
- Queued Node and Worker writes capture immutable values.
- Zero-Credit Hybrid Pay replays can no longer charge Coins twice.
- Credit checkout now commits ledger, Credits and Coins together.
- Weekly Global Expedition settlement is idempotent and catches up after
  server downtime.
- Daily streak state and reward commit together.
- Claim, tier upgrade, Worker hire/Bag expansion, Warehouse expansion,
  Booster purchase and Perk purchase now commit Coin cost with entitlement.
- Player data is saved every payout interval with revision-safe dirty state.
- Full-buffer research retains fractional progress.
- Rare caps apply to regular Exploration loot as well as passive production.
- Weekly Chapter progress counts distinct active days.
- Worker Bag assignment works from Node Detail.
- Node Detail no longer grants an action surface for another owner's node.
- Fuse rejects duplicate UUID inputs and foreign Bag records.
- Drop-table validation no longer hides empty tables behind a fallback.
- Player command numeric parsing and malformed Worker UUIDs fail safely.
- Gameplay state persistence was extracted into `GameStateStore`.
- Progression rewards were moved into one config-backed catalog.

## Remaining design-package gaps

These are product/content work, not regressions fixed by this refactor:

- Seasonal Chronicle, analytics alerts and experiment controls are partial.
- Frontier profession/crafting/equipment/repair sinks are intentionally gated
  off.
- Public-beta evidence still needs 10 new-player playtests and five simulated
  30-day cohorts.

## Follow-up engineering priorities

1. ~~Split commissions, achievements, projects and seasons into typed
   services over `GameStateStore`.~~ Done (second pass).
2. ~~Move the remaining convert/unclaim refund and project/commission
   resource settlements into cross-aggregate transactions.~~ Done (second
   pass).
3. Add durable trade escrow/recovery for hard process termination (the
   receipt is transactional now, but escrow items held in memory are still
   lost on a hard kill mid-trade).
4. Add further restart/fault-injection tests for claim, commission and
   project settlement (fuse and unclaim now covered).
5. ~~Replace late service setters with a composition root and constructor
   dependencies.~~ Done (second pass); two documented cycles remain.
6. Add MySQL integration tests in CI.
