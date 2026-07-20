# IdleFarm Game Design Package

This directory is the design authority for the IdleFarm player experience.
When implementation and an older product spec disagree with these documents,
the discrepancy must be resolved explicitly before coding.

## Design pillars

1. Every Production Node owns an independent Exploration Level.
2. Lv.1–100 expands a vanilla-resource pool.
3. Lv.101+ adds MMORPG materials without removing vanilla output.
4. A focused free player reaches Lv.100 on one primary node in 28–32 days.
5. Tier controls quantity; Exploration Level controls discovery and quality.
6. Progress is never wiped by a season.
7. Paid Credits provide cosmetics and bounded flexibility, never EXP, RNG,
   tradable power, or a higher power ceiling.

## Documents

| Document | Authority |
|---|---|
| `GAME_DESIGN_LV1_100.md` | Node progression, achievements, reward loops |
| `BALANCE_BIBLE.md` | EXP, production, buffers, Coins, Credits, caps |
| `CONTENT_MATRIX.md` | Node pools, events, commissions, achievements |
| `WORKERS_AND_SPECIALIZATIONS.md` | Worker roles, team strategy, node builds |
| `NODE_TYPE_PERKS.md` | Unique Mining/Farming/Wood/Livestock/Hunter perk choices |
| `RESOURCE_ECONOMY.md` | Resource sinks, projects, orders, trading |
| `UX_FLOWS.md` | Complete Player and Admin journeys |
| `UI_FLOW_AND_CHAT_ACTIONS.md` | Surface routing and clickable chat action spec |
| `LIVE_OPS_AND_SEASONS.md` | Rotations, post-100 releases, telemetry |
| `IMPLEMENTATION_ROADMAP.md` | Delivery phases and acceptance criteria |
| `IdleFarm_Balance_Bible.xlsx` | Tunable source numbers and simulations |

## Locked baseline assumptions

- One focused node reaches Lv.100 per month.
- Lv.1→100 requires 71,280 Exploration EXP.
- Typical focused-node progress averages about 2,350 EXP/day including weekly
  rewards.
- Tier-1 buffer target is 256 items, scaling linearly with Tier.
- A normal player should check a productive node two to three times per day,
  not every hour.
- Rare vanilla items use hard daily/weekly caps in addition to low weights.
- Lv.100 is a ceremony and content threshold, not a reset.
- Credits are integer, non-transferable, non-cashable server entitlements.

## Change-control rule

Any change to one of these values requires checking:

- Time to Lv.100.
- Coin source/sink ratio.
- Daily resource output.
- Warehouse pressure.
- Achievement reward budget.
- Free versus paid completion time.
- Post-100 material supply versus its MMORPG sinks.
