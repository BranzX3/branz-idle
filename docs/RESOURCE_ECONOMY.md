# Resource Economy, Projects, and Trading

## 1. Economy promise

Coins and resources remain separate:

- Coins come from online time and bounded gameplay participation.
- Resources come from nodes and expeditions.
- There is no unlimited resource→Coin vendor.

A fixed daily commission may require resources and award Coins, but its daily
cap makes it a behavior reward rather than an exchange rate.

## 2. Resource lifecycle

`Node pool → Node buffer → Warehouse → Commission / Project / Crafting /
Player trade / Vanilla use`

Every item added to a drop pool must have at least two destinations. Items with
only one destination are fragile; items with no destination are inflation.

## 3. Sink hierarchy

### Short-term sinks: daily

- Focused Node Commission.
- Rotating mixed-resource deliveries.
- Expedition preparation supplies.
- Worker Charm crafting.

### Medium-term sinks: weekly

- Settlement Projects.
- Server Projects.
- Mastery challenges.
- Building façade construction.

### Long-term sinks: monthly/seasonal

- Node Capstones.
- Chronicle Hall and territory monuments.
- Post-100 MMORPG professions and equipment.
- Seasonal community structures.

## 4. Personal Commissions

Three optional daily slots:

| Slot | Purpose | Reward |
|---|---|---|
| Focus | Advances Focused Node | 400 Node EXP |
| Behavior | Encourages a system/role | 600 Coins |
| Supply | Consumes a bounded bundle | Project material/Chronicle progress |

Rules:

- One free reroll per slot.
- Requirements scale by unlocked pool and recent output.
- Requested amount is no more than 20% of expected daily production. Because
  the bulk lane (Balance Bible §3) multiplies commons output, `collect` and
  `delivery` targets are scaled to bulk volume, while `produce` and
  `expedition` targets — which measure discovery-lane output only — are not.
- Never request capped rare resources.
- Unfinished commissions roll into catch-up at 50% after the day ends.
- No Credit rerolls and no premium reward multiplier.

## 5. Settlement Projects

Personal multi-day goals that turn resources into visible territory changes.

Examples:

| Project | Duration | Inputs | Reward |
|---|---:|---|---|
| Expanded Storehouse | 2–3 days | Logs, stone, iron | Cosmetic warehouse building + preset |
| Expedition Dock | 3–4 days | Wood, wool, food | Event team preset |
| Chronicle Hall | 7 days | All five families | Achievement display building |
| Master Mine/Farm/etc. | Lv.100 | Family Capstone bundle | Permanent Node façade |

Projects show physical construction stages at 25%, 50%, 75%, and completion.
This makes resource spending visible in the world.

## 6. Server Projects

One project runs for seven days.

Contribution structure:

- Common bucket: unlimited common resources, diminishing contribution.
- Specialist bucket: rotating advanced resources, daily cap.
- Expedition bucket: active-event tokens, weekly cap.
- Social bucket: unique contributors and Helper actions.

Rewards:

- Participation threshold: badge and small Chronicle Points.
- Community completion: server-wide cosmetic or event.
- Stretch goals: visual upgrades, not production power.
- No top-one exclusive power reward.

Diminishing contribution prevents one wealthy player from completing the
project alone and preserves broad participation.

## 7. Expedition preparation

Players may optionally supply a fixed preparation kit:

- Food
- Tools/materials appropriate to node family
- One crafted map/supply token

Preparation provides a choice:

- Safer/faster route
- Extra quantity
- Extra Node EXP

It does not increase Jackpot odds directly. This creates recurring resource
sinks while preserving Luck’s identity.

## 8. Vanilla use

The server must accept that many produced resources leave Idle and enter
normal Minecraft building/crafting. That is a valid sink and part of the
vision.

Balance rare resources around:

- Vanilla acquisition difficulty.
- Whether farms already make the resource abundant.
- The server’s survival rules.
- Whether passive access skips a major progression achievement.

Building blocks can be abundant. Boss/structure trophies cannot.

## 9. Protected direct trade

Launch with direct two-player trade before an auction house.

Trade flow:

1. Player A requests trade.
2. Both enter a protected inventory GUI.
3. Resources and worker contracts are placed into escrow slots.
4. Worker card expands full stats, level, history, and tradeability.
5. Any item change resets both confirmations.
6. Both confirm.
7. Server commits one idempotent transaction.
8. Audit receipt is available to both players.

Restrictions:

- Credits, Chronicle Points, bound workers, Charms, and Capstone quest items
  cannot trade.
- Coins should not trade at launch; otherwise real-money value and alt abuse
  become more difficult to control.
- No transaction tax is needed before a marketplace exists.

## 10. Marketplace phase

Add a marketplace only after 30 days of real telemetry.

If added:

- Resource listings only at first.
- Listing duration and quantity caps.
- Small Coin listing fee, not a percentage sale tax initially.
- Price history by item.
- Worker market added later with full stat filters.
- No premium listing priority.
- No Credits in market settlement.

## 11. Inflation controls

Track:

- Produced, consumed, traded, and withdrawn quantity by item.
- Warehouse stock per active account.
- Median direct-trade price if Coins later become tradeable.
- Percentage of production destroyed by full buffers.
- Project completion speed.

Actions:

- Increase meaningful sinks before reducing player output.
- Adjust rare caps before broad drop weights.
- Rotate project demand; do not secretly delete inventory.
- Announce balance changes one week ahead when they affect established items.

## 12. MMORPG material economy

Before enabling an MMORPG material:

- Define its profession recipe tree.
- Define repair/enhancement consumption.
- Define one rotating project sink.
- Define target time to first craft.
- Define maximum healthy monthly stock.

Initial material tiers:

| Tier | First availability | Intended use |
|---|---:|---|
| Frontier I | Node 101 | Entry profession components |
| Frontier II | Node 126 | Specialized equipment |
| Frontier III | Node 176 | High-end enhancement |

MMORPG materials never sell to an NPC for Credits or real money.

