# FitTrack AI — Food Scanner

## Purpose

Turn a photograph of a meal into a logged meal with accurate macros, while
keeping the user in control of every number and never presenting an estimate as
a fact.

## The flow

The active runtime is Spring Boot `ScannerController` and `ScannerService`:

```
multipart file upload
  -> declared MIME + magic-byte + 8 MiB validation
  -> private owner-scoped storage
  -> persisted food_scans row
  -> AiProvider analysis and output validation
  -> controlled food matching and server-side nutrition calculation
  -> user correction through PUT /api/v1/food-scans/{id}/items
  -> transactional confirmation creates meals and meal_items
  -> scan deletion removes the scan and stored object
```

The JWT subject determines the storage owner. The client does not provide an ownership user ID. If the
initial database persistence fails, the service removes the stored object before propagating the error.

## Why the model is never asked for calories

The single most important design decision. Asking "how many calories are in this
image?" produces a plausible-sounding number with no way to verify or correct it,
and the same photo can yield different answers on different runs.

Instead the model is asked only for **what** the food is and roughly **how much**
of it there is. Every calorie and macro is then computed from the `foods` table:

```
foods: 100 g cooked white rice = 130 kcal, 2.7 g protein, 28 g carbs
AI estimate: 200 g
computed: 260 kcal, 5.4 g protein, 56 g carbs
```

The result is reproducible, auditable, and correctable — and swapping the
nutrition source later changes one module, not the whole feature.

## Validation

The model's response is untrusted input. The backend validates the provider result before persisting
items. Detections are constrained to a bounded item count, valid names, finite gram values in the supported
range, and confidence values in the 0..1 range. Nutrition values are calculated server-side from matched
food rows; model-provided nutrition is not trusted as the final value.

Malformed or failed analysis is recorded as a failed scan. A user can correct item selection and grams
before confirmation; confirmation recalculates totals and creates the meal and meal items transactionally.

## Storage and privacy

- Scan objects are stored on the backend's configured private filesystem root, under an owner-scoped key.
- The JWT subject determines the storage owner; a client-supplied ownership ID is not used.
- Upload, retrieval, correction, confirmation, and deletion all check the authenticated owner.
- JPEG, PNG, and WebP signatures are accepted, with an 8 MiB limit.
- Deletion removes the stored object after the owner check.

There is no public object-serving route. Durable managed object storage, retention policy, malware scanning,
and production storage controls remain future operational work.

## Cost control

- Provider configuration and optional usage persistence are backend concerns.
- Tests use a test-scoped `AiProvider`; no real external AI request is required.
- Complete per-user quotas, cost controls, and production provider observability remain Phase 14 work.

## Confidence handling

Confidence is displayed per item as a percentage with a label, and any item below
0.55 raises an explicit warning:

> Food identification may be uncertain. Please review.

Language throughout uses "estimated", "approximately" and "≈". Nothing implies
photo-derived nutrition is exact.

## Mixed and Indian dishes

The prompt explicitly requests component-level identification for mixed plates.
Each component becomes its own editable line item, and a component that matches
a catalog row resolves to real nutrition data.

The seeded `foods` catalog comes from USDA FoodData Central SR Legacy. That
source is US-centric, so coverage of Indian components is **partial**. Being
accurate about which components resolve matters, because an unmatched component
is left uncorrected and the user must pick a food by hand.

Available in the catalog today, and expected to match:

- rice, wheat flour, oat bran, whole-wheat bread
- lentils (raw, and cooked with or without salt)
- chickpeas / garbanzo / bengal gram, and chickpea flour (besan)
- chapati / roti and naan
- clarified butter (ghee), turmeric, mustard seed, mustard greens
- egg, milk, yoghurt, tofu, and the usual vegetables, fruits and nuts

**Not available in this source, and not invented:** paneer, curd / dahi, idli,
dosa, sambar, raita, papad, biryani, rajma, toor dal, moong dal, masoor dal and
urad dal. SR Legacy has no entries for these, so a detection naming one will not
resolve to a catalog row. The user corrects it by choosing a substitute from
the catalog, and the substituted food's nutrition is what gets recorded.

These gaps are a known limitation, not a defect to be papered over with
approximate values. They are tracked as candidates for a future catalog
extension from a source that actually covers Indian foods; see
[food-catalog.md](food-catalog.md) for provenance, the full component
availability list, and the licensing basis.

## Integration with the rest of the app

The scanner is not a standalone feature:

```
Food Scanner -> meals / meal_items -> daily macro totals
             -> profile targets     -> remaining calories and protein
             -> goals               -> goal progress
             -> analytics           -> weekly averages
             -> AI coach            -> nutrition observations
```

Confirmed scans appear in Nutrition labelled "AI scanned", count toward the
daily rings immediately, and are included in the weekly coach's context via
`scanned_meals` and the meal aggregates.

## Failure modes

| Failure | Behaviour |
| --- | --- |
| Invalid file type or size | rejected before upload with a clear message |
| Upload fails | error shown, user returned to the preview step |
| AI provider not configured | provider-dependent analysis may be unavailable; the scanner remains available for manual correction/meal workflows |
| Provider error | scan marked `failed` with a sanitized error; the persisted scan remains owner-scoped |
| Malformed AI response | rejected by backend validation; no unsafe nutrition is persisted |
| No nutrition match | item remains uncorrected and the user can choose/correct a food |
| Session expired | API returns 401; client clears its token state |

## Verification status

The Spring flow is implemented: upload validation, private storage, persistence, provider abstraction and
output handling, nutrition calculation, correction, transactional meal/item confirmation, deletion, and
storage cleanup. Backend tests use a test-scoped `AiProvider`; no live provider call is required. The
historical `supabase/functions/analyze-food-photo` and `weekly-coach` files are migration reference only.
