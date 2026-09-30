# AI Coach

The Coach turns a window of your own recorded activity into a short, structured review: a summary,
what stands out, suggestions, next steps, and anything worth checking.

It is a **single request, single response** feature. There is no conversation, no message history and
no memory: asking again is a fresh request, not a follow-up turn. Nothing about a Coach answer is
stored.

## Endpoint

```
POST /api/v1/coach/insights
Authorization: Bearer <jwt>
Idempotency-Key: <client-generated-key>     # optional
```

### Request

| Field | Type | Rules |
|---|---|---|
| `question` | string | Optional. Max **500** characters. A present-but-blank value is rejected with `400`. |
| `window_days` | number | Optional. One of **7**, **30**, **90**. Default **7**. Anything else is `400`. |

```json
{ "question": "Should I deload this week?", "window_days": 7 }
```

There is no `user_id` field and none is read. The owner comes from the JWT subject alone, so posting
one cannot widen your visibility.

### Response

```json
{
  "summary": "A steady week with good consistency.",
  "observations": ["Three completed sessions.", "Protein averaged above target."],
  "recommendations": ["Keep the current weekly rhythm."],
  "next_actions": ["Schedule next week's first session."],
  "warnings": ["Recovery trended down midweek."],
  "model": "gpt-4o-mini",
  "request_id": "0f3cÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¦"
}
```

`request_id` is the same correlation id that appears in the error envelope and in server logs, so a
reported failure can be traced without exposing anything about you.

### Errors

| Status | Meaning | Retry? |
|---|---|---|
| `400` | Invalid window, or a blank/over-long question | Fix the request |
| `401` | Not authenticated | Sign in again |
| `409` | `coach_request_already_processed` ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â the idempotency key was already used | Ask again with a new key |
| `429` | AI quota exhausted | Wait for the next minute |
| `502` | Provider timeout, error, or a response that failed validation | Yes, shortly |

No provider internals, credentials or stack frames are ever included.

## Idempotency

Send `Idempotency-Key` to make a retry safe.

- **First request** with a key executes normally: the provider is called at most once and usage is
  accounted as usual.
- **A repeat** of the same key by the same user returns **`409 Conflict`** with code
  `coach_request_already_processed`. The provider is **not** called again.
- Keys are scoped per user, so two users may independently use the same key value.
- The claim is atomic, so concurrent duplicates still produce exactly one provider call.

The previous answer is **not** reconstructed. A Coach response is generated text with no persisted
form, and storing it to enable replay would mean persisting a response body ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â which this design
deliberately avoids. A replay therefore tells you the request already ran; ask again with a new key
for a new answer.

## Quotas and rate limits

## Daily token ceiling

`AI_TOKENS_PER_DAY` bounds daily token usage per user. Phase 22 changed the **default** from
`0` to **100000**, the figure this document already named as the production ceiling.

- The ceiling is **feature-scoped**: a Coach call and a food scan draw on separate budgets, because
  `ai_quota_counters` is keyed by `(user_id, feature, window_type, window_start)`.
- It is **per user**, so one account exhausting its allowance cannot affect another.
- It is checked **before** the provider is called, so a refused request is never billed.

Setting `0` still means unlimited and `ProductionConfigValidator` still refuses to start
production with a value `<= 0`. Phase 22 did not weaken that guard; it only stopped `0` from being
the value a deployment gets without opting in.

## What the Coach can see

Context is assembled server-side, from an explicit allowlist of columns, and sent as structured JSON.
Every query is scoped to the authenticated owner.

**Included:** step and sleep aggregates, readiness/stress/HRV/hydration, meal counts and macro
averages, completed-session counts and totals, habit adherence, goal titles and targets, a body-weight
*trend* (first, last, and change ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â not the daily series), and recent personal records.

**Never included:** email or display name, any user or database identifier, `fitness_profile.limitations`
(free text a user may use for medical information), health-device identifiers, raw exercise sets, raw
food-scan history, or any health condition.

The payload is capped at 24 KB. If a window would exceed that, sections are progressively dropped and
the reduction is recorded inside the payload, so the model is never misled about what it is looking at.

The Coach uses the configured **text** model (`AI_TEXT_MODEL`), not the vision model. The food scanner
is unaffected and continues to use the vision model.

## Canonical workout context

Phase 21 established that training activity is a completed quick log **or** a completed
structured session, and `WorkoutAnalytics` is the single tested definition of that. The Coach
now reads through that service instead of the legacy `workouts` table alone.

Before this, a user who logged every session through the structured logger was told by the Coach
that they had completed no sessions while their own dashboard showed otherwise. Both surfaces now
report the same number for the same day.

`avg_effort` is the one measure `WorkoutAnalytics` does not expose, so the Coach computes it
in a single narrow query across both tables. It carries no counting semantics, and it is a
pooled mean (total effort over total rows) rather than an average of two averages.

## Timezone semantics

The Coach resolves "today" through `AnalyticsTimezoneResolver`, the same component the analytics
endpoints use, reading `app_users.timezone` and falling back to UTC when none is set.

It previously read `LocalDate.now(ZoneOffset.UTC)` directly, so for anyone east or west of UTC the
Coach's "last 7 days" ended on a different day than the dashboard they had just looked at. The
`window_days` API contract is unchanged: still 7, 30 or 90, still inclusive of the current day,
now resolved in the user's own calendar.

## Nutrition fiber

Coach nutrition context includes `avg_fiber_g`, aggregated from `meals.fiber_g` on the same rows
as every other macro, matching the Phase 21 analytics aggregation. A meal written before fiber
existed carries a null there and is skipped rather than counted as a zero.

## Prompt construction

Three provider messages are sent, in order:

1. **system** ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â role, safety boundary, data-handling rules, output contract. The only place
   instructions live.
2. **user** ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â the optional question, fenced and labelled as untrusted data.
3. **user** ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â the server-generated context, fenced and labelled as untrusted data.

The question is never concatenated into the system message. Fencing and labelling are defence in depth
rather than a guarantee: an adversarial question can still try, which is why the safety rules are also
enforced by output validation and stated in the UI.

## Structured output

Requests use `response_format: { "type": "json_object" }` ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â the same mechanism the food scanner already
proves against this provider, so no new mechanism was introduced.

Syntactically valid JSON is **not** trusted. Every field is range-checked server-side:

- `summary` is required, must be a non-blank string, max 2000 characters.
- Each array must be an array of strings, max 12 items, each item max 500 characters.
- Absent or null arrays become empty lists; a wrong type is a fault.
- Unknown extra fields are ignored, not propagated.

Anything that fails is treated as a malformed provider response: it is accounted with a `malformed`
category and returned as `502`. Malformed output never reaches the frontend as an apparently valid
Coach answer. A markdown-fenced response is rejected rather than unwrapped, so no wrapper is ever
accepted.

## Safety boundary

The Coach offers **general fitness and wellness guidance only**. It does not diagnose conditions,
prescribe or adjust medication, recommend treatment, or replace a doctor, physiotherapist or
dietitian. For questions needing diagnosis, treatment or medication advice it says it cannot help and
refers to a qualified professional; urgent symptoms are directed to emergency services or urgent care.

This boundary is enforced in three places: system instructions, server-side output validation, and a
permanent disclaimer in the Coach surface. There is **no medical classifier** in the repository, and
none is claimed ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â the boundary is instruction, validation and disclosure.

## Privacy

- No prompt or response is persisted.
- No chat history or insight-history table exists.
- No AI response caching and no streaming.
- No database migration was added in Phase 9.
- Account deletion behaviour is unchanged.
- When the provider is unavailable, the UI can show a clearly-labelled summary built locally from your
  own numbers. It is deterministic and is **never** presented as AI output.

## Model allowlist

`app.ai-text-model` and `app.ai-vision-model` are free-form strings. Before Phase 22 a typo such as
`gpt-4o-minni` was accepted at startup, billed, and surfaced as a 502 that looked like a provider
outage. `AiModelAllowlist` now validates both at construction and **fails startup** with a
message naming the property, the offending value and the supported set. It never prints the API key.

The supported list is configuration, not code: `app.ai-supported-models` is a comma-separated
list of identifiers this deployment may select, defaulting to `gpt-4o-mini`. Adding a model the
provider already accepts is a configuration change, not a code change. The tests run against
`fake-*` stand-ins and list those in the same property; there is no test-only bypass.

## Cost estimates

`ai_usage.estimated_cost` is an **application-side estimate** computed from
`app.ai-limits.pricing.input-per-million` and `output-per-million`. It is **not provider
billing**, and the application never presents it as such.

Both default to `0`. This repository contains no price list for any model, so no default is
asserted - inventing one would put a fabricated figure into an accounting column. Operators set
both from their provider's current published rates. With both at `0` the arithmetic is skipped and
`estimated_cost` stays `null`, while token counts are still recorded, so usage stays observable
even when cost is not.

## Deferred and legacy

The following were identified during the Phase 22 audit and are deliberately **not** changed here.

- **Output safety classification.** The Coach's medical boundary is enforced by system
  instruction, structural response validation and a visible UI disclaimer - there is no
  classifier that inspects generated text. Adding one needs a product and policy decision,
  because a classifier that false-positives on ordinary training pain degrades the product and
  one that misses a real crisis is worse than none. Explicitly deferred.
- **Conversation, chat history and streaming.** The Coach remains a single-request,
  single-response endpoint. Deferred.
- **A `coach_notifications` producer.** The table exists and ProfileView renders it, but
  nothing in application code writes a row. No producer was added. Deferred.
- **Supabase Edge Functions.** `supabase/functions/analyze-food-photo` and `supabase/functions/weekly-coach`
  remain in the repository as reference only. **Spring Boot is the active runtime**; those
  functions are legacy, are not executed, and are not maintained. They are not a second AI
  system and were neither modified nor deleted in Phase 22.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `AI_TEXT_MODEL` | `gpt-4o-mini` | Model used by the Coach |
| `AI_VISION_MODEL` | *(unchanged)* | Model used by the food scanner |
| `AI_KEY` | *(empty)* | Provider key; empty means the Coach reports 502 |
| `AI_TOKENS_PER_DAY` | `100000` | Daily token ceiling; `0` means unlimited and production refuses to start |
| `AI_SUPPORTED_MODELS` | `gpt-4o-mini` | Model identifiers this deployment may select |
| `AI_INPUT_PRICE_PER_MILLION` | `0` | Estimate input rate; `0` leaves `estimated_cost` null |
| `AI_OUTPUT_PRICE_PER_MILLION` | `0` | Estimate output rate; `0` leaves `estimated_cost` null |
| `AI_COACH_TIMEOUT_MS` | `20000` | Interactive Coach request budget |
| `RATE_LIMIT_COACH_REQUESTS` | `20` | Coach HTTP bucket size |

Output is capped at 800 tokens per response. There are **no automatic provider retries** in Phase 9.

### Timeout enforcement

`AI_COACH_TIMEOUT_MS` (default `20000`) is applied as a **real read and connect timeout on the Coach's
own HTTP client**, not as a post-hoc elapsed-time check. A hung provider is abandoned at the socket, so
an interactive request never occupies a thread for the scanner's 45 s budget.

The Coach and the food scanner deliberately use **separate clients over the same base URL and key**,
because a socket timeout is a property of the connection factory: sharing one client would either slow
the scanner down or leave the Coach unprotected. The scanner keeps its original 10 s connect / 45 s read
budget unchanged, and narrowing `AI_COACH_TIMEOUT_MS` never narrows it. A non-positive value is floored
to 1 ms rather than left as "wait forever", which is the failure mode a zero read timeout would produce.

A timeout is categorised as `timeout` in `ai_usage`, returns the standard `502` envelope, and leaks no
provider body, credential or stack frame.


These are two independent layers and both must pass. The different numbers are intentional.

| Layer | Value | Scope |
|---|---|---|
| Coach HTTP bucket | **20 / minute** | Transport protection for the endpoint |
| AI Coach quota | **10 / minute** | Provider-consuming Coach operations |

- The **HTTP bucket** guards the endpoint itself. It is keyed by the JWT subject and lives in a
  dedicated `coach` bucket, separate from the general `ai` bucket that the food scanner and composite
  operations use. Giving Coach its own bucket means its transport limit can be tuned without
  changing scanner behaviour.
- The **AI quota** is the real control. It is enforced in the database, is race-safe under
  concurrency, and is the limit that actually bounds provider spend. Exceeding it returns `429` and
  the request never reaches the provider.

A transport refusal is `rate_limited`; a quota refusal is not. They are not interchangeable.
