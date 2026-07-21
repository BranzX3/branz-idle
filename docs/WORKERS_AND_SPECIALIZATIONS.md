# Workers, Teams, and Node Specializations

## 1. Design goal

The best worker must depend on the job. A single highest-total-stat worker
should not dominate production, research, every expedition, and every node.

## 2. Worker roles

Roles are derived from stats; they are not separate classes rolled at hire.

| Role | Primary stat | Best use | Weakness |
|---|---|---|---|
| Producer | Diligence | Passive quantity, Resource Run | Low discovery utility |
| Scout | Luck | Rare Discovery, rare grade | Lower throughput |
| Researcher | Stamina | Node/Worker EXP, Endurance | Lower immediate loot |
| Runner | Speed | Rescue, short expedition cycles | Lower quantity |
| Leader | Balanced/high level | Team synergy | Never best at one stat |

Role badge:

- Highest stat determines base role.
- Balanced trait with close stats becomes Leader.
- Role can change naturally as the worker levels.
- Role is descriptive, not a permanent lock.

## 3. Clear stat effects

| Stat | Passive production | Research | Regular event |
|---|---|---|---|
| Diligence | Bulk-lane rate multiplier, up to +200% (Efficiency-like) | None | Loot quantity |
| Luck | Rare roll shift after caps (discovery lane only) | None | Great/Jackpot odds |
| Stamina | Worker EXP | Research power | Node/Worker EXP |
| Speed | None | None | Duration reduction |

Display marginal impact:

- `+126 items/hour`
- `+12 research/day`
- `-3m 20s duration`
- `+2.1% Great, +0.7% Jackpot`

Players should not need to reverse-engineer formulas.

## 4. Team synergy

Synergy rewards composition without requiring one exact meta.

| Synergy | Requirement | Effect |
|---|---|---|
| Specialists | Three different roles | +10% event TeamScore |
| Survey Corps | Researcher + Runner | +15% Node EXP |
| Treasure Crew | Scout + Producer | +10% loot rolls |
| Veteran Lead | Leader + two lower-level workers | +15% lower-worker EXP |
| Full House | All four core roles | Choose one small event bonus |

Synergies do not stack with themselves. UI previews the active synergy before
commitment.

## 5. Node specialization tree

Each Node chooses one branch at Lv.25, one refinement at Lv.50, and one Mastery
perk at Lv.75.

### Lv.25 branches

| Branch | Effect | Intended player |
|---|---|---|
| Industry | +15% common/advanced quantity | Builders and suppliers |
| Discovery | +research and rare-event frequency | Progression/collection |
| Logistics | +50% buffer, stronger full-buffer research | Infrequent check-ins |

### Lv.50 refinements

Industry:

- Mass Production: another +10% common quantity.
- Fine Processing: advanced resources gain weight instead.

Discovery:

- Deep Survey: +15% regular-event Node EXP.
- Lucky Route: one extra rare-roll attempt per completed event.

Logistics:

- Deep Storage: another +50% buffer.
- Smart Routing: auto-route selected common resource to projects.

### Lv.75 Mastery perks

- Industry — Foreman: first 200 items/day gain +10% quantity.
- Discovery — Pathfinder: first event/day offers two event choices.
- Logistics — Quartermaster: full buffer retains 50% rather than 25% research.

No specialization raises rare-item hard caps.

## 6. Respec

- Free preview before confirming.
- First respec per node is free within 24 hours.
- Later respec costs Coins based on Node Level.
- Seven-day cooldown after confirmation.
- Credits may offset at most 15% through Hybrid Pay.
- Respec never removes Exploration EXP, pool discoveries, or achievements.

## 7. Worker growth

Keep rarity level caps and trait-weighted random allocation, but reduce regret:

- Show projected role after a level-up.
- Favorite/lock prevents fuse, trade, or accidental material selection.
- Assigned and exploring workers never appear as fuse materials.
- Worker Detail shows lifetime nodes, events, and achievements.

## 8. Fuse redesign

Current “two workers, failure destroys both” creates strong loss aversion.

Recommended:

- Two same-rarity workers + earned Fuse Catalyst.
- Failure consumes the catalyst and one selected duplicate; the protected base
  worker survives.
- Pity increases as currently designed.
- Success creates the next rarity at Lv.1.
- Return 25% of consumed worker lifetime EXP as `Training Notes`.
- On success choose one inheritance:
  - Skin
  - Name
  - Trait family
- Exact outcome odds and hard-pity distance are always visible.

Credits cannot buy hire rolls, Fuse Catalysts, pity, or extra inheritance.

## 9. Worker equipment

Use one non-tradeable `Charm` slot unlocked through gameplay at Node Lv.50.

Charm effects are situational:

- Survey Compass: Survey Node EXP.
- Heavy Gloves: Resource Run quantity.
- Lucky Token: small Great chance, no Jackpot increase.
- Trail Boots: Rescue speed.

Charms are crafted from project/achievement rewards and cannot be traded. This
creates builds without adding another speculative market.

## 10. Starter Worker

- Account-bound.
- Cannot trade, drop, fuse, or use as material.
- Levels normally.
- Has Balanced trait and average fixed stats.
- Gains a unique cosmetic at the first focused Node Lv.30.
- Keeping it assigned through Lv.100 awards a hidden Feat, not extra power.

## 11. Global Expedition interaction

Global and regular commitments use separate locks:

- Global lock removes the worker from passive production.
- Regular event may still use the same worker as a separate abstract activity
  if the product vision requires the systems to coexist.
- UI must clearly label this rule as a server-wide support assignment rather
  than physical travel, avoiding a narrative contradiction.

Alternative stricter rule:

- A worker can join only one activity.
- The node may run both events using different workers.

The stricter rule is more intuitive and is recommended once Tier 2+ crews are
common; starter Tier-1 nodes should not receive overlapping commitments.

