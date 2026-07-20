# IdleFarm — Per-Node Exploration Level Design

Status: Revised proposal  
Core rule: Every Production Node has its own Exploration Level  
Target: A focused free player raises one primary node from Lv.1 to Lv.100 in 28–32 days  
Content rule: Lv.1–100 produces vanilla resources; MMORPG materials begin entering the pool after Lv.100

## 1. Progression model

There is no account-wide gameplay level. Each Production Node progresses
independently:

| System | Purpose |
|---|---|
| Node Tier 1–5 | Worker slots, building size, buffer size, throughput |
| Node Exploration Lv.1–100+ | Drop-pool quality and expedition content |
| Node Type Perks | Unique behavior choices at Lv.15/35/60/85 |
| Worker rarity/level/stats | Production efficiency and expedition performance |
| Territory/node cap | Number and composition of nodes |

This creates two meaningful axes:

- Tier answers: “How much can this node produce?”
- Exploration Level answers: “What can this node discover?”

A Tier 5 Lv.20 node produces large quantities of early resources. A Tier 2
Lv.90 node produces fewer items but can find much richer resources.

## 2. One-month target

The one-month target applies to one focused primary node, not every node owned
by the player.

Expected progression:

- Primary node: Lv.100 in 28–32 days.
- Secondary nodes receiving passive attention: Lv.35–55.
- A highly active player can maintain two focused nodes, but must split active
  expedition and commission rewards between them.
- Raising all five node families to Lv.100 is a long-term 3–5 month account
  goal.

This keeps Lv.100 meaningful and prevents the complete vanilla economy from
being exhausted in the first month.

If the intended target is all owned nodes reaching Lv.100 in one month, the
shared reward allocation should be changed; this design intentionally assumes
one primary node per month.

## 3. Exploration EXP curve

Recommended formula:

`EXP required for next level = 120 + (12 × current level)`

Lv.1 → Lv.100 requires approximately 71,000 Exploration EXP.

The current formula (`500 × next level`) requires roughly 2.5 million EXP and
does not fit a one-month free-player target.

### Daily EXP budget for a focused node

| Source | Daily/weekly target | 30-day total |
|---|---:|---:|
| Assigned-worker research | Typical 650/day; cap 900 | 19,500 |
| First collection bonus | 100/day | 3,000 |
| First regular expedition completion | 700/day | 21,000 |
| Focused Node Commission | 400/day | 12,000 |
| Weekly Node Chapter | 3,500/week | 14,000 |
| First-node tutorial | One time | 2,000 |
| Total | | 71,500 |

### Research EXP rules

Exploration growth should not depend only on item count. Otherwise fast Mining
nodes level faster than lower-rate Hunter nodes.

Use:

`Research EXP/hour = 75 × CountBonus × AverageResearchPower`

`CountBonus = 1 + 0.15 × (workers - 1)`

Research power uses:

- Worker count.
- Worker Stamina.
- Small bonus from worker level.
- No direct bonus from production boosters.

Rules:

- Full research rate while the node is producing.
- 25% research rate while its item buffer is full.
- Daily passive-research cap of 900 EXP per node.
- No research with zero assigned workers.
- Rested research stores up to 48 hours for nodes below Lv.100.

The 25% full-buffer rate preserves the idle promise without making storage
management irrelevant.

## 4. Player focus system

The player selects one `Focused Node`.

Focused Node receives:

- Daily Node Commission.
- First-expedition bonus.
- Weekly Node Chapter progress.
- Strong visual tracking in the Main Hub.

Focus can change once every 24 hours. Changing focus does not remove EXP.

Why focus exists:

- Prevents five nodes from receiving five copies of every daily reward.
- Gives players agency over which MMORPG material family they unlock first.
- Makes “my first Lv.100 node” a personal build decision.

Passive research continues on every staffed node, so secondary nodes never stop
progressing.

## 5. First ten minutes

1. Claim the first Residential node for free.
2. Select the first node family: Mining, Farming, or Woodcutting.
3. Receive one account-bound Starter Worker.
4. Claim and construct the first Production Node for free.
5. Assign the worker.
6. The node becomes the initial Focused Node.
7. Complete a scripted 3–5 minute expedition.
8. Collect the first vanilla resources to Warehouse.
9. Reach Node Exploration Lv.3.
10. Preview the Lv.10 pool and choose the next objective.

The tutorial gives Node EXP, not a permanent account level.

## 6. Universal Lv.1–100 structure

Every node family uses ten brackets. The exact resources differ by node type,
but the purpose of each bracket is consistent.

| Levels | Phase | Drop-pool purpose |
|---|---|---|
| 1–9 | Camp | Most common survival materials |
| 10–19 | Survey | Broad basic-resource variety |
| 20–29 | Extraction | Early metal/crafting resources |
| 30–39 | Trade | Resources useful in larger builds and trade |
| 40–49 | Industry | Redstone, enchanting, potion-support materials |
| 50–59 | Frontier | Controlled rare vanilla resources |
| 60–69 | Specialist | Player-selected output preference |
| 70–79 | Deep Expedition | Difficult biome/dimension materials |
| 80–89 | Master Survey | High-value quantity and reliability |
| 90–99 | Vanilla Mastery | Best vanilla table without breaking progression |
| 100 | Mastered | Completes the vanilla node journey |
| 101+ | MMORPG Frontier | Adds MMORPG materials to the existing pool |

Node Type Perks punctuate the brackets without replacing pool unlocks:

- Lv.15: Foundation perk.
- Lv.35: Operations perk.
- Lv.60: Direction/resource-family perk.
- Lv.85: Legacy perk.

The complete choices and guardrails are defined in `NODE_TYPE_PERKS.md`.

Bracket unlocks should add resources or improve weights. Avoid deleting common
resources players still need.

## 7. Mining Node Lv.1–100

| Levels | Additions |
|---|---|
| 1–9 | Cobblestone, stone, coal |
| 10–19 | Deepslate, copper |
| 20–29 | Raw iron, flint |
| 30–39 | Redstone, raw gold |
| 40–49 | Lapis, quartz |
| 50–59 | Amethyst, small diamond weight |
| 60–69 | Emerald, output preference for iron/redstone/building stone |
| 70–79 | Obsidian, glowstone dust through expeditions |
| 80–89 | Improved diamond/emerald reliability |
| 90–99 | Very limited ancient debris/netherite scrap with weekly cap |
| 100 | Mining Mastery cache and cosmetic building upgrade |

Never passively produce Nether Stars, Heavy Cores, Dragon Eggs, or structure
exclusive trophies.

## 8. Farming Node Lv.1–100

| Levels | Additions |
|---|---|
| 1–9 | Wheat, carrot, potato |
| 10–19 | Beetroot, pumpkin, melon |
| 20–29 | Sugar cane, cactus, cocoa |
| 30–39 | Mushrooms, berries, bamboo |
| 40–49 | Nether wart through expeditions |
| 50–59 | Honey products and potion-support crops |
| 60–69 | Crop preference and seed-efficiency specialization |
| 70–79 | Chorus fruit and difficult-biome plants |
| 80–89 | Improved rare-crop reliability |
| 90–99 | Master agricultural bundles and cosmetic flora |
| 100 | Farming Mastery cache and cosmetic greenhouse upgrade |

## 9. Woodcutting Node Lv.1–100

| Levels | Additions |
|---|---|
| 1–9 | Oak, birch, spruce |
| 10–19 | Jungle and acacia |
| 20–29 | Dark oak and mangrove |
| 30–39 | Cherry and bamboo blocks |
| 40–49 | Stripped/log variant preferences |
| 50–59 | Crimson and warped stems through expeditions |
| 60–69 | Species preference and quantity specialization |
| 70–79 | Resin/sapling support if available in the server version |
| 80–89 | Improved dimension-wood reliability |
| 90–99 | Master carpenter bundles and decorative components |
| 100 | Woodcutting Mastery cache and cosmetic sawmill upgrade |

## 10. Livestock Node Lv.1–100

| Levels | Additions |
|---|---|
| 1–9 | Beef, pork, chicken, eggs |
| 10–19 | Leather, wool, feathers |
| 20–29 | Milk, rabbit hide |
| 30–39 | Honey bottles, fish varieties |
| 40–49 | Ink sacs, glow ink, turtle-support materials at low weight |
| 50–59 | Rabbit foot, nautilus shell through expeditions |
| 60–69 | Meat, textile, or aquatic specialization |
| 70–79 | Difficult-biome animal products |
| 80–89 | Improved rare-product reliability |
| 90–99 | Master ranch bundles and cosmetic companions |
| 100 | Livestock Mastery cache and cosmetic ranch upgrade |

## 11. Hunter Node Lv.1–100

| Levels | Additions |
|---|---|
| 1–9 | Rotten flesh, bone, string |
| 10–19 | Gunpowder, spider eye, arrows |
| 20–29 | Slime balls and magma cream |
| 30–39 | Ender pearls at low weight |
| 40–49 | Blaze powder/rods through expeditions |
| 50–59 | Ghast tears and phantom membranes |
| 60–69 | Undead, arachnid, or dimensional specialization |
| 70–79 | Prismarine and difficult-mob materials |
| 80–89 | Improved rare-drop reliability |
| 90–99 | Wither skeleton skull only through capped active expeditions |
| 100 | Hunter Mastery cache and cosmetic guild-hall upgrade |

Passive production should never output raid trophies or boss-exclusive items.

## 12. Drop-table behavior

Use additive pools with weight evolution:

- Common vanilla resources remain available at every level.
- Higher brackets reduce their relative weight but do not remove them.
- Node Mastery Lv.60 unlocks a favored-resource preference.
- Rare vanilla resources use account-level daily/weekly caps, not only tiny
  random weights.
- The UI displays the current pool, next additions, and capped resources.

Recommended roll composition before Lv.100:

- 65–85% common/utility resources.
- 12–30% advanced resources.
- 1–5% rare resources depending on bracket.
- Progression-breaking items: 0%.

## 13. Regular Exploration Events

Events are the active acceleration lane for the selected node.

Team-selection preview shows:

- Workers committed.
- Production lost during the run.
- Duration after Speed.
- Normal/Great/Jackpot chances after Luck.
- Exploration EXP reward.
- Possible loot families, not exact rolled loot.

Daily first-completion bonus applies only to the Focused Node. Additional
events still grant loot, Worker EXP, and a smaller amount of Node EXP.

Recommended cadence:

- One natural event opportunity every 45–90 minutes per online owner.
- Event inbox shared by all nodes.
- Event remains available for 90 minutes of online time.
- Maximum three waiting events across the territory.

This removes notification spam while retaining meaningful active decisions.

## 14. Lv.100 and the MMORPG transition

Lv.100 is a soft threshold, not a reset and not a mode switch.

At Lv.100:

- The node retains every vanilla resource.
- The Lv.100 Mastery cache and cosmetic building upgrade are awarded.
- The node becomes `frontier_eligible`.
- MMORPG items do not drop until `frontier.enabled` is enabled.

After MMORPG launch, levels continue beyond 100 and add MMORPG materials to
the same weighted pool:

| Node level | Vanilla share | MMORPG share | MMORPG tier |
|---|---:|---:|---|
| 101–110 | 90% | 10% | Tier 1 |
| 111–125 | 80% | 20% | Tier 1 |
| 126–150 | 70% | 30% | Tier 1–2 |
| 151–175 | 60% | 40% | Tier 2 |
| 176–200 | 50% | 50% | Tier 2–3 |

These percentages are target roll shares, configurable per node type and
season. Vanilla never reaches 0%.

Suggested MMORPG families:

| Node | MMORPG pool after Lv.100 |
|---|---|
| Mining | Aether Ore, Mythril Fragment, Resonant Crystal |
| Farming | Mana Herb, Life Seed, Astral Pollen |
| Woodcutting | Spiritwood Resin, Ancient Bark, Living Fiber |
| Livestock | Mystic Hide, Beast Core, Primal Blood |
| Hunter | Soul Ash, Corrupted Essence, Void Fang |

The materials are crafting inputs, not finished equipment. They should not be
enabled before professions, crafting, or another durable sink is available.

## 15. Coin economy

Coins remain the free gameplay currency.

Expected 30-day free income:

| Source | Approximate total |
|---|---:|
| Online payout, approximately 60 hours/month | 36,000 |
| Daily behavior commissions | 18,000 |
| Weekly node chapters | 8,000 |
| Journey/Mastery achievements | 10,000 |
| Expected Global Expedition reward | 2,000 |
| Total | 74,000 Coins |

Coin sinks:

- Node claims and Tier upgrades.
- Worker hiring and non-premium fuse catalysts.
- Warehouse and Worker Bag capacity.
- Node focus change before cooldown.
- Node specialization respec.
- Residential plots and construction cosmetics.

Exploration Level itself cannot be bought with Coins or premium currency.

## 16. Paid currency

Working name: `Credits`.

Purchase presentation:

- Checkout price: 1 Credit costs 1 Thai baht.
- This is a purchase price, not a redemption value.
- No cash-out, player transfer, market trading, or cross-server transfer.
- The in-game balance is called Credits, not THB, baht, wallet, deposit, or
  e-money.
- Keep immutable purchase, spend, refund, and admin-adjustment history.

Credits never buy:

- Exploration EXP.
- Research time.
- Expedition rewards.
- Worker gacha or Fuse RNG.
- MMORPG materials.
- Tradable resources or workers.
- Leaderboard contribution.

### Hybrid Pay

Credits can offset part of qualifying Coin purchases:

- 1 Credit offsets 20 Coins.
- At least 85% of each purchase remains payable in earned Coins.
- Maximum offset: 30,000 Coins per season.
- Seasonal offset is also capped at 25% of Coins earned by that account,
  whichever limit is lower.

Eligible:

- Node claims and Tier upgrades.
- Warehouse/Bag capacity.
- Residential plots.
- Fixed-result convenience perks.

Ineligible:

- Exploration Level and Focused Node rewards.
- Worker hire/fuse.
- Competitive events.
- Player trading.

Example:

- Node upgrade costs 10,000 Coins.
- Player may pay 8,500 Coins + 75 Credits.
- Free player may always pay the full 10,000 Coins.

## 17. UI requirements

### Main Hub

- Focused Node and Exploration Level are the primary progress display.
- Show “Next pool addition at Lv.X”.
- Show one Recommended Action.
- Nodes with full buffers, waiting events, or Lv.100 readiness use badges.

### Node Detail

- Exploration Level and EXP bar.
- Current bracket and next bracket.
- Current weighted resource families.
- Newly added resources at the next bracket.
- Research EXP/day and remaining daily cap.
- Estimated days to Lv.100 at the recent seven-day pace.
- Tier throughput shown separately from Exploration quality.

### Lv.100

- Mastery ceremony.
- Vanilla table remains visible.
- Frontier material preview is hidden or marked “Coming in a future season”
  until enabled.

## 18. Telemetry and tuning

Track per node type:

- Median days from Lv.1 to 10, 25, 50, 75, and 100.
- Passive/event/commission EXP split.
- Time spent buffer-full.
- Focus selection and focus switching.
- Resource output per bracket.
- Rare-resource cap hits.
- Event acceptance and team sizes.
- Percentage of accounts with one, two, and five Lv.100 nodes.
- Post-100 vanilla/MMORPG output share.

Initial target:

- First node Lv.10: day 2.
- Lv.25: day 7.
- Lv.50: day 14.
- Lv.75: day 22.
- Lv.100: day 28–32.

Tune EXP source values before changing the curve. The level curve should remain
stable so players can trust displayed time estimates.

## 19. External policy constraints

Before launching Credits, re-check the current Minecraft Usage Guidelines.
Server virtual currencies must remain server-only, non-cashable, and not
convertible into real-world currency. Pricing and purchase history must be
transparent.

Official references:

- https://www.minecraft.net/en-us/usage-guidelines
- https://www.bot.or.th/en/our-roles/payment-systems/payment-act-oversight/Notification-and-Circulars-under-Payment-Systems-Act-BE-2560.html
- https://www.rd.go.th/fileadmin/download/eService.pdf

## 20. Reward-loop philosophy

The player should never wait 20 levels for the next meaningful moment. Rewards
must operate at four cadences:

| Cadence | Frequency | Player feeling |
|---|---|---|
| Micro | Every 3–10 minutes | “That action mattered.” |
| Session | Every 20–45 minutes | “I completed something today.” |
| Chapter | Every 5–10 node levels | “My node changed and something new opened.” |
| Long-term | Weekly/monthly | “I am building a unique territory.” |

The complete loop is:

`Choose goal → perform activity → receive immediate feedback → see progress →
anticipate next unlock → claim meaningful reward → make a new choice`

Every reward should create or clarify the next decision. Pure currency popups
without a new decision become background noise.

## 21. Achievement system: The Pioneer Chronicle

The achievement system is called the `Pioneer Chronicle`.

It has three jobs:

1. Teach every important gameplay system.
2. Recognize different play styles.
3. Preview future content and create long-term identity.

Achievements are not a separate checklist disconnected from gameplay. Each
visible achievement points to a useful next action in an existing system.

### Achievement types

| Type | Visibility | Purpose |
|---|---|---|
| Journey | Always visible | Tutorials and essential progression |
| Mastery | Always visible | Per-node long-term expertise |
| Discovery | Silhouette until discovered | New resources, events, and locations |
| Feat | Hidden until earned | Memorable or skillful moments |
| Social | Visible | Cooperation and territory relationships |
| Seasonal | Visible during season | Fresh goals without resetting permanent progress |

## 22. Achievement categories

### 22.1 Journey achievements

These guide the first hours and major feature unlocks.

Examples:

| Achievement | Requirement | Reward |
|---|---|---|
| A Place to Begin | Claim the first Residential node | Starter Worker |
| First Camp | Construct the first Production Node | 300 Node EXP |
| First Shift | Assign a worker | Worker name ticket |
| Supplies Arrive | Collect the first buffer | 250 Coins |
| Organized Storage | Withdraw from Warehouse | +100 temporary Warehouse capacity for 7 days |
| Beyond the Fence | Complete the first expedition | 500 Node EXP |
| Growing Crew | Fill every worker slot on a node | Worker portrait frame |
| A Real Settlement | Own three different production types | Territory banner |

Journey rewards can accelerate onboarding but should contribute no more than
5% of the total Lv.1–100 Node EXP budget.

### 22.2 Per-node Mastery tracks

Every node family has its own achievement page with four ranks:

- Bronze: understands the node.
- Silver: uses the node consistently.
- Gold: optimizes the node.
- Platinum: masters the node at Lv.100.

Requirements combine multiple behaviors. Do not use item count alone.

Example Mining Mastery:

| Rank | Requirements | Reward |
|---|---|---|
| Bronze Surveyor | Reach Lv.10, collect 500 items, finish one Mining event | Mining banner pattern |
| Silver Prospector | Reach Lv.35, discover 8 mining resources, use two worker traits | 1,000 Coins + minecart cosmetic |
| Gold Deep Delver | Reach Lv.70, finish a Jackpot/Great event, hit Mastery specialization | Golden pickaxe NPC cosmetic |
| Platinum Earthbreaker | Reach Lv.100 and complete the Mining Capstone | “Earthbreaker” title + Master Mine façade |

Equivalent themes:

- Farming: Cultivator → Botanist → Harvest Sage → Lifebringer
- Woodcutting: Forester → Arborist → Grove Keeper → Spirit of the Woods
- Livestock: Rancher → Breeder → Beast Keeper → Wildheart
- Hunter: Tracker → Slayer → Night Warden → Dreadhunter

### 22.3 Discovery Journal

Every resource and exploration event has a journal entry.

Before discovery:

- Show a silhouette.
- Show the level range or a short clue.
- Never show exact rare-drop odds unless the item is part of a paid or
  repeatable-random purchase. Normal gameplay drops may show rarity bands.

After discovery:

- Show name, node family, bracket, owned/lifetime-produced count, and use.
- Preview the next undiscovered resource in that family.

Collection milestones:

- Discover 5 unique resources.
- Complete one full bracket.
- Discover every vanilla resource family for one node.
- Complete all five Lv.1–100 journals.

The journal turns a random drop into permanent account knowledge and provides
anticipation even when the item itself is common.

### 22.4 Worker achievements

Reward relationships and team building, not only rarity luck.

Good requirements:

- Raise one worker by 10 levels.
- Complete 20 shifts with the same worker.
- Win a Great expedition with three different traits.
- Reach a stat milestone through natural growth.
- Keep the Starter Worker until the first node reaches Lv.30.
- Complete an expedition using only Common workers.

Avoid:

- “Hire a Legendary Worker.”
- “Fail Fuse ten times.”
- Achievements requiring premium spending.
- Achievements that pressure players to destroy valued workers.

RNG-only achievements create frustration rather than mastery.

### 22.5 Territory and logistics achievements

Examples:

- Maintain a connected five-node territory.
- Keep every active buffer below 90% for 24 hours.
- Fill 80% of Warehouse capacity without reaching 100%.
- Collect five node types in one action.
- Build a residential showcase.
- Activate a five-family synergy.
- Change Focus and complete the new node’s commission.

These make storage and layout part of gameplay instead of passive menus.

### 22.6 Expedition achievements

Examples:

- First Normal, Great, and Jackpot result.
- Complete an expedition under a target duration.
- Complete one with a high-Luck team.
- Complete one with a high-Speed team.
- Complete one event from every bracket.
- Commit to the Global Expedition for four different weeks.

No achievement should require placing first on a server leaderboard. Ranking
depends too heavily on server population and spending behavior.

### 22.7 Social achievements

Examples:

- Visit another territory through a visit anchor.
- Complete a cooperative Server Project.
- As a Helper, collect a full buffer for the owner.
- Trade a worker through the protected trade flow.
- Complete an expedition with a cooperative support contribution.

Anti-abuse:

- Unique accounts count only after minimum playtime and progression.
- Repeating actions with the same account does not farm achievement progress.
- Social rewards are cosmetic or Chronicle Points, not large Coin payouts.

### 22.8 Feats and secrets

Hidden Feats reward memorable stories:

- Complete an expedition with exactly one second remaining.
- Receive a Jackpot with a low-Luck team.
- Recover every buffer while Warehouse has exactly enough space.
- Keep one worker assigned from Node Lv.1 to Lv.100.
- Reach Lv.100 without changing the node’s favored resource.

Hidden achievements must not contain core power or required collection
completion. They are delightful surprises, not secret optimal strategies.

## 23. Chronicle Points and rewards

Achievements award `Chronicle Points`, a non-purchasable, non-tradeable account
score.

Chronicle Points:

- Cannot be purchased with Credits.
- Cannot be converted to Coins.
- Never reset.
- Unlock Chronicle reward tiers.

Recommended permanent tiers:

| Points | Reward |
|---:|---|
| 10 | Pioneer profile frame |
| 25 | Territory banner set |
| 50 | Second saved Worker team preset |
| 75 | Residential statue blueprint |
| 100 | Custom collect animation |
| 150 | Chronicle Hall building skin |
| 200 | Master Pioneer title |
| 300 | Five-node mastery monument |

Achievement rewards are chosen from:

- Chronicle Points.
- Small one-time Coin grants.
- Small Node EXP grants for Journey achievements only.
- Titles, badges, building façades, NPC tools/poses, particles, sounds.
- UI presets and organization convenience.
- Lore pages and previews of future content.

Achievement rewards never include:

- Credits.
- Tradable rare resources.
- Random paid-value items.
- Permanent production multipliers.
- Exclusive MMORPG combat power.

## 24. Reward pacing across Node Lv.1–100

Every level gives feedback, but not every level requires opening a reward chest.

### Every level

- Action-bar level-up message.
- Small visual pulse on the node/NPC.
- Current progress rolls into the next level without loss.

### Every five levels

- Chronicle mini-milestone.
- Small fixed reward.
- Preview of the next bracket resource/event.
- One recommended action.

### Every ten levels

- New vanilla drop bracket.
- New event family or node option.
- Visible building/NPC decoration change.
- Mastery achievement progress.
- Manual reward claim with a clear reward card.

### Levels 25, 50, 75, and 100

These are major ceremonies:

- Lv.25: choose the first node preference.
- Lv.50: choose an expedition specialization.
- Lv.75: unlock the Mastery challenge.
- Lv.100: complete the node Capstone and Mastery façade.

The player should always see the next major ceremony in Node Detail.

## 25. Anticipation design

The UI should answer three questions at all times:

1. What am I doing now?
2. What will I unlock next?
3. How long will it approximately take?

Node Detail shows:

- Current level and recent seven-day pace.
- Next five-level reward.
- Next ten-level pool additions.
- One nearly completed achievement.
- One longer Mastery objective.

Use `near completion` prompts only at meaningful thresholds:

- 70%: appears quietly in Chronicle.
- 90%: Node Detail highlights it.
- 100%: celebration and claim notification.

Do not send chat messages for every incremental counter update.

### Reward reveal rules

- Fixed functional rewards are shown in advance.
- Cosmetic milestone rewards are shown in advance.
- Discovery entries use silhouettes and clues.
- Random expedition grades show odds before team commitment.
- Random results are rolled server-side before animation.

This balances certainty and surprise. Players trust the system while still
looking forward to discoveries.

## 26. Session, daily, weekly, and monthly loops

### Session loop: 20–45 minutes

1. Open the Hub and see the Focused Node.
2. Collect or resolve a full-buffer warning.
3. Complete one Node Commission step.
4. Make one worker/team decision.
5. Start or claim an expedition.
6. Advance one near-complete achievement.
7. Leave with a clear next unlock preview.

### Daily loop

- Focused Node Commission.
- First collection bonus.
- First expedition bonus.
- One rotating behavior objective, such as using a different trait.
- Chronicle displays daily progress but does not punish missed days.

### Weekly loop

- Weekly Node Chapter.
- Global Expedition contribution.
- Server Project.
- One rotating Mastery challenge.
- Catch-up pool for missed daily progress.

### Monthly loop

- Bring the Focused Node close to or through Lv.100.
- Complete one Platinum node track.
- Choose the next node family to focus.
- Add a permanent territory visual showing the mastered family.

The monthly transition is:

`Master one node → celebrate it permanently → choose the next family → see a
different set of resources/events/achievements`

This creates renewed novelty without resetting existing progress.

## 27. Dynamic Commissions

Daily objectives should adapt to the player’s current bracket and recent
behavior.

Commission templates:

- Produce a resource currently in the node’s pool.
- Collect before the buffer reaches a threshold.
- Use a worker with a specified trait family.
- Complete an event with one or two workers.
- Deliver a mixed resource bundle.
- Help a trusted territory.

Rules:

- Never request a resource the player has not unlocked.
- Never require a rarity the player does not own.
- Do not repeat the same template more than twice in three days.
- Offer one free reroll daily.
- Credits cannot reroll into better rewards.
- Catch-up commissions use simpler requirements.

Dynamic commissions make existing systems feel different each day without
requiring new content assets every day.

## 28. Achievement technical design

Definitions should be data-driven:

`plugins/IdleFarm/achievements.yml`

Suggested definition:

```yaml
mining_bronze:
  category: MASTERY
  scope: NODE
  node-type: MINING
  visible: true
  requirements:
    exploration-level: 10
    produced-items: 500
    completed-events: 1
  rewards:
    chronicle-points: 5
    cosmetic: mining_banner_1
```

Suggested persistence:

```text
idlefarm_achievement_progress
  owner_uuid
  achievement_id
  scope_type        ACCOUNT | NODE | WORKER | SEASON
  scope_id
  progress_json
  completed_at
  claimed_at
  PRIMARY KEY (owner_uuid, achievement_id, scope_id)
```

Gameplay services emit domain events:

- NODE_CLAIMED
- WORKER_ASSIGNED
- ITEM_PRODUCED
- BUFFER_COLLECTED
- EXPLORATION_COMPLETED
- NODE_LEVEL_UP
- WORKER_LEVEL_UP
- TRUST_ACTION
- TRADE_COMPLETED
- PROJECT_CONTRIBUTED

Achievement progress listens to these events. GUI and commands must not update
achievement counters directly.

Reward claiming must be idempotent:

- Completion and reward settlement use a unique transaction key.
- Reopening the menu or reconnecting cannot grant the reward twice.
- Every completion and reward is written to the audit log.

## 29. Achievement telemetry

Track:

- Completion rate and median completion time.
- Achievements abandoned after 70% progress.
- Reward claim delay.
- Which achievement is pinned.
- Commission reroll and abandonment rates.
- Node levels where players stop returning.
- Session length before and after a milestone.
- Retention after Lv.25, 50, 75, and 100.

Warning indicators:

- Over 80% completion means the achievement may be automatic and meaningless.
- Under 5% for a visible non-Feat achievement may mean unclear or overtuned.
- High progress but low claims indicates poor notification/UI.
- One node type progressing much faster indicates EXP-source imbalance.
