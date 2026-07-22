# Building Placement and Skins

## 1. Purpose

This document is the authority for how a node's building is chosen, oriented,
and written into the world. It covers four systems that share one code path:

| System | Question answered |
|---|---|
| Placement | Where does the building sit, and what was already there? |
| Preview | What will the player see before committing? |
| Rotation | Which way does the building face? |
| Skins | Which blueprint does this specific node use? |

These are documented together because they are not separable: a skin changes
the footprint, the footprint changes placement, rotation changes both, and the
preview must show the result of all three before the player pays.

## 2. Vocabulary

| Term | Meaning |
|---|---|
| Definition | One blueprint (`schematics/<id>.yml`): blocks, anchors, profiles |
| Skin | A named family of definitions, one per tier, selectable per node |
| Footprint | The XZ area the definition's blocks occupy |
| Origin | The world position `(dx,dy,dz) = (0,0,0)` maps to |
| Snapshot | The pre-build blocks recorded so unclaim restores the terrain |

A definition is a blueprint. A skin is a *choice between* blueprints. One node
uses exactly one skin, and that skin supplies a different definition at each
tier.

## 3. Placement

### 3.1 Origin

The origin is the chunk-centre column at the node's stored `origin_y`.
`origin_y` is captured once at claim time and never recomputed, so later
terrain edits never move an existing building.

### 3.2 Ground selection

Ground Y is chosen by sampling, not by a single column.

1. Sample every column of the definition's footprint on a 2-block grid,
   always including the four corners and the centre.
2. For each column, start at `MOTION_BLOCKING_NO_LEAVES` and walk down past
   vegetation, non-solid blocks, and liquids until a solid block is found.
3. Ground Y is the **median** of the samples, plus one.

The median is used rather than the mean, the max, or the centre column:

- The centre column alone is what causes buildings to sit on tree canopy.
- The max floats the building over a single pillar or a lone tree.
- The mean is dragged by one deep ravine cell.

The median tolerates a minority of outlier columns, which is the common case
on natural terrain.

### 3.3 Obstruction classes

Every block inside the build volume is classified before placement:

| Class | Examples | Handling |
|---|---|---|
| Free | Air, cave air | Ignored |
| Vegetation | Leaves, logs, grass, flowers, crops, snow, vines | Cleared automatically, recorded in the snapshot |
| Liquid | Water, lava | Cleared automatically, recorded in the snapshot |
| Solid | Stone, player builds, ore | Reported to the player; never auto-cleared |

Vegetation is auto-cleared because it is the overwhelmingly common obstruction
and because clearing it is fully reversible: the cleared blocks enter the same
snapshot the building uses, so unclaiming restores the trees exactly. Asking a
player to hand-clear a forest before every claim is friction with no upside.

Solid blocks are never auto-cleared, because they may be another player's
build, and because a claim that silently eats terrain is indistinguishable
from griefing. They are surfaced and the player decides.

### 3.4 Build volume

The build volume is the definition's bounding box, extended upward by
`nodes.placement.clear-headroom` blocks so overhanging canopy is removed
rather than left floating over the roof.

## 4. Preview

Preview is a *client-side* rendering. No block in the world is modified until
the player confirms.

- Ghost blocks are sent with `sendMultiBlockChange` to the previewing player
  only. Packets, not entities: a tier-5 building is several hundred blocks and
  block-display entities cost far more than one multi-block-change packet.
- Solid obstructions are marked with a distinct ghost block plus particles.
- The footprint perimeter is outlined with particles so the preview reads as a
  preview and not as a finished building.
- A preview refreshes periodically, because a chunk reload restores the real
  blocks on the client.

### 4.1 Confirming

The confirm action is a chat link carrying a single-use session token
(`/idle claim confirm <token>`). This is the Tier B exception documented in
`UI_FLOW_AND_CHAT_ACTIONS.md` §3.4b: a stale click matches no live session and
charges nothing.

### 4.2 Session rules

- One preview session per player.
- A session ends on confirm, cancel, timeout, world change, or quit.
- Ending a session always resends the real blocks.
- Confirm **re-runs full validation**. The chunk may have been claimed, the
  balance spent, or the terrain changed while the preview was open. The
  preview is a display, never an authorization.

### 4.3 Surfaces that reuse preview

Preview is one service with four callers: claim, rotate, change skin, and
admin review of a contest submission. This is why it is built before rotation
and skins rather than after.

## 5. Rotation

Rotation is stored per node as a quarter-turn count, `0`–`3`.

- Coordinates rotate `(dx, dz) -> (-dz, dx)` per step.
- Block states rotate with `BlockData#rotate`, so stairs, doors, and signs
  face correctly without per-material handling. The definition's parsed data
  is cloned first, because that call mutates in place.
- Spawn anchors and work anchors rotate with the blocks. Every
  relative-to-world conversion goes through one function so no caller can
  forget.

The transform lives in `schematic/Rotation.java`, and definition entries are
parsed in exactly one place (`SchematicService#resolveBlocks`). Pasting, the
upgrade animation, the survey, and the preview all consume that one list, so
they cannot disagree about where a rotated building lands.

### 5.1 When rotation is allowed

Rotation is allowed after placement, not only at claim time, subject to:

- The node is not mid-upgrade.
- The player confirms via preview.
- A fee or cooldown applies, because rotation restores and re-pastes the whole
  building.

Rotating an already-placed building reuses the same sequence as a skin change
and a type conversion: restore terrain, mutate the record, rebuild, refresh
NPCs, clear custom worker anchor overrides. This is one shared operation, not
three similar ones.

Cost is `nodes.rotate-cost` (0 disables the charge). Rotation is offered from
the claim preview before the first placement, and from `/idle rotate` while
standing on an owned node afterwards.

### 5.2 Obstructions when re-orienting

A placed building stands inside its own build volume. The survey therefore
ignores the positions the current building occupies: those blocks come down
before the new orientation goes up, and counting them would paint the whole
preview red.

Known gap: the terrain snapshot is restored under the old building before the
rebuild, and the survey does not model that restored ground. A hillside that
the original placement carved through is not re-counted as an obstruction. The
rebuild handles it the same way the first placement did — vegetation clears,
solid ground does not — so the result is correct; only the preview's count
can under-report in that case.

### 5.3 Asymmetric definitions

A definition whose bounding box is not centred on the origin will shift when
rotated, and may cross into a neighbouring chunk. Capture normalizes the
bounding box to be origin-centred, and warns when it cannot.

## 6. Skins

### 6.1 Model

```yaml
# plugins/Idle/skins/cozy_cottage.yml
id: cozy_cottage
display: "กระท่อมอบอุ่น"
author: <uuid>
author-name: "PlayerName"
node-types: [MINING, FARMING]   # empty = any
tier-1: cozy_cottage_t1         # the only required entry
tier-3: cozy_cottage_t3         # optional; tier 2 keeps the tier-1 building
unlock: contest_s1
winner-variant: cozy_cottage_gold   # optional, granted to the author only
```

**Decision: one building serves every tier.** A submission provides `tier-1`
only, and the node keeps that building from T1 to T5. Tier lookup still falls
back downward, so a later seasonal skin may add per-tier variants without any
code change, but nothing requires it.

Requiring five buildings per entry would cut the number of contest entries
hard for a reward players get almost none of the value from — tier is already
legible from the upgrade animation, the node GUI, and the hologram. If tier
differentiation is wanted later, it is a separate contest ("submit a T5
upgrade for an existing skin"), not a barrier to the first one.

### 6.2 Storage

| Table | Holds |
|---|---|
| `idle_nodes.skin_id` | Which skin this node currently displays |
| `idle_player_skins` | Which skins a player has unlocked |

These are separate because unlocking and using are separate events. A player
unlocks a skin once and may apply it to any number of nodes.

`skin_id` is nullable; null means the server default from `config.yml`. A skin
file that disappears falls back to the default rather than failing the build,
so removing a seasonal skin never breaks an existing node.

### 6.3 Selection policy

**Decision: contest-winning skins unlock server-wide.** The winner
additionally receives a distinguishing variant (`winner-variant`) that stays
exclusive, and permanent credit shown in the skin menu on every copy.

Locking a winning build to its author alone would mean almost nobody ever sees
it, which defeats the purpose of running a build contest. Credit is the
reward that build-contest entrants actually want; exclusivity of use is not.

Consequences to hold to:

- Author credit is stored in the skin file and shown wherever the skin is
  listed. It is not optional metadata — it is the prize.
- An approved skin is effectively permanent. Deleting one silently reverts
  every node wearing it to the default, so retiring a skin needs a deliberate
  path, not a file deletion.
- Because everyone can wear it, a submission's failure modes are everyone's
  problem. This is what justifies the validation in §7.1 being stricter than
  anything applied to admin captures.

## 7. Contest pipeline

**Decision: players build on their own node and author their own anchors.**
The submission flow is the admin authoring flow with guard rails, not a
separate one — the same capture, base-Y, spawn-anchor and work-anchor steps
that `AdminSchematicMenu` already exposes.

```
Player builds on a node they own
  -> /idle skin submit            starts a guided session on that chunk
     1. set base Y                the block level the building sits on
     2. set spawn anchors         where each worker slot stands
     3. set work anchors          where each worker slot works
     4. name it, confirm          capture + automatic validation
  -> pending queue                 rejection lists every failed rule
  -> admin review                  preview it, adjust anchors, approve/reject
  -> approve                       write skins/<id>.yml, grant unlock
```

Building on an owned node rather than in a dedicated contest world means no
new world to run, and the player composes against the real thing. The costs
are handled explicitly:

- **The capture is chunk-bounded.** Everything outside the node's own chunk is
  ignored, so a neighbour's wall cannot be captured into a submission.
- **Base Y is chosen by the player**, not inferred, because the surrounding
  terrain is theirs and a guessed ground level would slice the build.
- **The node's existing building is cleared first**, or the old hut ends up
  baked into the submission.

### 7.1 Automatic validation

Player-authored buildings are pasted into other players' worlds, so they are
validated far more strictly than admin captures.

**Rejected block classes**

| Class | Reason |
|---|---|
| Containers (chest, barrel, shulker, hopper) | Hide items; conflict with node storage |
| Spawners, command blocks, structure blocks | Server integrity |
| Liquids | Flow and destroy neighbouring terrain after paste |
| Beds, respawn anchors | Grant spawn rights inside another player's claim |
| TNT and other primeable blocks | Grief vector |
| Beacons | Area effects the owner did not choose |

Bed and shulker-box families are matched by material name suffix rather than
by block tag. Block tags resolve through the running server, which would make
the validator — the one place that must be provably correct — impossible to
unit test. Every vanilla bed ends in `_BED` and every shulker box ends in
`SHULKER_BOX`, so the match is exact.

**Limits**

| Limit | Value | Config key |
|---|---|---|
| Footprint | 15 × 15 | `skins.max-size` |
| Height | 100 | `skins.max-height` |
| Non-air block count | 6000 | `skins.max-blocks` |

Submissions are opened and closed with `skins.submissions-open`, so the
contest can be run as an event rather than being permanently live.

A submission made while standing on a merged Complex covers every chunk of it
and is validated per piece against a 16 × 16 footprint instead —
`NODE_MERGE_AND_COMPLEX.md` §11.1.

A rejected submission reports **every** broken rule at once. Fixing one rule
per capture would be miserable on a large build.

15 × 15 is the largest odd footprint that fits inside one chunk when centred
on the chunk's middle column (local 1 to 15). Odd matters: an even footprint
has no centre column, so it cannot rotate about the origin without shifting.

Height is capped separately at 100 — towers are allowed by design here, so the
block count does the real work of bounding cost. 6000 non-air blocks is
roughly a densely detailed 15 × 15 × 100 shell; it is a config value, so raise
it once the paste budget below is proven on the live server.

### 7.2 Consequences of allowing 100-tall builds

A 15 × 15 × 100 volume is 22,500 positions, against the 75 of the default hut.
Three things stop being optional at that size:

1. **Budgeted paste.** Placing 6000 blocks in one tick is a visible stall.
   Writes spread across ticks at `nodes.paste-budget-per-tick` blocks each.
   Jobs at or under that size still run synchronously, so an ordinary hut
   keeps its immediate behaviour and gains no latency.

   Site clearing, structural blocks, and attachables go into **one ordered
   work list** rather than three jobs. Order is what makes a door land on a
   wall that exists, and three independently budgeted jobs would interleave.

   Anything that must wait for a finished building — spawning workers,
   refreshing NPCs, rebuilding after a restore — runs from the completion
   callback. A chunk with a job in flight is marked busy, and claim,
   unclaim, convert, rotate, and reskin all refuse while it is: an unclaim's
   restore finishing after a re-claim's build would drop old terrain on top
   of the new building.
2. **Chunked preview.** One `sendMultiBlockChange` carrying thousands of
   positions is a large packet per refresh. The preview sends in batches and
   refreshes on a slower cadence for large blueprints.
3. **Build-limit truncation is now reachable.** A 100-tall building placed on
   a mountain at Y 250 runs past the world ceiling. Per-block clamping already
   prevents corruption, but silently losing the top 30 blocks is not
   acceptable feedback: the claim preview reports the overflow before the
   player confirms, and submission warns if the build cannot be placed at
   common ground levels.

### 7.3 Manual review is still mandatory

No submission is auto-approved, even though the player now supplies the
anchors. Automatic validation catches block-level abuse; it cannot judge
whether a shape traps NPCs, walls in a neighbour, or is simply offensive — and
a 100-tall tower makes the "walls in a neighbour" case considerably more
likely than the original design assumed.

Review is lighter than before because the anchors arrive already set: the
admin verifies and adjusts rather than authoring from scratch.

The queue is `/idle admin skinreview`. `show` draws the submission as ghost
blocks where the reviewer stands — judging a build never requires pasting it
into the world — and the reviewer can rotate it, re-place any anchor by
standing where the worker should be, then approve or reject with a reason.
The author is told the outcome, including the rejection reason, so a
resubmission can actually fix the problem.

Approval writes the blueprint as a schematic and the skin file pointing at
it, with `unlock: default` — that is the mechanism that makes a winning build
available server-wide, per §6.3.

Preview sessions carry the flow they belong to (claim, rotate, review), and
each confirm handler accepts only its own kind. Without that, a review token
sitting in an admin's chat could be spent by the claim handler.

### 7.4 Anchors

Anchors are captured relative to the submission's base Y and chunk centre, so
they travel with the build to every node that later wears the skin. A
submission with no anchors is accepted — workers fall back to an auto-layout
line in front of the building — but flagged in review, because that fallback
can place an NPC inside a wall.

## 8. Delivery phases

| Phase | Scope | Status |
|---:|---|---|
| 0 | Per-node blueprint resolution: `skin_id`, `rotation`, one lookup entry point | Done |
| 1 | Sampled ground selection, vegetation auto-clear, obstruction reporting | Done |
| 2 | Preview service and confirm flow | Done |
| 3 | Rotation, and the shared rebuild operation | Done |
| 4a | Skin core: registry, unlocks, resolution, selection | Done |
| 4b | Submission capture and automatic validation | Done |
| 4c | Admin review queue | Done |
| 4d | Budgeted paste for large blueprints | Done |
| 5 | Upgrade-wait animation | Done (§9.1 lists two layers deliberately skipped) |

Phase 0 precedes everything because blueprint lookup moves from
server-wide to per-node. Doing it late means revisiting every call site a
second time.

Phase 1 ships before preview because the misplacement bug it fixes is live.

## 9. Upgrade-wait animation

An upgrade takes `build-seconds-base × tier` seconds, during which nothing
visibly happened. Shipped:

1. A text display above the building showing target tier, a progress bar,
   percentage, and remaining time.
2. Construction particles and sound every `nodes.upgrade-site.effect-seconds`.

Both are derived state. The display is a non-persistent entity rebuilt each
tick from `upgrade_ends_at`, and no world block is touched, so a crash or a
restart cannot leave anything to clean up. Progress uses
`ClaimService#buildSeconds` — the same call that set the deadline — rather
than recomputing the formula, so the bar cannot drift from the schedule.

The completion animation already existed: the new building rises bottom-up,
one Y layer per interval.

### 9.1 Two layers deliberately not built

**Scaffolding around the site.** It is the only layer that writes real blocks,
and those blocks cannot go in the terrain snapshot (they are decoration, not
terrain — invariant 3). A crash mid-upgrade would strand them with nothing
recording that they exist. Revisit only with a material-checked, deterministic
sweep on startup.

**A worker NPC "building" animation state.** Production deliberately keeps
running at the old tier during an upgrade, so the workers are busy doing their
actual jobs. Repurposing their animation state would show them constructing a
house while their output says they are mining. A dedicated builder NPC would
be honest, but that is a new entity, not a reuse of the state machine.

## 10. Invariants

1. `origin_y` is captured at claim and never recomputed.
2. Every block the plugin overwrites is in the node's snapshot, including
   auto-cleared vegetation and liquids.
3. Scaffolding and other temporary decoration are never in a snapshot.
4. Solid obstructions are never cleared without an explicit player decision.
5. A preview never writes to the world and never authorizes anything.
6. Confirmation re-validates from scratch.
7. Every relative-to-world conversion applies the node's rotation.
8. A missing skin or definition falls back; it never fails a build.
9. Changing appearance (rotation, skin, type) always runs the same sequence:
   restore, mutate, rebuild, refresh NPCs, clear anchor overrides.
