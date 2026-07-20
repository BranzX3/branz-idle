# Live Operations and Seasons

## 1. No-reset promise

Seasons never remove:

- Node Exploration Level.
- Tier, specialization, or discoveries.
- Workers and their growth.
- Warehouse/resources.
- Chronicle Points and permanent achievements.
- Purchased Credits/cosmetics.

Seasons add goals, resource demand, event variations, cosmetics, and post-100
content.

## 2. Twelve-week season structure

| Weeks | Phase | Content |
|---|---|---|
| 1–2 | Discovery | New project, event family preview, catch-up |
| 3–5 | Development | Main commissions and server construction |
| 6 | Midseason | New event variant and economy review |
| 7–9 | Mastery | Harder optional achievements and project stages |
| 10–11 | Finale | Community capstone |
| 12 | Celebration | Results, cosmetics, next-season preview |

No seasonal objective requires daily attendance.

## 3. Weekly cadence

- Monday: new Weekly Node Chapter and Server Project stage.
- Wednesday: rotating event modifier.
- Friday–Sunday: Global Expedition window/bonus participation.
- Sunday: project recap and next-week preview.

Use Asia/Bangkok time consistently and show absolute end times.

## 4. Rotating event modifiers

Modifiers change decisions without invalidating builds:

- Long Routes: Speed matters more.
- Unstable Veins: higher quantity variance, same expected value.
- Research Week: Stamina rewards increase.
- Mixed Crews: diverse-role synergy bonus.
- Supply Shortage: preparation kits are more efficient.

Modifiers never secretly lower baseline rewards.

## 5. Seasonal Chronicle

Seasonal achievements award:

- Seasonal Chronicle Points on a separate track.
- Cosmetics, façade variations, titles.
- Project lore and display items.

They do not award permanent production power.

Permanent Chronicle records which seasons were participated in, but incomplete
season pages do not block permanent completion.

## 6. Catch-up

- Weekly Chapters remain claimable until season end.
- Missed daily Node EXP enters catch-up at 50%.
- Returning after seven days grants three simplified commissions.
- Nodes below Lv.100 receive up to 48 hours Rested Research.
- No purchase is required to access catch-up.

## 7. Post-100 rollout

Do not enable all MMORPG materials at once.

Release gate:

1. Professions/crafting sink is live.
2. At least one equipment or utility use is live.
3. Enhancement/repair sink is live.
4. Project sink is configured.
5. Drop supply simulation passes.
6. Admin rollback and item audit exist.

Rollout:

- Season A: Frontier I at Node 101–125.
- Season B: Frontier II at 126–175.
- Season C: Frontier III at 176–200.

Players above a newly activated threshold receive access immediately; no
progress is reset.

## 8. Global Expedition

The Global Expedition should reward participation bands as well as ranks.

Rewards:

- Participation threshold: fixed badge/Coins.
- Contribution milestones: project cosmetics.
- Top ranks: additional title/visual, modest Coins.
- No exclusive resource or power for rank one.

Score should use:

- Worker/team contribution.
- Diminishing returns on repeated commits.
- Diversity bonus.
- Weekly account cap.

This prevents 24/7 repetition from becoming the only competitive strategy.

## 9. Events and announcements

Communication hierarchy:

- Action bar: micro feedback.
- Hub badge: waiting/completed content.
- Chat: major milestone and one-time warnings.
- Broadcast: server project stage, season opening/finale.
- External channel: schedules and patch notes.

Do not broadcast routine gacha outcomes or every level.

## 10. Economy review

Review weekly:

- Coin faucet/sink ratio.
- Median Coin balance by node milestone.
- Resource production/consumption ratio.
- Warehouse stock growth.
- Rare cap utilization.
- Direct trade volume.
- Exploration level velocity.

Change order:

1. Fix bugs/duplication.
2. Add or tune sinks.
3. Tune caps.
4. Tune weights.
5. Tune production rate last.

Avoid reducing earned inventory after players have planned around it.

## 11. Telemetry dashboard

### Progression

- Time to first production/collection/event.
- Node Lv.10/25/50/75/100 by type.
- EXP source distribution.
- Focus changes.
- Rested/catch-up usage.

### Economy

- Coins created/spent.
- Credits purchased/spent/refunded.
- Hybrid Pay use.
- Items produced/consumed/traded.
- Rare-cap hits.

### Engagement

- Sessions/day and session length.
- Event acceptance/completion.
- Commission completion/reroll.
- Achievement pin/completion.
- Project participation.
- D1/D7/D30 retention.

### Safety

- Duplicate worker UUID attempts.
- Repeated idempotency keys.
- Suspicious trade networks.
- Admin adjustments.
- Payment callback failures.
- Warehouse/event claim failures.

## 12. Experiment rules

A/B tests may change:

- Copy.
- Layout.
- Recommendation order.
- Notification timing.

Do not A/B test different:

- Paid prices for otherwise identical players without legal review.
- Gacha odds.
- Rare drop odds.
- Permanent reward values.
- Free versus paid power ceilings.

Balance tests run on simulation/staging before production.

## 13. Content production template

Every seasonal addition includes:

- Player fantasy and target level.
- Source and sink.
- Drop cap/weight.
- Event/commission/achievement integration.
- UI icon/lore and Thai copy.
- Admin validation.
- Analytics events.
- Rollback plan.

Content without a sink or rollback plan does not ship.

