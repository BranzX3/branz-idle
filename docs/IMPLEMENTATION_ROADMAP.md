# Game Design Implementation Roadmap

This roadmap converts the design package into safe development increments.

## Phase 0 — Data safety foundation

Status: partially implemented.

- Persist Worker EXP below level-up.
- Atomic Exploration loot→Warehouse claim.
- Separate Global Expedition locks.
- Add transaction/audit coverage.
- Add automated service tests.

Acceptance:

- Restart cannot lose progress.
- Full Warehouse cannot lose event loot.
- Duplicate callbacks/actions do not duplicate rewards.

## Phase 1 — Node progression foundation

- Change Exploration EXP curve.
- Add daily passive research cap.
- Add full-buffer 25% research.
- Add Rested Research.
- Add Focused Node selection and cooldown.
- Increase buffer baseline to 256/Tier.
- Add rare-resource caps.

Acceptance:

- Simulation and staging test reach Lv.100 in 28–32 modeled days.
- Secondary nodes progress without receiving focused rewards.
- Tier and Level remain independent.

## Phase 2 — First ten minutes

- Starter Chapter.
- Free Residential and first Production Node.
- Bound Starter Worker.
- Scripted first event.
- Recommended-action Hub state.
- Residential Detail separation.

Acceptance:

- New tester reaches production under 7 minutes.
- First collection under 10 minutes.
- No command knowledge required.

## Phase 3 — Content pools and journal

- Data-driven 10 brackets for five node types.
- Effective table validation.
- Discovery Journal.
- Next unlock previews.
- Rare caps and reroll fallback.
- Lv.100 Capstone flags/façades.

Acceptance:

- Every pool item has source, sink, unlock, cap, and journal entry.
- Invalid/empty tables fail validation before publish.

## Phase 4 — Commissions and Chronicle

- Domain gameplay event bus.
- Achievement definitions/progress/reward settlement.
- Chronicle GUI.
- Dynamic daily commissions.
- Weekly Node Chapters.
- Catch-up.

Acceptance:

- Rewards are idempotent.
- No Credit or RNG-only achievements.
- Achievement budget stays within Balance Bible.

## Phase 5 — Workers and specialization

- Role badges and marginal stat display.
- Team Picker and synergies.
- Node specialization tree.
- Type-specific Perks at Lv.15/35/60/85.
- Respec.
- Favorite/lock.
- Safer Fuse and EXP return.
- Charm slot.

Acceptance:

- At least three viable team compositions per event family.
- Favorite/assigned/committed workers cannot be consumed.

## Phase 6 — Resource sinks and projects

- Personal Settlement Projects.
- Server Projects.
- Preparation kits.
- World construction stages.
- Protected direct trade.

Acceptance:

- Every produced resource has at least two current destinations.
- Trade is atomic, audited, and resets confirmation after changes.

## Phase 7 — Complete UX/Admin

- Rebuilt Main Hub and Node Detail.
- Pool/Journal/Commission/Chronicle/Project flows.
- Credit checkout and purchase history.
- Admin Hub, player/node inspector.
- Draft/validate/publish/rollback content editor.
- Economy dashboard.

Acceptance:

- Next useful action is within two clicks.
- Every destructive/premium action previews and confirms.
- Admin mutations require reason and audit ID.

## Phase 8 — Live Ops

- Twelve-week season framework.
- Event modifiers.
- Seasonal Chronicle.
- Global Expedition participation bands.
- Analytics dashboard and alerts.

Acceptance:

- Seasons add content without resetting permanent progress.
- Missing a week remains recoverable.

## Phase 9 — MMORPG Frontier

- Profession/crafting sinks.
- Frontier material tables Lv.101+.
- Supply caps and audit.
- Feature flag and staged rollout.

Implemented locally. Operator controls and the full material/sink table are in
`FRONTIER_PHASE9.md`.

Acceptance:

- Every enabled material has multiple durable sinks.
- Rollback can stop future drops without deleting owned items.

## Release gates

Before public beta:

- Phases 0–4.
- Direct economy telemetry.
- At least 10 new-player playtests.
- At least 5 simulated 30-day cohorts.

Before monetization:

- Purchase ledger/history/refund process.
- Hybrid Pay caps.
- Legal/accounting/payment-provider review.
- Published price catalog and support contact.

Before MMORPG materials:

- Phase 9 acceptance in full.
