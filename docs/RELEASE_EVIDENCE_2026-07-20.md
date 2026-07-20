# Release Evidence — 2026-07-20

## Automated engineering evidence

- Claim fault injection: a forced node-insert failure leaves both the claim
  and Coin charge absent before and after a database restart.
- Delivery commission fault injection: a forced game-state write failure
  rolls back the Warehouse withdrawal and leaves the commission claimable
  after restart.
- Personal project fault injection: a forced progress write failure rolls
  back the Warehouse withdrawal and leaves project progress unchanged after
  restart.
- MySQL integration coverage is opt-in through
  `IDLEFARM_TEST_MYSQL=true`. The suite verifies schema creation, restart
  persistence, and rollback across Warehouse/game-state rows. It still needs
  to be executed against the release MySQL instance.

## Five simulated focused-player cohorts

Command:

```text
node tools/simulate_release_cohorts.mjs
```

Each cohort contains 250 free players. The model uses the implemented
Lv.1–100 cost (71,280 EXP), the 300 starter-node grant, bounded passive
research, first collection, one Node-EXP commission, first/additional
expeditions, and weekly chapters. No paid boosts are counted. The engagement
assumption is 98% active days, so this is evidence for the design's
“focused player” target, not a forecast for all new accounts.

| Cohort | Players | Reached by day 30 | Median day | P10 | P90 | Median EXP at day 30 |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 250 | 6.8% | 31.0 | 31.0 | 33.0 | 69,497 |
| 2 | 250 | 6.4% | 32.0 | 31.0 | 33.0 | 69,492 |
| 3 | 250 | 4.8% | 31.0 | 31.0 | 33.0 | 69,486 |
| 4 | 250 | 6.4% | 31.0 | 31.0 | 32.1 | 69,591 |
| 5 | 250 | 6.8% | 32.0 | 31.0 | 32.0 | 69,383 |

Combined median is day 31, inside the 28–32 day target. The P90 tail reaches
day 33 in three cohorts, so the median gate passes but the slow-tail gate
does not. Before beta, verify whether the product target applies to the
median or to at least 90% of focused players; if it applies to P90, roughly
1,500–2,000 additional bounded EXP across the month is needed.

## Economy and resource sinks

The Balance Bible models 74,000 month-one Coin sources and 42,872 typical
sinks. That is a 57.9% source-to-sink ratio and +31,128 net Coins per typical
player in month one. This is intentional liquidity only if the remaining
balance is spent later on deep nodes, workers, or convenience. It is not
evidence that the live economy is non-inflationary.

Resource sink coverage is structurally present (delivery commissions,
preparation kits, personal/server projects, vanilla use, and gated Frontier
sinks), but a real sink ratio cannot be derived without production and
consumption telemetry. The beta gate should require the seven-day material
sink metric and investigate any sustained value below the configured 10%
alert threshold.

## Evidence still required

- Ten real new-player playtests, including time to first production, first
  collection, command usage, and observed confusion.
- Real local Paper verification of all four Block Display construction
  stages, restart/chunk unload-reload reconciliation, and relocation after
  unclaiming the oldest Residential anchor.
- Execution of the opt-in integration suite against the release MySQL
  version/configuration.
- At least seven days of economy telemetry to validate Coin velocity,
  material inflation, and actual resource-sink use.
