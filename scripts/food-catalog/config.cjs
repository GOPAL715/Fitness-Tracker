'use strict';

/**
 * FitTrack food-catalog generator - pinned configuration.
 *
 * Every value here is part of the generator's contract. Changing any of them
 * changes the emitted V9 migration, so each is versioned and must be paired
 * with a NEW Flyway migration rather than an edit to an applied V9.
 */

const GENERATOR_VERSION = '1.0.0';
const CATEGORY_MAPPING_VERSION = '1.0';
const BRAND_FILTER_VERSION = '1.0';

/* ------------------------------------------------------------------ *
 * Pinned source artifact
 * ------------------------------------------------------------------ */

const SOURCE = {
  publisher: 'U.S. Department of Agriculture, Agricultural Research Service',
  dataset: 'USDA FoodData Central',
  dataType: 'SR Legacy',
  release: 'April 2018 (final release of Standard Reference Legacy)',
  url: 'https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip',
  zipSha256:
    'B80817294B8850530AAEDF2E515C02593B1824F763A0FF356E5C2081643E6FD0',
  innerDir: 'FoodData_Central_sr_legacy_food_csv_2018-04',
  /** SHA-256 of every CSV the generator reads. Abort on any mismatch. */
  inputSha256: {
    'food.csv':
      'DE8B0390058DB4F102368C9FBDFDE144790B480FF4CB0507C0E060EF99805DEE',
    'food_nutrient.csv':
      '6D07E6FC062B09750C4D95112BE5A5CDB56A2346EFCFD0C96B7A7D705812D4BD',
    'nutrient.csv':
      '466AB9D011094CC1AD8D939B246693988B56817C42D0E40A98820723695C6084',
    'food_portion.csv':
      '6332E29DA61E13F7BD950B759461AF73303C76E8B0E64DC9DF4E41D5347CF3D1',
    'food_category.csv':
      'A8E05C18CF010D44303F2A02765637A21F9AD621F90B4F811AB4A4A436CFEDD9',
    'measure_unit.csv':
      'C1396FFEBBE6337260FE4DE6276AA91847C7F41FF31A4F69080764A5B36BA155',
    'sr_legacy_food.csv':
      'FF0FE85A469AA6D6702D68348850FC79DEE35E9515324026964F1209A0E3544D',
  },
};

/* ------------------------------------------------------------------ *
 * Nutrition contract
 * ------------------------------------------------------------------ */

/**
 * Source nutrient values are already per 100 g. The generator performs NO unit
 * conversion and NO rounding: it copies the source `amount` string verbatim so
 * the stored numeric keeps full source precision. Scaling and scale-2 HALF_UP
 * rounding belong to NutritionCalculator at request time.
 */
const NUTRIENTS = [
  { id: '1008', nutrientNbr: '208', name: 'Energy', unit: 'KCAL', column: 'calories' },
  { id: '1003', nutrientNbr: '203', name: 'Protein', unit: 'G', column: 'protein_g' },
  { id: '1005', nutrientNbr: '205', name: 'Carbohydrate, by difference', unit: 'G', column: 'carbs_g' },
  { id: '1004', nutrientNbr: '204', name: 'Total lipid (fat)', unit: 'G', column: 'fat_g' },
  { id: '1079', nutrientNbr: '291', name: 'Fiber, total dietary', unit: 'G', column: 'fiber_g' },
  { id: '2000', nutrientNbr: '269', name: 'Sugars, Total', unit: 'G', column: 'sugar_g' },
  { id: '1093', nutrientNbr: '307', name: 'Sodium, Na', unit: 'MG', column: 'sodium_mg' },
];

/* ------------------------------------------------------------------ *
 * Category decisions (all 28 decided explicitly)
 * ------------------------------------------------------------------ */

/**
 * FDC category id -> FitTrack category. A null value means the category is
 * included but resolved dynamically by categoryFor(). Any FDC category absent
 * from this map is excluded and reported.
 */
const INCLUDED_FDC_CATEGORIES = {
  1: null, // DAIRY, or EGGS when the normalized name begins with "egg"
  2: 'SPICES',
  4: 'OILS_FATS',
  5: 'MEAT',
  9: 'FRUITS',
  11: 'VEGETABLES',
  12: 'NUTS_SEEDS',
  13: 'MEAT',
  15: 'SEAFOOD',
  16: 'LEGUMES',
  17: 'MEAT',
  18: 'GRAINS', // special: only chapati/roti and naan prefixes survive (rule G4)
  20: 'GRAINS',
};

const CATEGORIES = [
  'DAIRY', 'EGGS', 'MEAT', 'SEAFOOD', 'LEGUMES', 'GRAINS', 'OILS_FATS',
  'SPICES', 'NUTS_SEEDS', 'VEGETABLES', 'FRUITS', 'OTHER',
];

/**
 * Category 1 splits on a leading "egg": EGGS when present, DAIRY otherwise.
 * Deterministic and unambiguous.
 */
function categoryFor(fdcCategoryId, normalizedName) {
  if (String(fdcCategoryId) === '1') {
    return normalizedName.toLowerCase().startsWith('egg') ? 'EGGS' : 'DAIRY';
  }
  return INCLUDED_FDC_CATEGORIES[String(fdcCategoryId)] || null;
}

/* ------------------------------------------------------------------ *
 * Rule G4 - category 18 allowlist
 * ------------------------------------------------------------------ */

/**
 * Baked Products is the only proprietary-heavy category admitted, because it is
 * the sole home of the South Asian breads (chapati/roti and naan). Only these
 * two prefixes survive; every other baked record is rejected.
 */
const CATEGORY_18_ALLOWED_PREFIXES = [
  'Bread, chapati or roti',
  'Bread, naan',
];


/* ------------------------------------------------------------------ *
 * Rule G4 - category 18 allowlist
 * ------------------------------------------------------------------ */

/* ------------------------------------------------------------------ *
 * Rule G2 - brand token set, version 1.0
 * ------------------------------------------------------------------ */

/**
 * Observed in the pinned artifact by enumerating all-caps comma-delimited
 * description segments, plus mixed-case manufacturer names a case-shape rule
 * alone would miss. Matched case-insensitively on whole tokens within a
 * comma-delimited segment; raw substring matching is forbidden because it would
 * match "kraft" inside "craft".
 */
const BRAND_TOKENS = new Set([
  'abbott', 'ancient harvest', 'applebee', 'betty crocker', 'bolthouse farms',
  'burger king', 'campbell', 'chobani', 'chosen roaster', 'concord',
  'cracker barrel', 'dannon', 'de boles', 'denny', 'domino', 'dunkin',
  'eclipse', 'enfamil', 'enova', 'gerber', 'general mills', 'heinz',
  'hellmann', 'hormel', 'isomil', 'kellogg', 'kikkoman', 'kraft',
  'lifeway', 'mcdonald', 'mori', 'naked juice', 'nestle', 'odwalla',
  'oscar mayer', 'pillsbury', 'popeyes', 'post', 'prosobee', 'quaker',
  'smart balance', 'smart soup', 'starkist', 'subway', 'swanson',
  'taco bell', 'tgi', 'tinkyada', 'uncle ben', 'vitasoy', 'weight watchers',
]);

/* ------------------------------------------------------------------ *
 * Rule G3 - case-shape signal
 * ------------------------------------------------------------------ */

/** An all-caps-looking comma-delimited segment triggers review. */
const ALLCAPS_SEGMENT_RE = /^[A-Z0-9][A-Z0-9 &'.\-/\(\)!]*$/;

/**
 * Legitimate all-caps tokens that are NOT brands. BBQ is load-bearing: it
 * appears 11 times in Poultry as a cooking method ("Chicken, broiler,
 * rotisserie, BBQ, drumstick"), and rejecting it would drop good records.

/**
 * Tier 1 anchors, pinned by FDC id so the catalog is stable and useful rather
 * than an alphabetical slice of one category. These are IDENTIFIERS ONLY: no
 * nutrition value is ever hand entered, and every anchor still passes the same
 * filters and validation as any other record.
 *
 * Each id below was verified present in the pinned artifact and to carry all
 * seven required nutrients.
 */
const ANCHOR_FDC_IDS = [
  // GRAINS - staples
  168935, // Rice, white, long-grain, regular, cooked, unenriched, with salt
  168877, // Rice, white, long-grain, regular, raw, enriched
  169704, // Rice, brown, long-grain, cooked
  168893, // Wheat flour, whole-grain
  168915, // Pasta, whole grain, 51% whole wheat, dry
  168872, // Oat bran, raw
  // LEGUMES
  172421, // Lentils, mature seeds, cooked, boiled, without salt
  175254, // Lentils, mature seeds, cooked, boiled, with salt
  173756, // Chickpeas (garbanzo beans, bengal gram), mature seeds, raw
  173757, // Chickpeas, cooked, boiled, without salt
  174288, // Chickpea flour (besan)
  172476, // Tofu, raw, regular, prepared with calcium sulfate
  174290, // Tofu, extra firm, prepared with nigari
  // DAIRY and EGGS
  171265, // Milk, whole, 3.25% milkfat, with added vitamin D
  171284, // Yogurt, plain, whole milk
  170893, // Egg, whole, raw, frozen, salted, pasteurized
  171314, // Butter, Clarified butter (ghee)
  // MEAT
  171075, // Chicken, broilers or fryers, breast, meat and skin, cooked, roasted
  168650, // Beef, ground, 70% lean, patty cooked, pan-broiled
  168631, // Beef, loin, top loin steak, cooked, grilled
  167810, // Pork, fresh, composite, separable lean and fat, raw
  174314, // Lamb, leg, whole, lean only, cooked, roasted
  // SEAFOOD
  171955, // Fish, cod, Atlantic, raw
  173688, // Fish, salmon, chinook, raw
  171986, // Fish, tuna, light, canned in water, drained solids
  // VEGETABLES
  170026, // Potatoes, flesh and skin, raw
  170457, // Tomatoes, red, ripe, raw
  170000, // Onions, raw
  168462, // Spinach, raw
  170393, // Carrots, raw
  170379, // Broccoli, raw
  169986, // Cauliflower, raw
  169975, // Cabbage, raw
  168409, // Cucumber, with peel, raw
  170050, // Tomatoes, red, ripe, cooked
  169256, // Mustard greens, raw
  // FRUITS
  173944, // Bananas, raw
  171688, // Apples, raw, with skin
  169097, // Oranges, raw, all commercial varieties
  167767, // Pineapple, canned, juice pack, drained
  // NUTS_SEEDS
  172430, // Peanuts, all types, raw
  168596, // Nuts, almonds, dry roasted, with salt added
  168597, // Nuts, cashew butter, plain, with salt added
  169412, // Seeds, sesame seed kernels, dried
  // OILS_FATS
  171413, // Oil, olive, salad or cooking
  // SPICES
  172231, // Spices, turmeric, ground
  170929, // Spices, mustard seed, ground
];

/**
 * Legitimate all-caps tokens that are NOT brands. BBQ is load-bearing: it
 * appears 11 times in Poultry as a cooking method ("Chicken, broiler,
 * rotisserie, BBQ, drumstick"), and rejecting it would drop good records.
 */
const GENERIC_ALLCAPS = new Set([
  'bbq', 'usda', 'u.s.', 'nlea', 'd3', 'a', 'b', 'c', 'd', 'e', 'f', 'g',
  'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u',
  'v', 'w', 'x', 'y', 'z',
]);

/* ------------------------------------------------------------------ *
 * Selection
 * ------------------------------------------------------------------ */

const TARGET_MIN = 50;
const TARGET_MAX = 200;
const TARGET_PREFERRED_MIN = 120;
const TARGET_PREFERRED_MAX = 180;


/**
 * South Asian priority set, restricted to records verified present and complete
 * in this artifact. Paneer, idli, dosa, rajma, toor/moong/masoor/urad dal,
 * curd, mango, whole cashew and tuna are NOT in SR Legacy and are NOT
 * substituted here; they are catalog-extension candidates for a later release.
 */
const SOUTH_ASIAN_FDC_IDS = [
  171844, // Bread, chapati or roti, plain, commercially prepared
  174075, // Bread, chapati or roti, whole wheat, commercially prepared, frozen
  171845, // Bread, naan, plain, commercially prepared, refrigerated
  174077, // Bread, naan, whole wheat, commercially prepared, refrigerated
  172420, // Lentils, raw
  172421, // Lentils, mature seeds, cooked, boiled, without salt
  175254, // Lentils, mature seeds, cooked, boiled, with salt
  173756, // Chickpeas (garbanzo beans, bengal gram), mature seeds, raw
  173757, // Chickpeas (garbanzo beans, bengal gram), cooked, boiled, without salt
  174288, // Chickpea flour (besan)
  171314, // Butter, Clarified butter (ghee)
  172231, // Spices, turmeric, ground
  170929, // Spices, mustard seed, ground
  169256, // Mustard greens, raw
];

/* ------------------------------------------------------------------ *
 * Identity and naming
 * ------------------------------------------------------------------ */

/**
 * RFC 4122 DNS namespace. A standards-defined constant means anyone can
 * reproduce UUIDv5(FITTRACK_FOOD_CATALOG_NAMESPACE, "fdc:<id>") from the RFC
 * alone. Frozen once V9 is applied: changing it would orphan meal_items.food_id
 * references.
 */
const FITTRACK_FOOD_CATALOG_NAMESPACE = '6ba7b810-9dad-11d1-80b4-00c04fd430c8';

const NAME_MAX_LENGTH = 160;
const SOURCE_COLUMN_PREFIX = 'USDA FDC ';

module.exports = {
  GENERATOR_VERSION,
  CATEGORY_MAPPING_VERSION,
  BRAND_FILTER_VERSION,
  SOURCE,
  NUTRIENTS,
  INCLUDED_FDC_CATEGORIES,
  CATEGORIES,
  categoryFor,
  CATEGORY_18_ALLOWED_PREFIXES,
  BRAND_TOKENS,
  ALLCAPS_SEGMENT_RE,
  GENERIC_ALLCAPS,
  TARGET_MIN,
  TARGET_MAX,
  TARGET_PREFERRED_MIN,
  TARGET_PREFERRED_MAX,
  SOUTH_ASIAN_FDC_IDS,
  ANCHOR_FDC_IDS,
  FITTRACK_FOOD_CATALOG_NAMESPACE,
  NAME_MAX_LENGTH,
  SOURCE_COLUMN_PREFIX,
};
