# IdleFarm Player and Admin UX Flows

## 1. UX principles

- The Hub always recommends one action.
- The player reaches the next useful screen in at most two clicks.
- Tier quantity and Exploration quality are visually separate.
- Every cost shows before/after balances.
- Every destructive or premium action confirms.
- GUI and command fallback call the same service.
- Errors explain the corrective action, not only the failure.

## 2. First-session flow

`Join → /idle → Starter Chapter → Territory Map → free Residential → choose
first Production Node → free Starter Worker → assign → scripted event → collect
→ Warehouse → Node Lv.3 → next-unlock preview`

### Hub state before first node

Only the recommended Starter Chapter glows. Other sections remain visible but
explain when they unlock.

### First-node choice

Offer:

- Mining: easiest building materials.
- Farming: fastest volume and food.
- Woodcutting: building palette and storage-friendly output.

Livestock and Hunter appear as future previews.

## 3. Main Hub

Header:

- Focused Node icon/type.
- Exploration Level and progress.
- Estimated days to next major milestone.
- Coin and Credit balances.

Priority card:

- Buffer full
- Loot ready
- Commission near completion
- Achievement completed
- Next level/pool unlock

Sections:

- Territory
- Nodes
- Workers
- Commissions
- Expeditions
- Warehouse
- Projects
- Chronicle
- Shop/Profile

The current passive Profile icon becomes a clickable Profile/Settings page.

## 4. Nodes list

Filters:

- Focused
- Needs attention
- Type
- Level
- Tier

Each card shows:

- Type, Tier, Exploration Level.
- Crew.
- Buffer bar and time-to-full.
- Active/waiting/completed event.
- Research daily cap.
- One status label: Producing, Full, Away, Empty, Upgrading.

Residential nodes open Residential Detail, never Production Node Detail.

## 5. Node Detail

Layout:

### Header

- Node type and visual state.
- Tier and slots.
- Exploration Level and current bracket.
- Current specialization.

### Production panel

- Items/hour.
- Buffer and time-to-full.
- Recent 24-hour output.
- Collect action.

### Research panel

- EXP/day at current pace.
- Remaining passive cap.
- Next level and next bracket reward.
- Estimated date to Lv.100.

### Crew panel

- Worker role badges.
- Marginal contribution.
- Active team synergy.
- Empty slot action.

### Action row

- Exploration
- Upgrade Tier
- Specialization
- Type Perks
- Pool/Journal
- Convert Type
- Unclaim

Convert and Unclaim remain double-confirmed.

## 6. Pool and Discovery Journal

Current pool:

- Item icon.
- Relative rarity band.
- Daily/weekly cap state.
- Lifetime discovered count.

Future pool:

- Next bracket item name if common/advanced.
- Silhouette and clue for discovery content.
- Exact unlock level.

Post-100:

- Vanilla/MMORPG target share.
- Disabled Frontier content says “Future system not enabled” rather than
  presenting an unusable item.

## 7. Exploration flow

`Waiting event → event detail → choose workers → synergy/odds/duration preview →
optional preparation → confirm → running status → completed result animation →
Warehouse capacity check → claim`

The result screen shows:

- Grade.
- Loot.
- Node EXP.
- Worker EXP.
- Achievement/Journal progress.
- Next likely event unlock.

If Warehouse is full, the result remains safely claimable.

## 8. Worker flow

Worker Bag:

- Filter role, rarity, level, trait, assigned/free, favorite.
- Compare up to three workers.
- Favorites never appear in Fuse material selection.

Worker Detail:

- Stats with concrete effects.
- Current role and possible future role.
- Lifetime history.
- Assigned node.
- Charm.
- Rename/skin.
- Favorite/trade/withdraw.

Fuse:

- Select protected base worker.
- Select duplicate.
- Show catalyst, success, pity, inheritance, EXP return.
- Confirm destructive material.
- Resolve server-side, then animate.

## 9. Commission flow

Hub badge shows `1 recommended / 3 available`.

Each Commission shows:

- Requirement.
- Why it was selected.
- Current progress.
- Fixed reward.
- Time remaining.
- Free reroll availability.

Completed fixed rewards auto-settle if there is no inventory risk. Item rewards
use safe claim.

## 10. Chronicle flow

Tabs:

- Journey
- Node Mastery
- Discovery
- Workers
- Territory
- Expeditions
- Social
- Seasonal

Pinned goals:

- One short goal.
- One long Mastery goal.
- One hidden Feat slot without requirement text.

Completed achievements produce one consolidated Hub notification, not repeated
chat spam.

## 11. Project flow

Project card:

- Final world preview.
- Required resource buckets.
- Contribution and next construction stage.
- Reward.
- Time remaining.

Submit flow supports:

- Exact amount.
- Fill next stage.
- Submit all eligible common items.

Always show irreversible consumption confirmation for rare/capped resources.

## 12. Shop and Credits

Separate tabs:

- Cosmetics
- Convenience
- Credits/Purchase History

Checkout:

`Benefit → Coin-only price → optional Hybrid Pay → earned-Coin requirement →
post-purchase balances → confirmation`

Never default the slider to spending Credits. The player opts in each time.

Display:

- Credits, not THB, inside the game.
- THB price only on external checkout/receipt.
- Purchase history and support contact.

## 13. Residential Detail

- Home/visit anchor.
- Visitor privacy.
- Trust summary.
- Showcase and project buildings.
- Cosmetic skin.
- Unclaim.

No Worker, buffer, or Exploration actions appear.

## 14. Trust UX

Role permission preview:

| Action | Visitor | Helper | Manager | Owner |
|---|---:|---:|---:|---:|
| Visit/use allowed doors | Yes | Yes | Yes | Yes |
| Collect buffer | No | Yes | Yes | Yes |
| Contribute project | No | Yes | Yes | Yes |
| Manage workers/events | No | No | Yes | Yes |
| Upgrade/convert/unclaim | No | No | No | Yes |

Changing a role shows exactly what access is gained or lost.

## 15. Error language

Bad:

- `Not enough money.`

Good:

- `Need 1,800 Coins; you have 1,420. Complete today's Behavior Commission for
  600 Coins.`

Bad:

- `Warehouse full.`

Good:

- `Loot is safe. Free 37 Warehouse spaces or expand capacity, then claim
  again.`

## 16. Admin Hub

Sections:

- Player Search
- Territory/Node Inspector
- Economy
- Content Editor
- Events/Projects
- Audit
- System Health

### Player Search

Shows:

- Account data, balances, purchase ledger.
- Nodes, workers, Warehouse.
- Active locks/events.
- Achievement/Commission state.
- Recent audit events.

Actions require:

- Permission.
- Preview.
- Reason.
- Confirmation.
- Audit transaction ID.

### Node Inspector

- Owner, type, tier, level, specialization.
- Crew and locks.
- Current/effective pool.
- Buffer/output/research.
- Rare-cap counters.
- Force complete/cancel only with explicit reason.
- Force-unclaim uses double confirmation and compensation choice.

### Content Editor

`Draft → validate → simulation preview → publish → rollback`

Validation:

- Valid materials.
- Positive weights.
- Nonempty fallback pool.
- Unlock ordering.
- Rare cap defined.
- MMORPG item has enabled sinks.

### Economy dashboard

- Coins created/spent.
- Credits purchased/spent/refunded.
- Resource faucets/sinks.
- Warehouse stock.
- Level velocity.
- Outlier and duplication alerts.

## 17. Accessibility/localization

- Thai is the primary copy; English may be secondary.
- Do not rely on color alone: use icon and state text.
- Avoid lore lines longer than the inventory width.
- Use consistent click conventions.
- Provide command fallbacks for essential actions.
- Allow animation reduction and notification frequency settings.
