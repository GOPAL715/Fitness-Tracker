# FitTrack AI — Food Scanner

## Purpose

Turn a photograph of a meal into a logged meal with accurate macros, while
keeping the user in control of every number and never presenting an estimate as
a fact.

## The flow

```
              TAKE FOOD PHOTO
                     |
                     v
              IMAGE UPLOAD
        client-side validation + compression
                     |
                     v
             PRIVATE STORAGE
       food-images/{user_id}/{scan_id}/original.jpg
                     |
                     v
             EDGE FUNCTION
        analyze-food-photo (verify caller)
                     |
                     v
              VISION MODEL
     returns names + grams + confidence ONLY
                     |
        +------------+-------------+
        |                          |
        v                          v
  RESPONSE VALIDATION       (rejects malformed)
        |
        v
   NUTRITION LOOKUP  <-- foods table (58 seeded items)
        |
        v
  NUTRITION CALCULATION  <-- deterministic, per-100g scaled
        |
        v
    USER REVIEW
   edit food / edit portion / delete / add
        |
   +----+----+
   |         |
   v         v
 EDIT     CONFIRM
             |
             v
        SAVE MEAL
     meals + meal_items
             |
             v
   scan marked confirmed, linked to meal
```

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

The model's response is untrusted input. `parseDetections` (client) and the
matching validator in the Edge Function both enforce:

- response must be a JSON object with an `items` array
- 1 to 12 items
- `name` must be a non-empty string of 2 to 80 characters
- `estimated_grams` must be a finite number between 0 and 5000
- `confidence` is clamped to 0..1, defaulting to 0.5 when missing

Anything that fails is dropped. If every item fails, the scan is marked `failed`
with a user-facing message rather than silently producing an empty meal.

## Storage and privacy

- The `food-images` bucket is **private**.
- Path convention `{user_id}/{scan_id}/original.jpg` lets storage policies
  compare the first path segment to `auth.uid()::text`.
- Every operation (read, insert, update, delete) is restricted to the caller's
  own folder.
- The Edge Function reads the image through a 300-second signed URL, so the file
  is never publicly reachable.
- Uploads are limited by the bucket itself to JPEG/PNG/WebP and 8 MB.

## Cost control

- Images are downscaled to 1024 px and re-encoded at quality 0.82 before upload.
- `max_tokens` is capped at 700.
- The prompt asks for the minimum information required.
- Every request is recorded in `ai_usage` with token counts, model, success flag
  and an estimated cost, so spend is measurable per user and per feature.
- Per-user scan limits are the natural next step; the data to enforce them
  already exists in `ai_usage`.

## Confidence handling

Confidence is displayed per item as a percentage with a label, and any item below
0.55 raises an explicit warning:

> Food identification may be uncertain. Please review.

Language throughout uses "estimated", "approximately" and "≈". Nothing implies
photo-derived nutrition is exact.

## Mixed and Indian dishes

The prompt explicitly requests component-level identification for mixed plates —
rice, dal, sambar, curd, papad, biryani, raita and similar. Each component
becomes its own editable line item, and the seeded `foods` table includes South
Asian staples so components usually resolve to real nutrition rows.

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
| AI key not configured | explicit "not configured" message, scanner remains usable manually |
| Provider error | scan marked `failed`, generic user message, logged in `ai_usage` |
| Malformed AI response | rejected, scan marked `failed`, user told to try a clearer photo |
| No nutrition match | item flagged, user prompted to pick the closest food |
| Session expired | explicit sign-in prompt |

## Verification status

The flow is implemented end to end: upload, private storage, edge function,
validation, nutrition lookup, correction UI, recalculation, meal creation and
scan linkage. Automated tests cover validation, food matching, portion maths and
the scan-to-meal transformation (76 tests passing). The live vision call itself
has not been exercised against the provider, because no AI key is configured in
this environment.
