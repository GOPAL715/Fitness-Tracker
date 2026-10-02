# AI FitTrack Food Catalog

The `foods` table is a **global, read-only catalog** of nutrition data. This
document records exactly where every seeded value came from, how it was
transformed, and how to regenerate it.

No nutrition value in this repository is hand-written. Every number in
`V9__seed_food_catalog.sql` is copied verbatim from the pinned USDA artifact
described below.

## Source

| Field | Value |
| --- | --- |
| Dataset | USDA FoodData Central |
| Data type | SR Legacy (Standard Reference Legacy) |
| Publisher | U.S. Department of Agriculture, Agricultural Research Service |
| Release | April 2018 — final release of SR Legacy; frozen and never updated |
| URL | <https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip> |
| ZIP SHA-256 | `B80817294B8850530AAEDF2E515C02593B1824F763A0FF356E5C2081643E6FD0` |

The ZIP is verified **before** extraction. A mismatch aborts generation without
extracting anything, so a substituted or corrupted download can never reach the
generator.

### Input file checksums

Verified on every run; any mismatch aborts.

| File | SHA-256 |
| --- | --- |
| `food.csv` | `DE8B0390058DB4F102368C9FBDFDE144790B480FF4CB0507C0E060EF99805DEE` |
| `food_nutrient.csv` | `6D07E6FC062B09750C4D95112BE5A5CDB56A2346EFCFD0C96B7A7D705812D4BD` |
| `nutrient.csv` | `466AB9D011094CC1AD8D939B246693988B56817C42D0E40A98820723695C6084` |
| `food_portion.csv` | `6332E29DA61E13F7BD950B759461AF73303C76E8B0E64DC9DF4E41D5347CF3D1` |
| `food_category.csv` | `A8E05C18CF010D44303F2A02765637A21F9AD621F90B4F811AB4A4A436CFEDD9` |
| `measure_unit.csv` | `C1396FFEBBE6337260FE4DE6276AA91847C7F41FF31A4F69080764A5B36BA155` |
| `sr_legacy_food.csv` | `FF0FE85A469AA6D6702D68348850FC79DEE35E9515324026964F1209A0E3544D` |

## Per-100g contract

`foods` stores **per 100 grams** for calories and every macro. This is the
single basis the whole system uses.

The basis is not assumed. It is stated in the source's own field-description
workbook, shipped inside the ZIP as
`Download & API Field Descriptions-2019-10-11-16-22.xlsx`:

- `food_nutrient.amount` — *"Amount of the nutrient per 100g of food. Specified
  in unit defined in the nutrient table."*
- `nutrient.unit_name` — *"The standard unit of measure for the nutrient (per
  100g of food)"*

```
stored value  = nutrient per 100 g
meal value    = stored value × grams / 100
```

Scaling happens server-side in `NutritionCalculator` at `BigDecimal` scale 2,
`HALF_UP`. The generator applies **no conversion and no rounding**, so the
stored numeric keeps full source precision.

`serving_size` and `serving_unit` are **display metadata only** and are
deliberately not a nutrition denominator.

## Nutrient mapping

| `nutrient_nbr` | `nutrient.id` | Name | Unit | `foods` column |
| --- | --- | --- | --- | --- |
| 208 | 1008 | Energy | KCAL | `calories` |
| 203 | 1003 | Protein | G | `protein_g` |
| 205 | 1005 | Carbohydrate, by difference | G | `carbs_g` |
| 204 | 1004 | Total lipid (fat) | G | `fat_g` |
| 291 | 1079 | Fiber, total dietary | G | `fiber_g` |
| 269 | 2000 | Sugars, Total | G | `sugar_g` |
| 307 | 1093 | Sodium, Na | MG | `sodium_mg` |

The join is on `nutrient.id`, not the display code `nutrient_nbr`. The generator
asserts the id, unit and nbr of all seven on every run and aborts on mismatch.

## Category mapping

All 28 SR Legacy categories are decided explicitly. Included categories map to
one of twelve AI FitTrack categories.

| FDC id | FDC category | AI FitTrack | Decision |
| --- | --- | --- | --- |
| 1 | Dairy and Egg Products | `DAIRY`, or `EGGS` when the name begins with "egg" | include |
| 2 | Spices and Herbs | `SPICES` | include |
| 3 | Baby Foods | — | exclude (proprietary infant formula) |
| 4 | Fats and Oils | `OILS_FATS` | include |
| 5 | Poultry Products | `MEAT` | include |
| 6 | Soups, Sauces, and Gravies | — | exclude (branded) |
| 7 | Sausages and Luncheon Meats | — | exclude (processed proprietary) |
| 8 | Breakfast Cereals | — | exclude (branded) |
| 9 | Fruits and Fruit Juices | `FRUITS` | include |
| 10 | Pork Products | `MEAT` | include |
| 11 | Vegetables and Vegetable Products | `VEGETABLES` | include |
| 12 | Nut and Seed Products | `NUTS_SEEDS` | include |
| 13 | Beef Products | `MEAT` | include |
| 14 | Beverages | — | exclude (branded soft drinks) |
| 15 | Finfish and Shellfish Products | `SEAFOOD` | include |
| 16 | Legumes and Legume Products | `LEGUMES` | include |
| 17 | Lamb, Veal, and Game Products | `MEAT` | include |
| 18 | Baked Products | `GRAINS` | include, **name allowlist only** (below) |
| 19 | Sweets | — | exclude (branded confectionery) |
| 20 | Cereal Grains and Pasta | `GRAINS` | include |
| 21 | Fast Foods | — | exclude (branded) |
| 22 | Meals, Entrees, and Side Dishes | — | exclude (composite dishes) |
| 23 | Snacks | — | exclude (proprietary, discretionary) |
| 24 | American Indian/Alaska Native Foods | — | exclude (niche) |
| 25 | Restaurant Foods | — | exclude (branded) |
| 26 | Branded Food Products Database | — | exclude (zero rows in this data type) |
| 27 | Quality Control Materials | — | exclude (zero rows; laboratory material) |
| 28 | Alcoholic Beverages | — | exclude (zero rows; out of scope) |

Resulting vocabulary: `DAIRY` `EGGS` `MEAT` `SEAFOOD` `LEGUMES` `GRAINS`
`OILS_FATS` `SPICES` `NUTS_SEEDS` `VEGETABLES` `FRUITS` `OTHER`.

## Brand and proprietary filtering

**Excluding the Branded Foods data type is not sufficient.** SR Legacy contains
manufacturer-specific products even though it contains no rows from the Branded
Food Products Database category — for example `Pillsbury Golden Layer Buttermilk
Biscuits`, `Snacks, KRAFT, CORNNUTS, plain`, `Cereals ready-to-eat, POST, HONEY
BUNCHES OF OATS` and `Heinz, Weight Watcher, Chocolate Eclair`. Those are
formulations supplied by third parties. Four layered rules are therefore applied
in order, and each rejection records the rule that produced it.

| Rule | Mechanism |
| --- | --- |
| **G1** | Category exclusion (table above) |
| **G2** | Versioned `BRAND_TOKENS` set, matched case-insensitively on **whole tokens within a comma-delimited segment**. Raw substring matching is never used, because it would match `kraft` inside `craft` and `post` inside `posted` |
| **G3** | Case-shape signal: an all-caps comma-delimited segment of length ≥ 3 that is not in `GENERIC_ALLCAPS` |
| **G4** | Category-18 allowlist: only `Bread, chapati or roti*` and `Bread, naan*` survive |

`BBQ` is deliberately in `GENERIC_ALLCAPS`. It appears 11 times in Poultry as a
cooking method (`Chicken, broiler, rotisserie, BBQ, drumstick`), so treating it
as a brand would drop eleven good records. This is the case that shows why
"any all-caps segment is a brand" is the wrong rule.

## Name normalization

Applied in order, and **never truncating**:

1. Unicode NFC normalization
2. whitespace runs collapsed to one U+0020
3. trim
4. reject empty, reject control characters, reject longer than 160 characters

Preparation and variety descriptors are preserved verbatim, so
`Rice, white, long-grain, regular, cooked` stays distinct from
`Rice, white, long-grain, regular, raw`.

Deduplication uses `lower(btrim(name))`, byte-identical to the generated column
`foods.name_normalized` added in V8. Ties resolve by non-proprietary status,
then lower FDC category id, then presence of a valid primary portion, then
lower numeric FDC id — never by source row order. The guard is retained even
though the pinned artifact currently contains no collisions, because
`idx_foods_name_normalized` is **not unique** and PostgreSQL will not catch a
violation.

## Serving metadata

`serving_size` comes from `food_portion.gram_weight` where `seq_num = 1`.

`serving_unit` comes from `food_portion.modifier`, **not** `measure_unit_id`.
Every `seq_num = 1` row carries `measure_unit_id = 1000` ("undetermined"),
while the useful unit (`oz`, `cup`, `tbsp`, `fl oz`, `slice`, `serving`) lives
in the `modifier` column. Following the foreign key would store the literal
string `undetermined` for every food.

A food with no `seq_num = 1` portion, or with `gram_weight <= 0`, gets `NULL`
serving data. That is valid and is **not** a rejection reason.

## Identity

```
id = UUIDv5(6ba7b810-9dad-11d1-80b4-00c04fd430c8, "fdc:<numeric fdc id>")
```

The namespace is the **RFC 4122 DNS namespace**, a standards-defined constant
rather than a value chosen for this project. Anyone can therefore reproduce any
catalog id from the RFC alone, without access to this repository. UUIDv5 is
SHA-1 based and fully deterministic across runs, platforms and languages.

No UUIDv4 and no random values are used anywhere. The namespace is frozen once
V9 is applied: changing it would orphan `meal_items.food_id` and
`food_scan_items.food_id` references.

`foods.source` is set to `USDA FDC <fdcId>`, a provenance identifier rather than
a licence statement. The column is `varchar(40)` and must not hold licence text.

## South Asian coverage and its limits

SR Legacy contains genuine South Asian entries, and all of them are seeded:
chapati/roti (plain and whole wheat), naan (plain and whole wheat), lentils
(raw, cooked with and without salt), chickpeas/garbanzo/bengal gram, chickpea
flour (besan), clarified butter (ghee), turmeric, mustard seed and mustard
greens.

The following are **absent from this source** and are deliberately not
substituted, approximated or invented: paneer, idli, dosa, rajma, toor dal,
moong dal, masoor dal, urad dal, curd/dahi, mango, whole cashew and tuna. They
are catalog-extension candidates for a future release rather than gaps to be
filled with invented values.

A practical consequence: `docs/food-scanner.md` describes the scanner
prompting for components such as dal, sambar, curd and raita. Several of those
will not resolve to catalog rows from this release.

## Generation

```bash
# 1. download and verify the pinned artifact, then extract it
node scripts/food-catalog/fetch-source.cjs

# 2. generate V9, catalog.json and rejections.json
node scripts/food-catalog/generator.cjs "$TEMP/fittrack-food-catalog/extracted"

# 3. independently re-check every seeded value against the raw source
node scripts/food-catalog/verify.cjs "$TEMP/fittrack-food-catalog/extracted"
```

| Component | Version |
| --- | --- |
| Generator | 1.0.0 |
| Category mapping | 1.0 |
| Brand filter | 1.0 |

Outputs: `V9__seed_food_catalog.sql`, `scripts/food-catalog/out/catalog.json`
and `scripts/food-catalog/out/rejections.json`.

### Reproducibility

```
same ZIP sha256 + same generator version + same config  =>  byte-identical output
```

Guaranteed by: explicit sorts on every collection before it is written, numeric
(not lexical) FDC id ordering, LF line endings, UTF-8 without BOM, fixed
numeric literal formatting, UUIDv5, and no timestamps or randomness in the
output path.

Selection order is: pinned staple anchors, then the South Asian set, then a
**round-robin balanced fill** across categories in a fixed order. The balanced
fill exists so a single large category cannot crowd out the others; an
alphabetical slice produced a catalog that was 94% dairy, which is not a usable
food catalog.

**Changing the category map, the brand rules, the anchor list or the UUID
namespace changes the output.** That must be shipped as a **new** Flyway
migration, never as an edit to an already-applied V9.

## Catalog selection

Selection is fully deterministic. There is no sampling, no scoring against a
model, and no dependence on source row order. Every stage sorts explicitly
before it is read.

### Candidate pool

| Stage | Count |
| --- | --- |
| SR Legacy foods in the pinned artifact | 7,793 |
| Passed category, brand, name and seven-nutrient validation | 3,165 |
| After deterministic deduplication | 3,165 (0 collisions) |
| **Selected into the catalog** | **200** |

The pool is far larger than the catalog, so selection is a **curation** problem
rather than a scarcity problem. Nothing is rejected for lack of candidates.

### Ranges

| Bound | Value |
| --- | --- |
| Target range | 120-180 |
| Hard range | 50-200 |
| Actual | 200 |

Generation **aborts** rather than emitting a catalog below 50 rows.

### Algorithm

Three ordered passes. A record is added once and never reconsidered.

1. **Pinned staple anchors.** 46 FDC ids listed in `config.cjs`, each verified
   present in the pinned artifact and carrying all seven nutrients. These are
   identifiers only - no nutrition value is ever hand-entered, and each anchor
   still passes every filter and validation like any other record. They exist so
   the catalog always contains recognisable staples: rice, wheat flour, oat
   bran, lentils, chickpeas, tofu, milk, yoghurt, egg, ghee, chicken, beef,
   pork, lamb, cod, salmon, tuna, potato, tomato, onion, spinach, carrot,
   broccoli, cauliflower, cabbage, cucumber, mustard greens, banana, apple,
   orange, pineapple, peanuts, almonds, cashew, sesame, olive oil, turmeric and
   mustard seed.
2. **South Asian priority.** The 14 FDC ids that are actually present in SR
   Legacy: chapati/roti (plain and whole wheat), naan (plain and whole wheat),
   lentils (raw, cooked with and without salt), chickpeas (raw and cooked),
   besan, ghee, turmeric, mustard seed and mustard greens.
3. **Balanced round-robin fill.** The remainder is drawn one record at a time
   from each category in turn, cycling through this fixed order until the target
   is reached:

   `LEGUMES` -> `GRAINS` -> `DAIRY` -> `EGGS` -> `VEGETABLES` -> `FRUITS` ->
   `NUTS_SEEDS` -> `MEAT` -> `SEAFOOD` -> `OILS_FATS` -> `SPICES` -> `OTHER`

   Within a category, records are ordered by `name` then numeric FDC id, so the
   draw order is fixed.

### Why round-robin rather than a global sort

The original specification's overflow rule - sort by category, then name, then
FDC id, and truncate - was deterministic but produced a catalog that was
**187 of 200 records DAIRY**, mostly obscure cheese variants. It satisfied the

## Rejection reporting

No record is discarded silently. Every rejection is written to
`out/rejections.json` with its FDC id, description, category, the rule that
produced it, and a reason:

`EXCLUDED_CATEGORY` · `BRANDED_PRODUCT` · `MISSING_NUTRIENT` ·
`INVALID_NUTRIENT` · `NEGATIVE_NUTRIENT` · `INVALID_NAME` · `DUPLICATE_NAME` ·
`OVERFLOW_TRIM`

**Nutrition values are never zero-filled, imputed or estimated.** A record
missing any of the seven required nutrients is rejected outright, which shrinks
the catalog; that is the intended behaviour. `<` threshold values, non-numeric
values and negatives are rejected rather than coerced.

Observed for the pinned artifact: `EXCLUDED_CATEGORY` 2,864 ·
`MISSING_NUTRIENT` 1,171 · `BRANDED_PRODUCT` 593.

## Attribution and licensing

Attribution is **not legally required** for this source: it is public domain,
not CC-BY. It is provided voluntarily in the V9 header and in this document.

During research, the data.gov catalogue entry for FoodData Central was found to
declare the U.S. Government Public Domain Label 1.0, supported by 17 U.S.C.
§105.

Two caveats are recorded rather than dismissed:

- The data.gov entry describes a **web-page resource**, not the CSV files
  themselves, so the public-domain determination rests on the dataset's
  catalogued licence plus §105.
- <https://www.usa.gov/government-copyright> is explicit that *"Not everything
  that appears on a federal government website is a government work... Content
  on federal websites may include protected intellectual property used with the
  right holder's permission."* No claim is made that all content on USDA
  websites is automatically public domain. The layered brand filter described
  above is the mitigation, not a licence workaround.

Rejected alternatives, for the record: **Open Food Facts** is ODbL, a share-alike
licence that would attach obligations to a derived database; **IFCT 2017** on
Zenodo carries "Rights License: Other (Open) — No further description", which is
unverifiable; **INDB** depends on IFCT tables that its own README says must be
requested from the original source.

This section is an engineering assessment, not legal advice. Trademark or
intellectual-property questions should be confirmed by counsel before
commercial distribution.

## Known runtime limitation

The scanner's **AI analysis path has not been exercised against a live
provider**, because no AI provider key is configured in this environment. What
was verified at runtime:

- image upload validates declared MIME type, magic bytes and size, and persists
  a `food_scans` row
- with no provider configured the scan records `status = failed` and
  `error = "Food scanning is not configured"`, and the API reports it
- no external AI request is attempted

Catalog matching, correction by item id, and transactional confirmation are
covered by `NutritionContractAcceptanceTest` and `ScannerNutritionAcceptanceTest`
using a test-scoped provider. A live end-to-end scan against a real provider
remains unverified.

letter of the rule and failed the purpose of it: a food catalog with no fish,
no meat and one vegetable is not usable for logging a real meal.

Round-robin balancing was adopted instead. The result is:

| Category | Count | Category | Count |
| --- | --- | --- | --- |
| `VEGETABLES` | 25 | `SEAFOOD` | 17 |
| `LEGUMES` | 24 | `SPICES` | 16 |
| `GRAINS` | 21 | `DAIRY` | 16 |
| `MEAT` | 18 | `EGGS` | 15 |
| `FRUITS` | 18 | `OILS_FATS` | 15 |
| | | `NUTS_SEEDS` | 15 |

No filler is added to reach a number. If fewer than 50 records survived, the
generator would fail rather than pad the catalog.


