// One-shot verification: cross-check every seeded record against the raw
// source CSV, independently of the generator's own join logic.
const fs = require('fs');
const path = require('path');
const { readCsv } = require('./generator.cjs');

const C = require('./config.cjs');
const extract = process.argv[2];
const inner = path.join(extract, C.SOURCE.innerDir);
const catalog = JSON.parse(fs.readFileSync(path.join(__dirname, 'out', 'catalog.json'), 'utf8'));

const colById = Object.fromEntries(C.NUTRIENTS.map((n) => [n.id, n.column]));

const src = new Map();
for (const r of readCsv(path.join(inner, 'food_nutrient.csv'))) {
  if (!colById[r.nutrient_id]) continue;
  if (!src.has(r.fdc_id)) src.set(r.fdc_id, new Map());
  src.get(r.fdc_id).set(r.nutrient_id, r.amount);
}

let checked = 0;
const problems = [];
for (const rec of catalog.records) {
  const have = src.get(rec.fdc_id);
  if (!have) { problems.push(`fdc ${rec.fdc_id} has no nutrient rows`); continue; }
  for (const n of C.NUTRIENTS) {
    const expect = have.get(n.id);
    const got = String(rec[n.column]);
    checked++;
    if (expect === undefined) problems.push(`fdc ${rec.fdc_id} ${n.column} absent in source`);
    else if (Number(expect) !== Number(got)) {
      problems.push(`fdc ${rec.fdc_id} ${n.column} seeded=${got} source=${expect}`);
    }
  }
  // Nutrition must be per 100 g and non-negative.
  for (const n of C.NUTRIENTS) {
    if (rec[n.column] < 0) problems.push(`fdc ${rec.fdc_id} ${n.column} negative`);
  }
}

console.log(`records checked : ${catalog.records.length}`);
console.log(`nutrient values : ${checked}`);
console.log(`mismatches      : ${problems.length}`);
for (const p of problems.slice(0, 20)) console.log('  ' + p);
process.exit(problems.length === 0 ? 0 : 1);
