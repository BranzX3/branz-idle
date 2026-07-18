# IdleFarm — Product Spec (v1.0 target / final product)

## 1. Vision

A chill survival server where players build their base freely, and progress
through an **idle chunk-node system** instead of grinding vanilla resource
gathering. Being online (even AFK) earns baseline **Money**. Money is spent
to claim and upgrade **Nodes** — chunks around the player's home that
passively generate real Minecraft **resources**, 24/7, online or offline,
worked by visible **NPC workers** living in constructed buildings.
Content is added via **seasons** (new node types, resources, buildings)
without wiping player progress.

Build approach: **single push to final product**, then seasonal content
updates on top.

## 2. Two separate economies

| | Money | Resources |
|---|---|---|
| Earned from | Playtime (online, AFK included) | Production Nodes, ticking 24/7 incl. offline |
| Spent on | Claiming nodes, tier upgrades, cap expansion, Residential plots | Building, crafting, trading — real items |
| Stored as | Number per player (`idlefarm_players.balance`) | Per-node storage buffer → collected via GUI |

No conversion between the two.

## 3. The Node/Chunk system

- **1 Minecraft chunk = 1 Node.**
- **Residential Node**: player's claimed home chunk. No production, no cap
  cost. Full protection. Players can buy extra Residential plots with Money
  for building space (free players included — building is never cap-gated).
- **Production Node**: claimed chunk that passively generates one resource
  category. Costs Money to claim and counts against the **Node Cap**.
- **Contiguity rule**: every claimed node must be orthogonally adjacent to
  another node the player owns, forming one connected territory anchored
  at the Residential Node (Towny-style blob).
- **Node Cap**: max simultaneous Production Nodes per player.
  - Free baseline cap for everyone.
  - Expandable via Money, server rank, or season pass.
  - Players choose their own node-type mix within the cap — no fixed layout.

### 3.1 Launch node types (5)

| Type | Output (examples, by tier) |
|---|---|
| Mining | cobble/coal → iron/gold → diamond-adjacent |
| Farming | wheat/carrot/potato → advanced crops |
| Woodcutting | logs of various species |
| Livestock/Fishing | meat, leather, wool, eggs, fish |
| Hunter | hostile drops: bone, gunpowder, string, ender pearl |

Exact per-tier output tables are a content/balance file (YAML), designed to
be extended per season without code changes.

## 4. Buildings & the Worker system

### 4.1 Buildings

- Each Production Node has a **preset building schematic** (worker housing).
  Claiming the node **constructs** the building in the chunk (staged
  construction animation optional; at minimum paste-on-claim).
- Higher tier may also visually extend/upgrade the building schematic.

### 4.2 Workers (physical items, gacha + fuse + growth)

- **Tier = worker slots.** Upgrading a node's tier opens another worker
  slot in that node's housing.
- **A worker is a physical Minecraft item** (custom item with identity
  stored in PDC/NBT — think a contract card/spawn egg). It can be kept in
  inventory, stored in chests, dropped, and **traded between players
  hand-to-hand** like any item. No roster cap needed — storage is the
  player's own problem/space.
- Each worker item carries:
  - **Rarity** (Common → Uncommon → Rare → Epic → Legendary), rolled at
    hire — sets base-stat roll ranges and level cap.
  - **Level/EXP**, grown while assigned and working/exploring.
  - **4 Stats** and a **Trait** (see §4.3).

### 4.3 Worker stats & growth

**Stats (4):**

| Stat | Effect |
|---|---|
| Diligence (ขยัน) | production rate contribution |
| Luck (โชค) | rare-drop chance in production & exploration loot quality |
| Stamina (อึด) | EXP gain rate (levels faster) |
| Speed (เร็ว) | reduces Exploration Event run time |

- **Base stats** are rolled at mint (hire or fuse) within ranges set by
  rarity — higher rarity ⇒ higher/wider ranges.
- **Trait (นิสัย)**: each worker rolls one Trait at mint (e.g. Hardworking,
  Fortunate, Tireless, Swift, Balanced). The Trait **biases the random
  stat allocation on level-up** — points land randomly but weighted toward
  the Trait's favored stat(s). Trait is permanent and shown on the item.
  Trait catalog + weights live in balance YAML (seasonal additions
  possible).
- **Level-up**: each level grants stat points, randomly allocated with the
  Trait's weighting. Same rarity + same trait can still grow differently —
  "god-roll" workers emerge naturally and drive the trade market.
- **Level cap scales with rarity** (e.g. Common 20 → Legendary 100).
- **Fuse resets level**: the fused higher-rarity worker starts at Lv.1
  with fresh base stats + a new Trait roll (partial EXP refund from
  materials configurable in YAML).
- **Hiring**: spend Money to recruit → receive a worker item with RNG
  rarity (gacha loop; primary long-term Money sink alongside claims/tiers).
- **Fuse system**: combine multiple worker items to produce a
  higher-rarity worker (recipe/odds in balance YAML) — this is the
  duplicate sink and the pity/deterministic path to high rarities.
- **Assignment**: insert the worker item into a node slot (via node GUI) —
  the item is consumed and the worker materializes as the node's NPC;
  ejecting the worker returns the item (with its level/identity intact).
  Lost items are lost workers — item risk is part of the economy.
- NPC workers are simple state machines, no work animations:
  - `WORKING` — leaves the housing, stands/roams at the work area.
  - `IDLE` — at/around the housing.
  - `STOP` — inactive (node paused, storage full).
  - `EXPLORING` — away on an exploration run (not visible in chunk).
- NPCs are decoration + state indicator; **production is pure math**
  (rate formula), never entity AI. NPCs only spawn in loaded chunks;
  offline/unloaded production is unaffected.

### 4.4 Schematic Definitions & NPC anchors

Every building is registered as a **Schematic Definition**: the block data
plus admin-authored metadata. One definition serves every node that uses
that building type/tier — set once, applies everywhere.

**Anchor points (per definition):**
- **Spawn anchors `1..N`** — one per worker slot, like a bed per worker.
  Worker in slot 1 spawns/idles at anchor 1, slot 2 at anchor 2, etc.
  N matches the building's max slots (tier).
- **Work-site anchors** — points where WORKING NPCs walk to / stand at.
- **Wander zone** — radius or region for IDLE random walking.

**Animation profiles (per definition, per state):**
- Admin chooses which visuals each state uses for this building —
  e.g. WORKING = walk-to-worksite + tool-in-hand + arm-swing;
  IDLE = wander + look-at-player; STOP = stand + sneak.
- **Defaults apply when unset**: a definition with no explicit profile
  falls back to the global default profile (config), so a building is
  loadable with zero setup and can be polished later.
- A definition is validated at load: missing spawn anchors beyond slot
  count → filled by auto-layout fallback (line in front of the building).

**Admin authoring flow (in-game):**
- `/idle admin schem edit <definition>` — enter edit mode on a definition
- `/idle admin schem setspawn <slot>` — set spawn anchor at admin's feet
- `/idle admin schem setwork` — add a work-site anchor
- `/idle admin schem setanim <state> <profile>` — bind animation profile
- `/idle admin schem save` — persist definition; nodes pick it up on
  next NPC refresh/chunk load
- Definitions persist as YAML files under `plugins/IdleFarm/schematics/`
  (block data + anchors + profiles) — season content is a file drop.

## 5. Production model

- Rate formula per node:
  `output/hour = base_rate(type) × Σ [ base_power(rarity, level) × (1 + diligence/100) ]`
  over assigned workers. `base_power` scales with rarity+level (gear-score
  style); Diligence is a direct multiplier on top of it.
- Accrues into a **per-node storage buffer**. **Buffer full ⇒ production
  stops** (node state → `STOP`), resuming after collection. Buffer size
  scales with tier.
- True idle: accrual continues offline/unloaded, computed lazily from
  `last_tick_at` timestamp deltas on load/collect — no per-player
  schedulers while offline.
- **Tier upgrade costs are exponential** (×1.5–2 per tier) — long-term
  goals for high tiers, idle-game feel.

## 5b. Exploration framing & the RNG layer

**Visual metaphor**: a Production Node is not a factory producing inside
its chunk — it is a **base camp from which workers go out exploring** and
bring resources back. Regular production (§5) *is* exploration,
thematically. Hence each node has **two separate progressions**:

| | Tier | Exploration Level |
|---|---|---|
| What it is | Worker slots (housing size) | How far/deep this node's expeditions reach |
| Raised by | Spending Money | Accumulates from workers working/exploring at this node |
| Affects | Throughput (more workers) | Drop *quality/value range* — higher level ⇒ richer drop tables |

### 5b.1 Exploration Level & Brackets

- Exploration Level is grouped into **Brackets** (e.g. Lv 1–9 = Bracket I,
  10–19 = Bracket II, …). Each bracket unlocks a richer drop table for
  baseline production **and** a new pool of Exploration Events for that
  node. Bracket thresholds/caps per node type live in balance YAML.
- **Growth**: exploration EXP accrues **passively while workers are
  WORKING** at the node (rate scales with worker count/stats — Stamina
  contributes), plus a **large lump bonus for completing an Exploration
  Event**. Idle players still progress; active players progress much
  faster. Passive accrual is computed in the same lazy timestamp pass as
  production (works offline).
- Baseline production draws from the node's normal drop table, whose value
  range is gated by the node's current bracket.
- A node with **zero assigned workers produces nothing** and gains no
  exploration EXP — workers are the engine.

### 5b.2 Exploration Events

- Events **spawn randomly per node with an expiry window** (e.g. rolls a
  chance every N minutes while the node has workers; event stays available
  for M minutes/hours then disappears). Spawn rolls happen only while the
  owner is **online** — no punishing FOMO for sleeping; expiry timers
  pause at owner logout (grace configurable). Players get a
  notification + the event shows in the node GUI / territory map.
- Event pool is drawn from the node's current **bracket** — higher
  brackets roll rarer, richer events.
- **Team commitment**: the player assigns **one or more workers** from
  that node to the event (state → `EXPLORING`). More workers / better
  stats ⇒ better outcome roll and larger loot share. Workers on an event
  do not count toward baseline production (opportunity cost).
- **Run time** is real time; reduced by the team's Speed stat. Runs
  continue and complete while offline (timestamp math).
- **Loot is guaranteed** — no fail state. Outcome grade
  (Normal / Great / Jackpot) scales quantity/quality; team Luck shifts
  the grade odds. Loot is rolled at completion and **claimed in the GUI
  → goes to the Warehouse**.
- One **regular** event may be active per node at a time (multiple nodes
  can each run their own event concurrently). The weekly **Global
  Expedition** (§8b) uses a separate commitment slot and can run
  alongside a node's regular event.
- Completing an event grants the node a **large exploration EXP bonus**
  (the fast lane for bracket progression) and worker EXP to participants.

### 5b.3 Future-facing

- Exploration drop tables are the delivery mechanism for **MMORPG material
  items** planned in later seasons — each node type will produce crafting
  materials for that system. Drop tables + event pools live in the same
  YAML content catalog so seasons can extend them without code.

## 6. Storage & management UX

### 6.1 Warehouse (virtual central storage)

- Each player has a **virtual Warehouse** — a central item store viewed
  through a GUI (paged). **Collect-all sends node buffers to the
  Warehouse**, not to player inventory.
- Warehouse has a **capacity** (item/stack count), expandable with Money —
  another money sink.
- Withdraw from Warehouse → player inventory via GUI; deposit likewise.
- Node buffer (per-node, small, stops production when full) → Warehouse
  (big, central) → inventory is the item flow.

### 6.2 GUI system — full design (player & admin)

All GUIs are chest-inventory menus (paged where needed). Shared
conventions: bottom row = navigation (back / close / page arrows);
green = confirm, red = destructive with a confirm click; every GUI
action has a command fallback. GUI layer is presentation only — every
click routes to the same service methods the commands use.

#### Player menu tree

```
/idle  →  Main Hub
├── Nodes            (list, paged)
│   └── Node Detail
│       ├── Worker Slots      insert/eject worker items per slot
│       ├── Collect           buffer → Warehouse
│       ├── Tier Upgrade      cost preview → confirm
│       ├── Exploration       events available/running, send team, claim loot
│       └── Convert Type      cost + "exploration level halved" warning
├── Territory Map     chunk-grid view of the blob; claim/unclaim from map
├── Warehouse         paged item store; withdraw/deposit; expand capacity
├── Workers
│   ├── Hire (gacha)          cost + odds display → roll animation → result
│   └── Fuse                  place N same-rarity contracts → preview → fuse
├── Trust             list trusted players, set level (Visitor/Helper/Manager)
├── Boosters          active boosters + purchase
├── Leaderboard       money/production/exploration tops
└── Profile           balance, streak, node cap, playtime
```

- **Main Hub** (`/idle` with no args once GUIs land): 9x3, one icon per
  section, live counters in lore (balance, buffer alerts, event ready).
- **Node Detail** is the workhorse: worker slots rendered as head items
  (drag a contract in = assign, click out = eject), buffer bar, state
  icon mirroring NPC state, exploration level + bracket progress.
- **Territory Map**: each chunk = one pane item, color by node type,
  center = player position; click empty adjacent chunk → claim flow
  (type picker → cost confirm); click owned chunk → jump to Node Detail.
- **Gacha roll** shows a short slot-machine animation before revealing
  rarity (pure presentation; roll already resolved server-side).

#### Admin menu tree

```
/idle admin gui  →  Admin Hub
├── Drop Pool Editor   per node type (per bracket after Phase 5)
│   • real items shown in a chest grid, weight in lore
│   • drag item in = add to pool; drag out = remove
│   • L/R click = weight ±1, shift = ±10; close = write YAML + live apply
├── Schem Definitions  list definitions; per definition:
│   • anchors viewer (armor-stand markers toggled in-world)
│   • bind animation profiles per state from a profile list
│   • rebuild/paste preview at current node
├── Node Inspector     info of the node stood in: owner, tier, workers,
│                      buffer, exploration; force-state buttons (preview);
│                      force-unclaim (double confirm)
├── Economy            give/take money & items via player picker;
│                      cap editor per player
├── Balance            live-edit key numbers (rates, costs, odds) with
│                      bounds; writes YAML + reload
└── Audit Browser      paged recent audit-log entries, filter by player/action
```

- Admin GUIs write back to the same YAML files (config/schematics) the
  file-based workflow uses — the two editing paths never diverge.
- Every admin GUI mutation is recorded in the audit log.

#### Build order

GUI layer lands as its own phase after Warehouse (needs WarehouseService
for collect-all), starting with Node Detail + Main Hub, then Territory
Map, then the Admin Hub (Drop Pool Editor first — highest balance-tuning
value). Commands remain first-class and are built before their GUI in
every phase.

### 6.3 Territory visualization

- **Claim border visuals in-world**: particle/glowing outline of claimed
  chunk borders (toggleable, shown on claim mode / near borders).
- **Territory map in GUI**: a chunk-grid map view of the player's blob —
  which chunk is which node type/tier — used as the claim/manage surface.

## 6b. Unclaiming

Unclaim is allowed but designed so territory churn has real cost:

1. **Contiguity guard** — a node cannot be unclaimed if removing it would
   split the remaining territory into disconnected pieces (BFS check).
   The Residential Node can only be unclaimed last (i.e. abandoning the
   whole territory).
2. **Buffer must be empty** — the player must collect the node's storage
   before unclaiming (simple, no dupe/loss edge cases).
3. **Workers return to roster** — assigned workers are unassigned, kept by
   the player (they are player-owned units, never destroyed by unclaim).
   Any in-progress Exploration Event on that node is cancelled (no loot).
4. **Terrain restore** — at claim time we snapshot the chunk's blocks
   before pasting the building; unclaim removes the building and restores
   the snapshot.
5. **Partial refund** — refund a configurable % of the *claim* cost
   (default 50%). Tier upgrade spend is **not** refunded.
6. **Exploration Level is lost** — it is per-node investment; this is the
   real deterrent against claim-shuffling for quick RNG. A confirmation
   dialog states exactly what will be lost.

## 7. Protection & trust

- Claimed chunks (Residential + Production) are fully protected:
  no break/place/container-access/interact by others; explosion-proof.
- **Trust system, 3 levels** (`/idle trust <player> <level>`, revocable):
  - **Visitor** — may enter the territory (bypasses none of the build
    protection); baseline for anyone if the owner allows entry at all.
  - **Helper** — Visitor + collect node buffers to the *owner's*
    Warehouse, view node/territory GUIs read-only.
  - **Manager** — Helper + assign/eject workers, start Exploration
    Events. Owner-only, never delegable: claim, unclaim, convert, tier
    upgrade, cap purchase, Warehouse withdraw, trust management itself.
- Trust level is per-node-owner, applies across their whole territory
  (no per-node granularity at launch).

## 7b. Territory adjacency

- Claims are **first-come-first-served at the chunk level** — no
  mandatory buffer between different players' territories; territories
  may become directly adjacent. The only hard rule is a chunk can never
  be claimed by more than one player at a time.
- **Overworld only** at launch — Nether/End are vanilla exploration/travel
  space with no node/claim system. World scope is config-gated so a
  future season could enable additional worlds without a code change.

## 8. Money loop (already shipped in MVP)

- `PayoutTask` pays Money + vanilla EXP per interval to all online players
  (AFK included). Config-driven rates. This remains the sole Money faucet
  at launch; sinks are node claims/tiers/cap/plots.

## 8b. Additional v1 features

**Money / engagement**
- **Boosters**: timed multipliers (money rate / production rate), bought
  with Money or dropped from events. Stack rules + durations in YAML.
- **Daily login streak**: escalating Money bonus for consecutive days,
  resets on a missed day (grace configurable).

**Node QoL**
- **Node type conversion**: pay Money to switch a Production Node's type
  in place (no unclaim, no BFS/contiguity re-check needed). Requires the
  buffer to be empty first (same rule as unclaim). Tier is kept;
  **all assigned workers are ejected back to item form** (roster) —
  the player reassigns them to the reconfigured node afterward.
  Exploration Level is halved (configurable) — cheaper than
  unclaim+reclaim but not free.
- **Auto-collect upgrade** (endgame): expensive per-node unlock that
  periodically flushes the node buffer into the Warehouse automatically.

**Worker identity**
- **Generated name + random cosmetic skin** rolled at mint, shown on the
  item and the in-world NPC — gives traded workers personality/provenance.

**Exploration**
- **Global Expedition** (weekly): server-wide event. Player picks **one**
  of their own nodes to represent them and commits workers from that
  node's roster — a **separate commitment slot** from the node's regular
  Exploration Event, so both can run concurrently on the same node.
  Ranked by contribution (worker stats × count committed); leaderboard
  with seasonal rewards. Committed workers count as `EXPLORING` and are
  unavailable to baseline production for the expedition's duration
  (same opportunity-cost rule as regular events).

**Social**
- **Co-op territory**: delivered by the Helper/Manager trust levels in
  §7 — no separate mechanism needed.
- **Visit/showcase**: `/idle visit <player>` teleports to a visit anchor
  in their Residential Node (owner toggleable, visitors get no
  build/access rights); territory showcase board with top territories.

## 8c. Admin tooling (launch-required)

- **Territory management**: `/idle admin claims <player>` (list/inspect),
  `/idle admin forceunclaim <world> <x> <z>` — for ban/griefer/dispute
  resolution.
- **Direct economy edits**: `/idle admin give money|item <player> <amount>`
  — support & correction tool.
- **Schematic/NPC tools**: the `schem` authoring commands (§4.4), plus
  `/idle admin npc refresh|list` (respawn/inspect a node's NPCs),
  `/idle admin npc state <state>` (force a state to preview its
  animation profile), `/idle admin schem rebuild` (re-paste a damaged
  building without touching the terrain snapshot), and
  `/idle admin node info` (dump owner/tier/workers/state of the chunk
  the admin stands in).
- **Live balance reload**: `/idle admin reload` re-reads all balance YAML
  (rates, prices, gacha odds, drop tables, brackets) without a restart.
- **Audit log**: every Money spend, gacha/fuse roll, and exploration
  claim is written to an append-only log (file or DB table) with actor,
  timestamp, and outcome — for dupe/complaint investigation.

```
idlefarm_audit_log
  id                BIGINT AUTO_INCREMENT PK
  actor_uuid        VARCHAR(36) NOT NULL
  action            VARCHAR(32) NOT NULL   -- CLAIM | UNCLAIM | HIRE | FUSE | EXPLORE_CLAIM | ADMIN_GIVE | ...
  detail_json       TEXT
  created_at        TIMESTAMP NOT NULL
  INDEX (actor_uuid), INDEX (action)
```

## 9. Seasons

- **Content seasons, no resets.** Each season adds node types, resources,
  building schematics, cosmetics; player balances/claims persist.
- Season pass (later) can grant bonus Node Cap — hooks into `bonus_cap`.
- Content lives in data files (node catalog YAML + schematic files) so a
  season is mostly a data/asset drop, not a code rewrite.

## 10. Data model (MySQL)

```
idlefarm_players          (existing) uuid PK, name, balance, total_online_minutes

idlefarm_nodes
  id                BIGINT AUTO_INCREMENT PK
  owner_uuid        VARCHAR(36) NOT NULL
  world             VARCHAR(64) NOT NULL
  chunk_x, chunk_z  INT NOT NULL
  node_type         VARCHAR(32) NOT NULL   -- RESIDENTIAL | MINING | FARMING | WOODCUTTING | LIVESTOCK | HUNTER
  tier              INT NOT NULL DEFAULT 1 -- = worker count for production nodes
  state             VARCHAR(16) NOT NULL   -- ACTIVE | STORAGE_FULL | PAUSED
  last_tick_at      TIMESTAMP NOT NULL     -- lazy offline accrual anchor
  storage_json      MEDIUMTEXT             -- buffered uncollected items
  UNIQUE (world, chunk_x, chunk_z)
  INDEX (owner_uuid)

idlefarm_node_cap
  owner_uuid        VARCHAR(36) PK
  base_cap          INT NOT NULL
  bonus_cap         INT NOT NULL DEFAULT 0 -- purchases / rank / season pass

idlefarm_trust
  owner_uuid        VARCHAR(36)
  trusted_uuid      VARCHAR(36)
  level             VARCHAR(16) NOT NULL   -- VISITOR | HELPER | MANAGER
  PRIMARY KEY (owner_uuid, trusted_uuid)

idlefarm_workers
  -- Row exists for every worker ever minted; identity uuid also lives in
  -- the worker item's PDC. Unassigned workers exist as items in the world
  -- (inventories/chests); assigned workers are consumed items bound here.
  worker_uuid       VARCHAR(36) PK         -- stamped into the item PDC at mint
  rarity            VARCHAR(16) NOT NULL   -- COMMON..LEGENDARY (rolled at hire/fuse)
  trait             VARCHAR(24) NOT NULL   -- growth-bias personality (rolled at mint)
  stats_json        VARCHAR(255) NOT NULL  -- {diligence, luck, stamina, speed}
  skin              VARCHAR(64)            -- cosmetic skin id (rolled at mint)
  level             INT NOT NULL DEFAULT 1
  exp               BIGINT NOT NULL DEFAULT 0
  assigned_node_id  BIGINT NULL            -- FK idlefarm_nodes, NULL = item-form
  state             VARCHAR(16) NOT NULL   -- ITEM | WORKING | IDLE | STOP | EXPLORING
  name              VARCHAR(32)            -- generated/cosmetic
  INDEX (assigned_node_id)

idlefarm_warehouse
  owner_uuid        VARCHAR(36) PK
  capacity          INT NOT NULL           -- expandable with Money
  content_json      LONGTEXT               -- paged item store

idlefarm_exploration_events
  id                BIGINT AUTO_INCREMENT PK
  node_id           BIGINT NOT NULL        -- FK idlefarm_nodes
  event_type        VARCHAR(32) NOT NULL   -- from bracket pool (YAML catalog)
  state             VARCHAR(16) NOT NULL   -- AVAILABLE | RUNNING | COMPLETED | EXPIRED | CLAIMED
  spawned_at        TIMESTAMP NOT NULL
  expires_at        TIMESTAMP NOT NULL     -- pauses while owner offline
  started_at        TIMESTAMP NULL
  ends_at           TIMESTAMP NULL         -- run completion (Speed-adjusted)
  worker_uuids_json VARCHAR(512)           -- committed team
  outcome_grade     VARCHAR(16) NULL       -- NORMAL | GREAT | JACKPOT (Luck-shifted)
  loot_json         TEXT                   -- rolled at completion, claimed to warehouse

-- idlefarm_nodes also gains:
--   exploration_level INT NOT NULL DEFAULT 0
--   exploration_exp   BIGINT NOT NULL DEFAULT 0
```

## 11. System components (implementation map)

| Component | Responsibility |
|---|---|
| ClaimService | claim/unclaim, contiguity + cap validation |
| WorkerService | hire (gacha roll), worker-item mint/consume/eject, fuse, level/exp growth |
| ExplorationService | start/complete runs, exploration level, loot rolls |
| WarehouseService | virtual storage, capacity expansion, deposit/withdraw, auto-collect |
| TerritoryMapService | chunk-grid GUI map + in-world border particles |
| BoosterService | timed multipliers, stacking rules |
| StreakService | daily login streak tracking/rewards |
| EventService | global weekly expeditions, contribution leaderboard |
| VisitService | visit anchors, showcase board |
| TrustService | 3-tier trust (Visitor/Helper/Manager), permission checks |
| AdminService | claim inspect/force-unclaim, economy edits, config reload |
| AuditService | append-only action log |
| ProtectionListener | block/interact/explosion guards + trust checks |
| ProductionEngine | rate math, lazy accrual, buffer/state transitions |
| SchematicService | paste building on claim, upgrade variants per tier |
| WorkerManager | NPC spawn/despawn per loaded chunk, state display |
| NodeGui | /idle nodes menu, collect-all, claim/upgrade flows |
| CapService | base+bonus cap, purchase flow |
| Existing: PlayerDataStore / PayoutTask / IdleCommand | Money economy |

## 12. Remaining open items (decide during build, non-blocking)

1. Schematic tooling — bundle a lightweight schematic paster vs. depend on
   WorldEdit/FAWE for pasting (recommend FAWE soft-dependency).
2. NPC implementation — Citizens dependency vs. lightweight custom
   entities (recommend custom: only 3 states needed, avoids heavy dep).
3. Warehouse-full behavior on collect-all (partial collect + warning
   recommended), and worker-item dupe safeguards (item is authoritative
   token; DB state must reject double-assign of the same worker_uuid).
3b. Fuse recipe/odds table (how many of rarity X → chance of X+1).
4. Exact base rates / prices / cap numbers / gacha rarity odds /
   exploration drop tables — balance YAML, tunable live.
5. Exploration balance numbers — bracket thresholds, event spawn/expiry
   windows, run durations, grade odds curves — balance YAML.
6. Terrain snapshot storage format/size for unclaim restore (per-chunk
   block snapshot vs. regenerate-from-seed for untouched chunks).
7. Anti-abuse (macros, alts) — explicitly post-launch.
8. Audit log retention/rotation policy (unbounded growth over time).
9. Whether admin `forceunclaim` refunds the player or is a pure seizure
   (recommend: no refund — it's a moderation action, not a service).
