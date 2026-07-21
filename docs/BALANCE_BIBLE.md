# IdleFarm Balance Bible

## 1. Balance goals

- First useful production in under 7 minutes.
- First collection in under 10 minutes.
- Focused Node Lv.10 on day 2.
- Lv.25 on day 7.
- Lv.50 on day 14.
- Lv.75 on day 22.
- Lv.100 on day 28–32.
- Missing two days should delay progress, not break the month target.
- Paying never purchases Exploration EXP.

## 2. Exploration Level

Use:

`EXP_next(L) = 120 + 12L`

where `L` is the current level. The sum from Lv.1 through the Lv.100 unlock is
71,280 EXP.

### Expected focused-node budget

| Source | Typical value | Cap |
|---|---:|---:|
| Passive worker research | 650/day | 900/day |
| First collection | 100/day | Once per day |
| Focused commission | 400/day | Once per day |
| First expedition | 700/day | Once per day |
| Additional expeditions | 100 each | 300/day |
| Weekly Node Chapter | 3,500/week | Once per week |
| Tutorial | 2,000 total | Once |

Typical month:

`19,500 + 3,000 + 12,000 + 21,000 + 14,000 + 2,000 = 71,500 EXP`

### Passive research

`Research/hour = 75 × CountBonus × AverageResearchPower`

`CountBonus = 1 + 0.15 × (workers - 1)`

`WorkerResearchPower = rarity_factor × (1 + stamina/100 + level × 0.005)`

Rarity factors:

| Rarity | Research factor |
|---|---:|
| Common | 1.00 |
| Uncommon | 1.05 |
| Rare | 1.10 |
| Epic | 1.15 |
| Legendary | 1.20 |

Rules:

- Producing node: 100% research.
- Buffer full: 25% research.
- No worker: 0%.
- Worker on regular exploration: excluded from passive research.
- Worker on Global Expedition: excluded from production but may still join a
  regular exploration because the systems have separate locks.
- Daily passive cap: 900 EXP per node.
- Rested research: up to 48 hours below Lv.100.

## 3. Production

Production runs in two independent lanes per node.

### Bulk lane: family commons

The bulk lane models a worker gathering the family's basic resources the way
a real player would (stone, cobblestone, logs, crops, basic drops). It is
deterministic — no rolls — and it is the lane that makes a Lv.100 territory
feel like "I never mine stone by hand again."

Rates are anchored to active vanilla gathering with mid-tier tools:

| Node | Active player anchor/hour | Bulk base/hour |
|---|---:|---:|
| Mining | ~1,200 | 70 |
| Farming | ~800 | 45 |
| Woodcutting | ~600 | 35 |
| Livestock | ~300 | 18 |
| Hunter | ~250 | 15 |

Per worker:

`bulk/hour = family_base × rarity_mult × (1 + level × 0.02) × (1 + 2 × diligence/100)`

The rarity multiplier mirrors vanilla tool dig speed
(wood/stone/iron/diamond/netherite = 2/4/6/8/9, normalized to wood = 1) so a
worker upgrade reads like a tool upgrade:

| Rarity | Tool analogy | rarity_mult |
|---|---|---:|
| Common | Wooden | 1.0 |
| Uncommon | Stone | 2.0 |
| Rare | Iron | 3.0 |
| Epic | Diamond | 4.0 |
| Legendary | Netherite | 4.5 |

Node bulk output is the sum of assigned workers. Tier still adds worker
slots rather than a direct production multiplier.

Reference points (Mining):

| Crew stage | Multiplier | Bulk/hour | Versus active player |
|---|---:|---:|---:|
| Day-1: Common Lv.5, Dil.10 | 1.3× | ~95 | 8% |
| Midgame: Rare Lv.30, Dil.40 | 8.6× | ~605 | 50% |
| Max: Legendary Lv.100, Dil.100 | 40.5× | ~2,835 | 236% |

Design intent: a midgame crew replaces about half of hand-gathering per
worker; only a fully built worker out-gathers an active player. Idle
abundance is earned, not default.

Sinks that consume commons must be authored relative to expected daily
production (the 20% commission rule in `RESOURCE_ECONOMY.md`), never as
fixed counts tuned to a historical rate.

### Discovery lane: advanced, rare, and MMORPG

Everything that is not a family common — advanced resources, capped rares,
and post-100 MMORPG materials — stays on the roll table. Discovery
rolls/hour are tuned independently of the bulk lane and must be set so that
expected advanced items/day is unchanged by the bulk-lane introduction. Rare
caps in section 4 are unchanged and remain the hard guarantee.

Stat identity across lanes:

- Diligence multiplies the bulk lane only (Efficiency-like, up to +200%).
- Luck affects discovery rolls only, after caps, as already specified.
- Stamina and Speed are unchanged.

Worker discovery power remains:

`rarity_power × (1 + level × 0.02) × (1 + diligence/100)`

### Buffer

Each node has two buffers:

- Discovery buffer: `256 × Tier` items, unchanged.
- Bulk buffer: `hours × current node bulk rate`, where hours is 8 at Tier 1
  and declines by one per Tier to 4 at Tier 5. Capacity is recomputed when
  the crew changes, so fill time stays constant as rates grow.

Target fill time remains the governing constant:

- Early common crew: 5–8 hours.
- Midgame optimized crew: 3–6 hours.
- Endgame crew: 2–4 hours unless Storage specialization is selected.

Logistics specialization percentages apply to both buffers. A full buffer
stops only its own lane; research drops to 25% when both lanes are stopped.

### Warehouse storage of commons

Bulk-lane commons are stored as counts and materialized into item stacks
only on withdrawal. Endgame territories hold six-figure common stocks;
physical item stacks must never be the storage representation.

## 4. Rare-resource caps

Weights alone do not safely control valuable vanilla items because high-tier
nodes produce thousands of rolls.

Suggested per-node caps:

| Item | Unlock | Cap |
|---|---:|---:|
| Diamond | 50 | 8/day; 16/day after Lv.80 |
| Emerald | 60 | 12/day |
| Nautilus Shell | 55 | 4/day |
| Ghast Tear | 55 | 4/day |
| Ancient Debris | 90 | 2/week |
| Netherite Scrap | 95 | 2/week shared with Ancient Debris |
| Wither Skeleton Skull | 90 | 1/week, active event only |

When a cap is reached, reroll into the table's common/advanced pool. Never
display an item and silently delete it.

Never produce:

- Elytra
- Nether Star
- Dragon Egg
- Heavy Core
- Enchanted Golden Apple
- Server-unique trophies

## 5. Coin economy

Coins reward time and bounded participation. Resources never have an unlimited
Coin sell price.

### Expected free income: month one

| Source | Monthly Coins |
|---|---:|
| Online payout, 60 hours | 36,000 |
| Daily behavior commission | 18,000 |
| Weekly chapters | 8,000 |
| Journey/Mastery achievements | 10,000 |
| Expected Global Expedition reward | 2,000 |
| Total | 74,000 |

The existing payout of 10 Coins/minute equals 600/hour.

### Expected month-one sinks

| Sink | Expected spend |
|---|---:|
| Production claims after the free starter | 4,000 |
| Primary node Tier 1→5 | 11,872 |
| Three secondary Tier-2 upgrades | 3,000 |
| 20 worker hires | 5,000 |
| Fuse catalysts/respec | 6,000 |
| Warehouse expansion | 5,000 |
| Bag/utility/cosmetic choices | 8,000 |
| Total typical spend | 42,872 |

The remaining balance creates choice between a second deep node, more workers,
or convenience. A player cannot max every system in month one.

### Price ladder

Node upgrade formula remains:

`1,000 × 1.8^(current tier - 1)`

Rounded UI prices:

| Upgrade | Exact | Display |
|---|---:|---:|
| T1→T2 | 1,000 | 1,000 |
| T2→T3 | 1,800 | 1,800 |
| T3→T4 | 3,240 | 3,250 |
| T4→T5 | 5,832 | 5,850 |

Use displayed rounded prices as authoritative checkout prices to avoid showing
one value and charging another.

## 6. Credits

- Purchase price: 1 Credit = 1 THB.
- Integer only.
- Non-transferable and non-cashable.
- No direct Credit→Coin conversion.

Hybrid Pay:

- 1 Credit offsets 20 Coins.
- Maximum 15% of a qualifying checkout.
- Seasonal offset cap: lower of 30,000 Coins or 25% of Coins earned.
- No Credits for Exploration EXP, Worker RNG, Fuse RNG, event contribution,
  tradable items, or MMORPG materials.

At 15% substitution, paid acceleration remains bounded; free completion time
and power ceiling do not change.

## 7. Achievement budget

Month-one maximum rewards:

| Reward | Budget |
|---|---:|
| Exploration EXP | 2,000, Journey only |
| Coins | 10,000 |
| Chronicle Points | 40–60 |
| Cosmetics | 6–10 unlocks |
| Functional presets | 1–2 |

Achievement EXP must not be repeatedly farmable per node.

## 8. Post-100 supply

The share below applies to discovery-lane rolls only. Bulk-lane vanilla
commons continue at the full worker rate after Lv.100; MMORPG content never
reduces them.

MMORPG roll share:

| Level | Vanilla | MMORPG |
|---|---:|---:|
| 101–110 | 90% | 10% |
| 111–125 | 80% | 20% |
| 126–150 | 70% | 30% |
| 151–175 | 60% | 40% |
| 176–200 | 50% | 50% |

Each MMORPG material must have:

- At least two permanent sinks.
- One short-term sink.
- A target number of days to craft its first useful item.
- A maximum healthy monthly supply per active player.

Do not enable the pool until those sinks exist.

