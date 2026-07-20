# MMORPG Frontier — Phase 9

Phase 9 extends production nodes from level 101 to 200 while retaining their
vanilla output. Frontier supply is additive and is never required for levels
1–100.

## Material table

| Node | Lv.101 | Lv.126 | Lv.176 |
|---|---|---|---|
| Mining | Aether Ore | Mythril Fragment | Resonant Crystal |
| Farming | Mana Herb | Life Seed | Astral Pollen |
| Woodcutting | Spiritwood Resin | Ancient Bark | Living Fiber |
| Livestock | Mystic Hide | Beast Core | Primal Blood |
| Hunter | Soul Ash | Corrupted Essence | Void Fang |

Frontier share is 10% at 101–110, 20% at 111–125, 30% at 126–150,
40% at 151–175 and 50% at 176–200. Each material has an account-month cap
(`frontier.material-monthly-cap`) with an optional
`frontier.materials.<material>.monthly-cap` override. A capped roll is rerolled
into existing common output.

## Sinks

Each node type has a matching profession and three equipment tiers.

- `/idle frontier train <material> <amount>` is a permanent profession EXP
  sink and accepts all 15 Frontier materials in their matching profession.
- `/idle frontier craft <tier>` consumes that tier's material and installs
  node-bound equipment.
- Equipment loses durability during production.
- `/idle frontier repair` is the recurring short-term sink. Broken equipment
  stops granting its production bonus, but is not deleted.

Use `/idle frontier info` while standing in an owned production node to see
profession progress, recipes and durability.

## Staged rollout and rollback

All four `frontier.sink-gates` and `frontier.enabled` must be true before the
level cap or supply can open.

`live-ops.features.frontier` controls access to profession/crafting by stable
UUID cohort. `live-ops.features.frontier-drops` independently controls only
new supply. Both support `enabled`, `rollout-percent`, and `allowlist`.

To stop new drops immediately, set:

```yaml
live-ops:
  features:
    frontier-drops:
      enabled: false
```

Then run the normal local config reload. This changes only future drop-table
selection. Warehouse contents, profession EXP, and equipped item state are
stored independently and are not scanned, removed, or rewritten.
