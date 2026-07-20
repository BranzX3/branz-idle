# Code and Gameplay Audit — 2026-07-20

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

- The Chronicle contains only the initial achievement subset; full Mastery,
  Discovery, Worker, Territory, Expedition, Social and Feat tracks remain.
- Dynamic commissions still use a small fixed template set.
- World-construction project stages are not represented in the world.
- Seasonal Chronicle, analytics alerts and experiment controls are partial.
- Frontier profession/crafting/equipment/repair sinks are intentionally gated
  off.
- Public-beta evidence still needs 10 new-player playtests and five simulated
  30-day cohorts.

## Follow-up engineering priorities

1. Split commissions, achievements, projects and seasons into typed services
   over `GameStateStore`.
2. Move the remaining convert/unclaim refund and project/commission resource
   settlements into cross-aggregate transactions.
3. Add durable trade escrow/recovery for hard process termination.
4. Add restart/fault-injection tests for claim, commission, project and fuse
   settlement.
5. Replace late service setters with a composition root and constructor
   dependencies.
6. Add MySQL integration tests in CI.
