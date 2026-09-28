'use strict';

/**
 * Deterministic FitTrack food-catalog generator.
 *
 * Reads the pinned USDA SR Legacy artifact, applies the approved filtering and
 * validation rules, and emits:
 *   - V9__seed_food_catalog.sql   the Flyway migration
 *   - out/catalog.json            machine-readable catalog metadata
 *   - out/rejections.json         machine-readable rejection report
 *
 * Determinism contract:
 *   same ZIP sha256 + same generator version + same config  =>  byte-identical output
 *
 * There are no timestamps, no random values and no machine-dependent ordering
 * in the output path. Every collection is sorted on an explicit key before it
 * is written.
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { v5: uuidv5 } = require('./uuidv5.cjs');
const C = require('./config.cjs');

const ROOT = path.resolve(__dirname, '..', '..');
const MIGRATION_DIR = path.join(ROOT, 'backend', 'src', 'main', 'resources', 'db', 'migration');
const V9_PATH = path.join(MIGRATION_DIR, 'V9__seed_food_catalog.sql');
const OUT_DIR = path.join(__dirname, 'out');

const NUTRIENT_COLUMNS = C.NUTRIENTS.map((n) => n.column);

/* ------------------------------------------------------------------ *
 * Integrity and parsing
 * ------------------------------------------------------------------ */

function sha256File(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex').toUpperCase();
}

function assertEqual(actual, expected, label) {
  if (actual !== expected) {
    throw new Error(
      `CHECKSUM MISMATCH (${label})\n  expected ${expected}\n  actual   ${actual}\n` +
      'The pinned artifact does not match. Generation aborted.'
    );
  }
}

/** RFC 4180 CSV parser: handles quoted fields, escaped quotes and CRLF. */
function parseCsv(text) {
  const rows = [];
  let row = [];
  let field = '';
  let inQuotes = false;
  let i = text.charCodeAt(0) === 0xfeff ? 1 : 0;
  while (i < text.length) {
    const ch = text[i];
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') { field += '"'; i += 2; continue; }
        inQuotes = false; i++; continue;
      }
      field += ch; i++; continue;
    }
    if (ch === '"') { inQuotes = true; i++; continue; }
    if (ch === ',') { row.push(field); field = ''; i++; continue; }
    if (ch === '\r') { i++; continue; }
    if (ch === '\n') {
      row.push(field); rows.push(row); row = []; field = ''; i++; continue;
    }
    field += ch; i++;
  }
  if (field !== '' || row.length > 0) { row.push(field); rows.push(row); }
  return rows;
}

function readCsv(file) {
  const rows = parseCsv(fs.readFileSync(file, 'utf8'));
  const header = rows.shift();
  return rows
    .filter((r) => r.length === header.length)
    .map((r) => {
      const o = {};
      header.forEach((h, idx) => { o[h] = r[idx]; });
      return o;
    });
}

/* ------------------------------------------------------------------ *
 * Name normalization (spec step 6)
 * ------------------------------------------------------------------ */

const CONTROL_CHARS = /[\u0000-\u001F\u007F]/;

/**
 * NFC normalize, collapse whitespace runs, trim. Never truncates. Returns null

/* ------------------------------------------------------------------ *
 * Brand filtering (spec rules G2 and G3)
 * ------------------------------------------------------------------ */

/** Split a description into comma-delimited segments. */
function segmentsOf(description) {
  return description.split(',').map((s) => s.trim()).filter((s) => s.length > 0);
}

function tokensOfSegment(segment) {
  return segment
    .toLowerCase()
    .split(/[^a-z0-9.&'-]+/)
    .map((t) => t.replace(/^[.'-]+|[.'-]+$/g, ''))
    .filter((t) => t.length > 0);
}

/**
 * G2: a curated, versioned brand token anywhere in any segment. Multi-word
 * brand names are matched as a contiguous token subsequence so "weight
 * watchers" and "general mills" are caught. Matching is whole-token within a
 * comma-delimited segment; raw substring matching is never used, because it
 * would match "kraft" inside "craft" and "post" inside "posted".
 */
function matchBrandTokens(description) {
  for (const segment of segmentsOf(description)) {
    const tokens = tokensOfSegment(segment);
    for (let i = 0; i < tokens.length; i++) {
      if (C.BRAND_TOKENS.has(tokens[i])) {
        return { rule: 'G2', evidence: segment };
      }
      if (i + 1 < tokens.length &&
          C.BRAND_TOKENS.has(`${tokens[i]} ${tokens[i + 1]}`)) {
        return { rule: 'G2', evidence: segment };
      }
      if (i + 2 < tokens.length &&
          C.BRAND_TOKENS.has(`${tokens[i]} ${tokens[i + 1]} ${tokens[i + 2]}`)) {
        return { rule: 'G2', evidence: segment };
      }
    }
  }
  return null;
}

/**
 * G3: case-shape signal. An all-caps comma-delimited segment of length >= 3
 * that is not in GENERIC_ALLCAPS is treated as a brand marker. BBQ is in the
 * generic list because it is a cooking method in 11 poultry records.
 */
function matchAllCapsSegment(description) {
  for (const segment of segmentsOf(description)) {
    if (segment.length < 3) continue;
    if (!C.ALLCAPS_SEGMENT_RE.test(segment)) continue;
    if (C.GENERIC_ALLCAPS.has(segment.toLowerCase())) continue;
    return { rule: 'G3', evidence: segment };
  }
  return null;
}

/* ------------------------------------------------------------------ *
 * Numeric validation
 * ------------------------------------------------------------------ */

/**
 * A nutrient amount is accepted only when it is a plain non-negative decimal.
 * Rejects empty strings, "<" threshold values, non-finite numbers and
 * negatives. The original string is returned unchanged so the stored numeric
 * keeps full source precision. Nothing is zero-filled, imputed or estimated.
 */
function validateAmount(raw) {
  if (raw === null || raw === undefined) return { ok: false, reason: 'MISSING_NUTRIENT' };
  const s = String(raw).trim();
  if (s === '') return { ok: false, reason: 'MISSING_NUTRIENT' };
  if (s.startsWith('<')) return { ok: false, reason: 'INVALID_NUTRIENT' };
  if (!/^-?\d+(\.\d+)?$/.test(s)) return { ok: false, reason: 'INVALID_NUTRIENT' };
  if (!Number.isFinite(Number(s))) return { ok: false, reason: 'INVALID_NUTRIENT' };
  if (Number(s) < 0) return { ok: false, reason: 'NEGATIVE_NUTRIENT' };
  return { ok: true, value: s };
}

function sqlLiteral(s) {
  return `'${String(s).replace(/'/g, "''")}'`;
}

function sqlNumericOrNull(s) {
  return s === null || s === undefined ? 'NULL' : String(s);
}

/**
 * NFC normalize, collapse whitespace runs, trim. Never truncates. Returns null
 * when the result is unusable; the caller records an INVALID_NAME rejection.
 * Descriptors such as raw/cooked/boiled/roasted and grain variety are
 * preserved verbatim, so "Rice, white, long-grain, regular, cooked" stays
 * distinct from "Rice, white, long-grain, regular, raw".
 */
function normalizeName(raw) {
  if (raw === null || raw === undefined) return null;
  let s = raw.normalize('NFC').replace(/\s+/g, ' ').trim();
  if (s.length === 0) return null;
  if (CONTROL_CHARS.test(s)) return null;
  if (s.length > C.NAME_MAX_LENGTH) return null;
  return s;
}

/** Dedup key: byte-identical to the V8 generated column lower(btrim(name)). */
function dedupKey(normalizedName) {
  return normalizedName.trim().toLowerCase();
}

/* ------------------------------------------------------------------ *
 * Source loading
 * ------------------------------------------------------------------ */

function loadInputs(extractDir) {
  const inner = path.join(extractDir, C.SOURCE.innerDir);
  if (!fs.existsSync(inner)) {
    throw new Error(`Extracted directory not found: ${inner}`);
  }
  const files = {};
  for (const [name, expected] of Object.entries(C.SOURCE.inputSha256)) {
    const p = path.join(inner, name);
    if (!fs.existsSync(p)) throw new Error(`Missing input file: ${name}`);
    assertEqual(sha256File(p), expected, name);
    files[name] = p;
  }
  return files;
}

/** Verify nutrient.csv declares the expected ids, units and nutrient numbers. */
function validateNutrientDefinitions(nutrientRows) {
  const byId = new Map(nutrientRows.map((r) => [r.id, r]));
  for (const n of C.NUTRIENTS) {
    const row = byId.get(n.id);
    if (!row) {
      throw new Error(`NUTRIENT DEFINITION MISSING: id=${n.id} (${n.name}) not in nutrient.csv`);
    }
    if (row.unit_name !== n.unit) {
      throw new Error(`NUTRIENT UNIT MISMATCH: id=${n.id} expected ${n.unit}, found ${row.unit_name}`);
    }
    if (row.nutrient_nbr !== n.nutrientNbr) {
      throw new Error(`NUTRIENT NBR MISMATCH: id=${n.id} expected ${n.nutrientNbr}, found ${row.nutrient_nbr}`);
    }
  }
}

function loadSource(extractDir) {
  const files = loadInputs(extractDir);
  const nutrientRows = readCsv(files['nutrient.csv']);
  validateNutrientDefinitions(nutrientRows);

  // Only the seven required nutrient ids are retained.
  const requiredIds = new Set(C.NUTRIENTS.map((n) => n.id));
  const nutrientsByFood = new Map();
  for (const r of readCsv(files['food_nutrient.csv'])) {
    if (!requiredIds.has(r.nutrient_id)) continue;
    if (!nutrientsByFood.has(r.fdc_id)) nutrientsByFood.set(r.fdc_id, new Map());
    nutrientsByFood.get(r.fdc_id).set(r.nutrient_id, r.amount);
  }

  // Primary portion (seq_num = 1). serving_unit comes from `modifier`, NOT
  // measure_unit_id: every seq_num=1 row carries measure_unit_id 1000
  // ("undetermined") while the useful unit lives in the modifier column.
  const primaryPortion = new Map();
  for (const r of readCsv(files['food_portion.csv'])) {
    if (r.seq_num === '1') primaryPortion.set(r.fdc_id, r);
  }

  return {
    foodRows: readCsv(files['food.csv']),
    nutrientsByFood,
    primaryPortion,
  };
}

/* ------------------------------------------------------------------ *
 * Filtering and validation
 * ------------------------------------------------------------------ */

/**
 * Apply G1 (category), G4 (category-18 allowlist), G2/G3 (brand), name
 * normalization and the seven-nutrient requirement. Every rejection is
 * attributable by FDC id, with the rule that produced it.
 */
function filterAndValidate(src) {
  const rejections = [];
  const accepted = [];
  const reject = (fdcId, description, catId, code, rule, detail) => {
    rejections.push({
      fdc_id: String(fdcId),
      description,
      food_category_id: String(catId),
      rule_id: rule,
      reason: code,
      detail: detail === undefined ? null : detail,
    });
  };

  for (const f of src.foodRows) {
    const fdcId = f.fdc_id;
    const catId = String(f.food_category_id);
    const description = f.description;

    // G1: category exclusion.
    if (C.INCLUDED_FDC_CATEGORIES[catId] === undefined) {
      reject(fdcId, description, catId, 'EXCLUDED_CATEGORY', 'G1', 'category not in allowlist');
      continue;
    }

    // G4: category 18 is admitted only for the South Asian breads.
    if (catId === '18') {
      const ok = C.CATEGORY_18_ALLOWED_PREFIXES.some((p) => description.startsWith(p));
      if (!ok) {
        reject(fdcId, description, catId, 'BRANDED_PRODUCT', 'G4',
          'category 18 record outside the chapati/naan allowlist');
        continue;
      }
    }

    // Name normalization. Never truncates.
    const name = normalizeName(description);
    if (name === null) {
      reject(fdcId, description, catId, 'INVALID_NAME', 'NAME',
        'empty, contains a control character, or longer than 160 characters');
      continue;
    }

    // G2 / G3: brand detection.
    const brand = matchBrandTokens(description) || matchAllCapsSegment(description);
    if (brand) {
      reject(fdcId, description, catId, 'BRANDED_PRODUCT', brand.rule, brand.evidence);
      continue;
    }

    const category = C.categoryFor(catId, name);
    if (!category) {
      reject(fdcId, description, catId, 'EXCLUDED_CATEGORY', 'G1', 'unresolved category');
      continue;
    }

    // Seven required nutrients. No zero-filling, no imputation.
    const available = src.nutrientsByFood.get(fdcId);
    const values = {};
    let failed = null;
    for (const n of C.NUTRIENTS) {
      const raw = available ? available.get(n.id) : undefined;
      const res = validateAmount(raw);
      if (!res.ok) {
        failed = { code: res.reason, nutrient: n.name, raw: raw === undefined ? null : raw };
        break;
      }
      values[n.column] = res.value;
    }
    if (failed) {
      reject(fdcId, description, catId, failed.code, 'NUTRIENT',
        `${failed.nutrient} = ${failed.raw === null ? 'null' : failed.raw}`);
      continue;
    }

    // Serving metadata: display only, never a nutrition denominator.
    // gram_weight <= 0 yields NULL serving data, not a food rejection.
    const portion = src.primaryPortion.get(fdcId);
    let servingSize = null;
    let servingUnit = null;
    if (portion) {
      const gw = Number(portion.gram_weight);
      if (Number.isFinite(gw) && gw > 0) {
        servingSize = portion.gram_weight;
        const mod = (portion.modifier || '').trim();
        servingUnit = mod === '' ? null : mod.slice(0, 30);
      }
    }

    accepted.push({
      fdcId,
      fdcIdNum: Number(fdcId),
      name,
      key: dedupKey(name),
      category,
      fdcCategoryId: catId,
      servingSize,
      servingUnit,
      values,
    });
  }

  return { accepted, rejections };
}

/**
 * Deterministic deduplication on lower(btrim(name)). Ties resolve by:
 *   1. non-proprietary (all survivors already passed the brand filter)
 *   2. lower FDC category id
 *   3. has a valid primary portion with gram_weight > 0
 *   4. lower numeric FDC id
 * Never depends on CSV ordering.
 */
function deduplicate(accepted, rejections) {
  const byKey = new Map();
  for (const rec of accepted) {
    if (!byKey.has(rec.key)) byKey.set(rec.key, []);
    byKey.get(rec.key).push(rec);
  }

  const winners = [];
  let duplicateCount = 0;
  for (const key of [...byKey.keys()].sort()) {
    const group = byKey.get(key);
    if (group.length === 1) { winners.push(group[0]); continue; }
    const ranked = [...group].sort((a, b) => {
      if (Number(a.fdcCategoryId) !== Number(b.fdcCategoryId)) {
        return Number(a.fdcCategoryId) - Number(b.fdcCategoryId);
      }
      const aMissing = a.servingSize === null ? 1 : 0;
      const bMissing = b.servingSize === null ? 1 : 0;
      if (aMissing !== bMissing) return aMissing - bMissing;
      return a.fdcIdNum - b.fdcIdNum;
    });
    winners.push(ranked[0]);
    for (const loser of ranked.slice(1)) {
      duplicateCount++;
      rejections.push({
        fdc_id: loser.fdcId,
        description: loser.name,
        food_category_id: loser.fdcCategoryId,
        rule_id: 'DEDUP',
        reason: 'DUPLICATE_NAME',
        detail: `lost to fdc_id ${ranked[0].fdcId} on key "${key}"`,
      });
    }
  }
  return { winners, duplicateCount };
}

/**
 * Deterministic catalog selection.
 *
 * Three ordered passes, each internally sorted so the result never depends on
 * source row order:
 *   1. every pinned anchor (Tier 1) and every South Asian record (Tier 2), so
 *      staples and the Indian items are always present regardless of category;
 *   2. a balanced per-category fill, round-robin across categories in a fixed
 *      order, so one large category cannot crowd out the others;
 *   3. nothing is added once the target is reached - no filler.
 *
 * Overflow beyond TARGET_MAX is trimmed from the balanced fill only, never from
 * the anchor or South Asian sets.
 */
function selectCatalog(winners) {
  const anchors = new Set(C.ANCHOR_FDC_IDS.map(String));
  const southAsian = new Set(C.SOUTH_ASIAN_FDC_IDS.map(String));

  // Fixed category order: this is the priority used for the balanced fill.
  const CATEGORY_ORDER = [
    'LEGUMES', 'GRAINS', 'DAIRY', 'EGGS', 'VEGETABLES', 'FRUITS',
    'NUTS_SEEDS', 'MEAT', 'SEAFOOD', 'OILS_FATS', 'SPICES', 'OTHER',
  ];

  const cmp = (a, b) => a.name.localeCompare(b.name) || a.fdcIdNum - b.fdcIdNum;

  const picked = new Map();
  const add = (r) => { if (!picked.has(r.fdcId)) picked.set(r.fdcId, r); };

  // Tier 1: pinned anchors.
  const tier1 = winners.filter((r) => anchors.has(r.fdcId)).sort(cmp);
  tier1.forEach(add);

  // Tier 2: South Asian records.
  const tier2 = winners.filter((r) => southAsian.has(r.fdcId)).sort(cmp);
  tier2.forEach(add);

  // Tier 3: balanced round-robin fill.
  const pools = CATEGORY_ORDER.map((c) => ({
    items: winners.filter((r) => r.category === c).sort(cmp),
    cursor: 0,
  }));
  const tier3 = [];
  let progressed = true;
  while (progressed && picked.size < C.TARGET_MAX) {
    progressed = false;
    for (const pool of pools) {
      if (pool.cursor >= pool.items.length) continue;
      const rec = pool.items[pool.cursor++];
      if (picked.has(rec.fdcId)) continue;
      picked.set(rec.fdcId, rec);
      tier3.push(rec);
      progressed = true;
      if (picked.size >= C.TARGET_MAX) break;
    }
  }

  return {
    selected: [...picked.values()],
    tiers: { tier1: tier1.length, tier2: tier2.length, tier3: tier3.length },
    overflow: [],
  };
}

/* ------------------------------------------------------------------ *
 * SQL emission
 * ------------------------------------------------------------------ */

function buildV9(selected) {
  const ordered = [...selected].sort((a, b) => a.fdcIdNum - b.fdcIdNum);
  const L = [];
  L.push('-- Phase 8: seed the global food catalog from USDA FoodData Central.');
  L.push('--');
  L.push('-- Source');
  L.push(`--   Dataset          : ${C.SOURCE.dataset} (${C.SOURCE.dataType})`);
  L.push(`--   Publisher        : ${C.SOURCE.publisher}`);
  L.push(`--   Release          : ${C.SOURCE.release}`);
  L.push(`--   URL              : ${C.SOURCE.url}`);
  L.push(`--   ZIP SHA-256      : ${C.SOURCE.zipSha256}`);
  L.push('--');
  L.push('-- Generators and rules. All are versioned: changing any of them requires a NEW');
  L.push('-- migration rather than an edit to this file, because V9 may already be applied.');
  L.push(`--   Generator        : ${C.GENERATOR_VERSION}`);
  L.push(`--   Category mapping : ${C.CATEGORY_MAPPING_VERSION}`);
  L.push(`--   Brand filter     : ${C.BRAND_FILTER_VERSION}`);
  L.push(`--   UUID namespace   : ${C.FITTRACK_FOOD_CATALOG_NAMESPACE} (RFC 4122 DNS namespace)`);
  L.push("--   UUID formula     : UUIDv5(namespace, 'fdc:<numeric fdc id>')");
  L.push('--');
  L.push('-- Nutrition basis');
  L.push('--   Every value below is PER 100 GRAMS, taken verbatim from food_nutrient.amount,');
  L.push('--   whose field definition in the source workbook reads "Amount of the nutrient per');
  L.push('--   100g of food." No unit conversion and no rounding is applied here; the server');
  L.push('--   scales by grams/100 in NutritionCalculator at scale 2 HALF_UP.');
  L.push('--   serving_size and serving_unit are DISPLAY METADATA ONLY and are deliberately');
  L.push('--   not a nutrition denominator.');
  L.push('--');
  L.push('-- Data quality');
  L.push('--   Records missing any of the seven required nutrients were REJECTED, never');
  L.push('--   zero-filled or imputed. Branded and manufacturer-specific products were');
  L.push('--   excluded even though they sit inside SR Legacy, because that data type does');
  L.push('--   include proprietary formulations.');
  L.push('--');
  L.push('-- Attribution (voluntary; this source is public domain, not CC-BY)');
  L.push(`--   ${C.SOURCE.dataset}, ${C.SOURCE.dataType}, ${C.SOURCE.release}.`);
  L.push('--   See docs/food-catalog.md for provenance, checksums and the licensing caveat.');
  L.push('');
  L.push('INSERT INTO foods (id,name,category,serving_size,serving_unit,calories,protein_g,carbs_g,fat_g,fiber_g,sugar_g,sodium_mg,source) VALUES');

  const rows = ordered.map((r) => {
    const id = uuidv5(C.FITTRACK_FOOD_CATALOG_NAMESPACE, `fdc:${r.fdcIdNum}`);
    const cells = [
      sqlLiteral(id),
      sqlLiteral(r.name),
      sqlLiteral(r.category),
      sqlNumericOrNull(r.servingSize),
      r.servingUnit === null ? 'NULL' : sqlLiteral(r.servingUnit),
      ...NUTRIENT_COLUMNS.map((c) => r.values[c]),
      sqlLiteral(C.SOURCE_COLUMN_PREFIX + r.fdcId),
    ];
    return `  (${cells.join(',')})`;
  });
  L.push(rows.join(',\n') + ';');
  L.push('');
  return L.join('\n');
}


/* ------------------------------------------------------------------ *
 * Entry point
 * ------------------------------------------------------------------ */

function main() {
  const extractDir = process.argv[2];
  if (!extractDir) {
    process.stderr.write('Usage: node generator.cjs <extracted-zip-directory>\n');
    process.exit(2);
  }
  const log = (s) => process.stdout.write(s + '\n');

  log('FitTrack food catalog generator');
  log(`  generator version : ${C.GENERATOR_VERSION}`);
  log(`  category mapping  : ${C.CATEGORY_MAPPING_VERSION}`);
  log(`  brand filter      : ${C.BRAND_FILTER_VERSION}`);
  log(`  source            : ${C.SOURCE.dataset} / ${C.SOURCE.dataType}`);

  const src = loadSource(extractDir);
  log('  input checksums   : VERIFIED (7 files)');
  log('  nutrient defs     : VERIFIED (7 ids, units, nbrs)');
  log(`  source foods      : ${src.foodRows.length}`);

  const { accepted, rejections } = filterAndValidate(src);
  log(`  passed filters    : ${accepted.length}`);

  const { winners, duplicateCount } = deduplicate(accepted, rejections);
  log(`  after dedup       : ${winners.length} (${duplicateCount} duplicates)`);

  const { selected, tiers } = selectCatalog(winners);
  log(`  selected          : ${selected.length} ` +
      `(anchors ${tiers.tier1}, south asian ${tiers.tier2}, balanced fill ${tiers.tier3})`);

  if (selected.length < C.TARGET_MIN) {
    throw new Error(`Catalog below the hard minimum: ${selected.length} < ${C.TARGET_MIN}. Refusing to emit.`);
  }

  rejections.sort((a, b) =>
    Number(a.fdc_id) - Number(b.fdc_id) || a.reason.localeCompare(b.reason));
  const selectedSorted = [...selected].sort((a, b) => a.fdcIdNum - b.fdcIdNum);

  const sql = buildV9(selectedSorted);
  fs.mkdirSync(MIGRATION_DIR, { recursive: true });
  fs.writeFileSync(V9_PATH, sql, 'utf8');

  const byReason = {};
  for (const r of rejections) byReason[r.reason] = (byReason[r.reason] || 0) + 1;

  const byCategory = {};
  for (const r of selectedSorted) byCategory[r.category] = (byCategory[r.category] || 0) + 1;

  const catalog = {
    generator_version: C.GENERATOR_VERSION,
    category_mapping_version: C.CATEGORY_MAPPING_VERSION,
    brand_filter_version: C.BRAND_FILTER_VERSION,
    source: {
      dataset: C.SOURCE.dataset,
      data_type: C.SOURCE.dataType,
      release: C.SOURCE.release,
      url: C.SOURCE.url,
      zip_sha256: C.SOURCE.zipSha256,
    },
    uuid_namespace: C.FITTRACK_FOOD_CATALOG_NAMESPACE,
    counts: {
      source_foods: src.foodRows.length,
      passed_filters: accepted.length,
      after_dedup: winners.length,
      selected: selected.length,
      rejections: rejections.length,
      duplicates: duplicateCount,
      by_category: byCategory,
    },
    categories: C.CATEGORIES,
    records: selectedSorted.map((r) => ({
      fdc_id: r.fdcId,
      id: uuidv5(C.FITTRACK_FOOD_CATALOG_NAMESPACE, `fdc:${r.fdcIdNum}`),
      name: r.name,
      category: r.category,
      serving_size: r.servingSize,
      serving_unit: r.servingUnit,
      source: C.SOURCE_COLUMN_PREFIX + r.fdcId,
      ...r.values,
    })),
  };

  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.writeFileSync(path.join(OUT_DIR, 'catalog.json'),
    JSON.stringify(catalog, null, 2) + '\n', 'utf8');
  fs.writeFileSync(path.join(OUT_DIR, 'rejections.json'),
    JSON.stringify({ generator_version: C.GENERATOR_VERSION, by_reason: byReason, rejections }, null, 2) + '\n',
    'utf8');

  log('');
  log(`  V9 written        : ${path.relative(ROOT, V9_PATH)}`);
  log('  category spread   :');
  for (const k of Object.keys(byCategory).sort()) {
    log(`    ${k.padEnd(20)} ${byCategory[k]}`);
  }
  log('  rejection breakdown:');
  for (const k of Object.keys(byReason).sort()) {
    log(`    ${k.padEnd(20)} ${byReason[k]}`);
  }
  log('');
  log(`  V9 SQL sha256     : ${crypto.createHash('sha256').update(sql, 'utf8').digest('hex').toUpperCase()}`);
}

if (require.main === module) {
  try {
    main();
  } catch (err) {
    process.stderr.write(`\nGENERATION FAILED: ${err.message}\n`);
    process.exit(1);
  }
}

module.exports = {
  parseCsv, readCsv, normalizeName, dedupKey, validateAmount,
  matchBrandTokens, matchAllCapsSegment, filterAndValidate,
  deduplicate, selectCatalog, buildV9,
};


