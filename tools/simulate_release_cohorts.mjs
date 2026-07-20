// Deterministic release-gate simulation for focused free-player cohorts.
// Uses bounded rewards implemented in config.yml; no paid boosts are counted.

const COHORTS = 5;
const PLAYERS_PER_COHORT = 250;
const EXP_TO_LEVEL_100 = 71_280;
const MAX_DAYS = 40;

function rngFor(seed) {
  let state = seed >>> 0;
  return () => {
    state += 0x6D2B79F5;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

function gaussian(rng) {
  const u = Math.max(Number.EPSILON, rng());
  const v = rng();
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
}

function simulatePlayer(rng) {
  let exp = 300; // progression.rewards.starter-node-exp
  let activeDays = 0;
  let day30Exp = 0;
  let reached = null;
  for (let day = 1; day <= MAX_DAYS; day++) {
    // Variance covers crew quality, brief full buffers, and expedition absence.
    exp += Math.round(Math.min(900, Math.max(400, 650 + gaussian(rng) * 80)));
    const acted = rng() < 0.98;
    if (acted) {
      activeDays++;
      if (rng() < 0.98) exp += 100; // first collection
      if (rng() < 0.97) exp += 400; // focused Node-EXP commission
      if (rng() < 0.96) exp += 700; // first expedition
      if (rng() < 0.50) exp += 100; // one bounded additional expedition
    }
    if (day % 7 === 0) {
      if (activeDays >= 5) exp += 3_500;
      activeDays = 0;
    }
    if (day === 30) day30Exp = exp;
    if (reached === null && exp >= EXP_TO_LEVEL_100) reached = day;
  }
  return { reached, day30Exp };
}

function median(values) {
  const ordered = [...values].sort((a, b) => a - b);
  const middle = Math.floor(ordered.length / 2);
  return ordered.length % 2
    ? ordered[middle]
    : (ordered[middle - 1] + ordered[middle]) / 2;
}

function percentile(values, fraction) {
  const ordered = [...values].sort((a, b) => a - b);
  const index = (ordered.length - 1) * fraction;
  const low = Math.floor(index);
  const high = Math.min(ordered.length - 1, low + 1);
  return ordered[low] * (1 - (index - low)) + ordered[high] * (index - low);
}

const allDays = [];
console.log("cohort,players,day30_reached_pct,median_day,p10_day,p90_day,median_day30_exp");
for (let cohort = 1; cohort <= COHORTS; cohort++) {
  const rng = rngFor(20260720 + cohort);
  const results = Array.from(
    { length: PLAYERS_PER_COHORT },
    () => simulatePlayer(rng),
  );
  const days = results.map(result => result.reached).filter(day => day !== null);
  const day30Reached = results.filter(result => result.reached <= 30).length;
  allDays.push(...days);
  console.log([
    cohort,
    PLAYERS_PER_COHORT,
    `${(day30Reached / PLAYERS_PER_COHORT * 100).toFixed(1)}%`,
    median(days).toFixed(1),
    percentile(days, 0.10).toFixed(1),
    percentile(days, 0.90).toFixed(1),
    median(results.map(result => result.day30Exp)).toFixed(0),
  ].join(","));
}

const coinSources = 74_000;
const coinSinks = 42_872;
console.log();
console.log(`all_cohorts_median_day=${median(allDays).toFixed(1)}`);
console.log(`coin_source_sink_ratio=${(coinSinks / coinSources * 100).toFixed(1)}%`);
console.log(`modeled_month_one_net_coin_growth=${coinSources - coinSinks}`);
