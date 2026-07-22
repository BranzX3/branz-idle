# Node Merge and Complexes

## 1. Purpose

One chunk is 16 × 16. That is a house, not a factory. A Complex lets a player
spend adjacent **Residential** nodes as building space for a single
**Production** node, so one building can span up to 47 × 47.

This document is the authority for how Complexes form, render, rotate, and
come apart. It builds on `BUILDING_PLACEMENT_AND_SKINS.md`, which owns
placement, preview, rotation, and skins for a single chunk.

## 2. The model

**A Complex is one Production node plus N Residential nodes.**

Only the Production node produces. The Residential nodes contribute their
chunk as floor space and nothing else.

| Shape | Chunks | Production | Residential | Building area |
|---|---:|---:|---:|---|
| 1×1 | 1 | 1 | 0 | 15 × 15 |
| 2×1 | 2 | 1 | 1 | 31 × 15 |
| 3×1 | 3 | 1 | 2 | 47 × 15 |
| 2×2 | 4 | 1 | 3 | 31 × 31 |
| 3×2 | 6 | 1 | 5 | 47 × 31 |
| 3×3 | 9 | 1 | 8 | 47 × 47 |

### 2.1 Why this shape of design

The obvious alternative — merge N Production nodes into one — was rejected.
It multiplies everything the economy is balanced around: per-node rare caps,
Silo throughput, exploration event rate, worker NPC count. A 3×3 of Production
nodes is nine times the rare-resource ceiling for one building.

Spending Residential nodes instead costs land and Coins while leaving the
producing side untouched. A player with five 3×3 Complexes still owns exactly
five producing nodes.

This is what makes an uncapped number of Complexes safe, and it is the single
property the rest of this document protects.

## 3. Balance neutrality

**A Complex must never change production.** No output bonus, no buffer bonus,
no worker slot, no exploration modifier, no rare-cap change. Its entire reward
is that the building is bigger and better looking.

Three things depend on this and break the moment it is violated:

1. **Rare caps stay per-node.** They only remain safe because merging does not
   add producing nodes.
2. **Residential may be sold for Credits.** Design pillar 7 forbids Credits
   from raising the power ceiling. Residential is legal to sell only while it
   is pure building space. Attach any output to a Complex and Residential
   becomes power, and the sale becomes pay-to-win.
3. **Complex count needs no cap.** Price, land, and time are the only limits
   required, because more Complexes never means more production.

A Complex is a **Coin sink that returns no power**, which is the scarcest and
most valuable thing a late-game idle economy can have. That is the design
intent, not a limitation to be worked around later.

## 4. Forming a Complex

Merging is always **opt-in, per node, and reversible**. Residential is where
players build for themselves; the system may never absorb one silently and
paste over what they made.

```
Stand on the Production node
  -> /idle merge            offers every shape the surrounding land supports
  -> pick a shape           2×1 / 3×1 / 2×2 / 3×2 / 3×3
  -> pick a skin            only skins authored for that shape
  -> preview + rotate       the existing PreviewService, spanning chunks
  -> confirm                pay, snapshot every chunk, paste
```

### 4.1 Eligibility

A shape is offered when, for some placement of the shape's rectangle over the
chunk grid:

- The Production node is one of the covered chunks.
- Every other covered chunk is a **Residential node owned by the same player**.
- No covered chunk already belongs to another Complex.
- The Production node is not mid-upgrade.

Ownership of the Residential chunks is the whole condition. There is no
separate unlock ladder: owning eight adjacent Residential plots *is* the
achievement.

### 4.2 Anchor and piece assignment

The Production node is the Complex's **anchor**. The skin's layout is defined
relative to the anchor's position within the shape, so a skin declares which
cell the Production piece occupies.

Each covered chunk renders its own piece, through the existing per-node paste.
This is why merging needs no new world-writing machinery: a Complex is N
ordinary buildings that happen to line up.

Complex pieces fill the full **16 × 16** of their chunk. The 15 × 15 limit for
single-chunk skins exists so a building can rotate about its chunk's centre
column; a Complex rotates about the Complex centre instead, so its pieces have
no such constraint and must not leave a 1-block seam.

### 4.3 Ground level

Every chunk in a Complex uses the **anchor's `origin_y`**. A Complex that
derived ground per chunk would step up and down a slope and tear the building
apart.

Residential nodes currently store `origin_y = 0` and never receive a building.
Joining a Complex gives a Residential node a real `origin_y` copied from the
anchor, and its own terrain snapshot.

## 5. Rotation

A Complex rotates as one unit, about the centre of its shape.

- **Square shapes** (1×1, 2×2, 3×3) rotate all four ways.
- **Non-square shapes** (2×1, 3×1, 3×2) rotate **180° only**. A 3×1 turned 90°
  would need a 1×3 arrangement of chunks, which the player does not own.

Rotating from any member redirects to the anchor, and the new orientation is
written to **every** member: each one turns its own piece about its own chunk
centre.

### 5.1 The pieces move around each other

Turning each piece in place is only half of the rotation. Chunk centres are
themselves symmetric about the Complex centre, so the pieces must also swap
positions — a 2×1 lodge turned 180° puts the western piece in the eastern
chunk. Rotating without that swap leaves the door on the wrong end and the
open seam facing outwards.

`ComplexShape#authoredCell` maps a chunk's physical cell to the piece
authored for it at a given rotation, and is the single place that mapping
lives: the live building, the rotation preview, and the merge preview all use
it. The test suite asserts it is a permutation of the cells at every turn, so
no piece can be dropped or drawn twice.

The rotate button steps by `4 / allowedRotations`, so a long Complex jumps
straight from 0° to 180° and never offers an orientation its land cannot
take. The service re-checks the rule on confirm regardless.

## 6. Coming apart

`/idle unmerge` restores every covered chunk from its own snapshot, returning
the Residential plots to plain building space with whatever the player had
built there intact.

A Complex also comes apart automatically when any covered chunk stops
qualifying — a Residential plot is unclaimed, sold, or transferred. The break
is graceful: the whole Complex reverts, and the Production node falls back to
its single-chunk building.

Because a break can rewrite up to nine chunks at once, both merge and unmerge
must use the budgeted paste (§8).

## 7. Caps and pricing

| Limit | Value | Reason |
|---|---|---|
| Production cap | 4–6 | Unchanged; Complexes do not consume it |
| Residential cap | ~45–50 | Five 3×3 Complexes need 40, plus free-build plots |
| Complex count | **uncapped** | Price, land, and time limit it |

Residential 1–3 are ordinary progression. Beyond that they may be bought with
Coins **or** Credits, because they buy building space and nothing else.

Merge cost scales with the shape's chunk count, and is the intended late-game
Coin sink.

## 8. Budgeted paste

A 3×3 Complex of 6000-block pieces is 54,000 blocks. Placing that in one tick
stalls the server.

The budgeted paste this needs already exists (`nodes.paste-budget-per-tick`,
`BUILDING_PLACEMENT_AND_SKINS.md` §7.2). Merge and unmerge extend it from one
chunk to the shape's chunks: the busy-chunk guard must cover **every** chunk
in the Complex, so a merge in flight blocks unclaiming any part of it.

A merge in progress shows the same construction effects as an upgrade.

## 9. Interaction with existing systems

| System | Effect of merging |
|---|---|
| Production, buffers, lanes | None — lives on the anchor node |
| Workers, NPC slots | None — slots still come from the anchor's tier |
| NPC anchors | Rotate and translate with the Complex |
| Exploration, perks, tier | None |
| Protection | Unchanged; every chunk was already fully protected |
| Territory split rule | Unchanged; Complex chunks are ordinary claims |
| Terrain snapshot | One per chunk, as today |
| Skins | A Complex skin is a skin whose layout spans a shape |

The short version: a Complex changes **what is drawn**, never what is
produced.

## 10. Invariants

1. A Complex contains exactly one Production node.
2. A Complex never modifies production, buffers, workers, or rare caps.
3. Merging is opt-in per Residential node and always reversible.
4. Every chunk keeps its own terrain snapshot.
5. Every chunk in a Complex shares the anchor's `origin_y`.
6. A Complex piece may fill its chunk; a single-chunk skin may not exceed
   15 × 15.
7. Non-square Complexes rotate 180° only.
8. Losing any covered chunk reverts the whole Complex gracefully.
9. Merge, unmerge, and rebuild go through the budgeted paste.
10. Credits may buy Residential plots only while §3 holds.

## 11. Delivery

Complexes depend on the skin system, because a Complex skin is a skin. Order:

| Step | Scope | Status |
|---:|---|---|
| 1 | Skin core: registry, unlocks, selection (phase 4) | Done |
| 2 | Budgeted paste | Done |
| 3 | Complex detection, merge/unmerge, piece resolution | Done |
| 4 | Multi-chunk preview before confirming a merge | Done |
| 5 | Complex skin authoring and contest category | Done |

Step 3 shipped as `/idle merge` and `/idle unmerge`. Membership is one column
(`idle_nodes.complex_anchor`) holding the anchor's node id, and the shape is
derived from the members' chunks rather than stored — a stored shape could
drift out of step with the land actually held, and the land is the truth.

Piece resolution goes through the same `definitionFor(node)` entry point every
other building uses: a member resolves the anchor's skin, finds its own cell,
and renders that piece. A cell the skin leaves undefined renders nothing, so a
courtyard is authored by omission.

`/idle merge <shape>` opens a preview covering every chunk of the proposed
Complex before charging anything, confirmed by the same single-use token the
claim and rotate flows use (`UI_FLOW_AND_CHAT_ACTIONS.md` §3.4b).

A preview session holds a **list of parts**, one per chunk, rather than a
single blueprint; a single-chunk preview is simply a one-part list. Both the
ghost and the finished building resolve through the same `pieceForCell`, so a
preview cannot show something the merge would not produce.

### 11.1 Authoring a Complex skin

A Complex skin is submitted the same way a single-chunk one is: **merge first,
build across the whole space, then `/idle submit`**. Standing on a merged
Complex captures every member chunk as one design, sliced into a piece per
chunk.

Membership does the work that would otherwise need its own rules:

- It already proves the player owns every chunk, so there is no second
  ownership check to get wrong.
- Every member already shares the anchor's ground level, so the pieces line up
  by construction.
- The submitted `anchor-cell` records which cell held the Production node, so
  the layout can be reproduced on any Complex that later wears the skin.

Each piece is validated separately against a **16 × 16** footprint rather than
the single-chunk 15 × 15, because a Complex piece fills its chunk (§4.2). An
empty chunk is accepted — that is how a courtyard is authored — but a wholly
empty submission is rejected.

Anchors are stored once for the Complex and attached to the anchor cell's
piece, because that is the cell the workers actually stand in.

Review previews the whole composition: the pieces are laid out around the
reviewing admin's chunk in the same arrangement they would take on a real
Complex, so the judgement is made on the building rather than on nine
unrelated fragments.

### 11.2 The sample Complex

A code-generated 2×1 skin, `demo_lodge`, ships so merging can be exercised
before any player-authored Complex exists. Its pieces and skin file are
written only when missing, and `skins.install-demo: false` stops it coming
back once deleted.

It doubles as the fixture for the property every Complex skin must satisfy:
the west piece's `+7` column and the east piece's `-8` column are
neighbouring world blocks, so both must leave that edge open or the two
halves become separate rooms. The test suite asserts exactly that, along with
the pieces filling their chunks and the anchors landing inside the building
rather than in a wall.

### 11.3 Merging before a Complex skin exists

A Complex whose anchor wears no Complex-shaped skin renders the anchor's
ordinary building and leaves the Residential plots clear. It does **not** fall
through to per-node resolution, which would put a default hut on every plot.

The player has bought the space; what fills it is a separate choice.
