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
  "request_id": "0f3c…"
}
```

`request_id` is the same correlation id that appears in the error envelope and in server logs, so a
reported failure can be traced without exposing anything about you.

### Errors

| Status | Meaning | Retry? |
|---|---|---|
| `400` | Invalid window, or a blank/over-long question | Fix the request |
| `401` | Not authenticated | Sign in again |
| `409` | `coach_request_already_processed` — the idempotency key was already used | Ask again with a new key |
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
form, and storing it to enable replay would mean persisting a response body — which this design
deliberately avoids. A replay therefore tells you the request already ran; ask again with a new key
for a new answer.

## Quotas and rate limits

## Daily token ceiling

`AI_TOKENS_PER_DAY` bounds daily token usage per user.

- **Development and tests** may set `0`, which means unlimited. That behaviour is unchanged.
- **Production refuses to start** if the value is `<= 0`, because there "unlimited" is an uncapped
  bill rather than a convenience. `ProductionConfigValidator` enforces this.
- The documented production default is **100000** tokens per user per day. Operators may set a
  higher or lower value deliberately.

## What the Coach can see

Context is assembled server-side, from an explicit allowlist of columns, and sent as structured JSON.
Every query is scoped to the authenticated owner.

**Included:** step and sleep aggregates, readiness/stress/HRV/hydration, meal counts and macro
averages, completed-session counts and totals, habit adherence, goal titles and targets, a body-weight
*trend* (first, last, and change — not the daily series), and recent personal records.

**Never included:** email or display name, any user or database identifier, `fitness_profile.limitations`
(free text a user may use for medical information), health-device identifiers, raw exercise sets, raw
food-scan history, or any health condition.

The payload is capped at 24 KB. If a window would exceed that, sections are progressively dropped and
the reduction is recorded inside the payload, so the model is never misled about what it is looking at.

The Coach uses the configured **text** model (`AI_TEXT_MODEL`), not the vision model. The food scanner
is unaffected and continues to use the vision model.

## Prompt construction

Three provider messages are sent, in order:

1. **system** — role, safety boundary, data-handling rules, output contract. The only place
   instructions live.
2. **user** — the optional question, fenced and labelled as untrusted data.
3. **user** — the server-generated context, fenced and labelled as untrusted data.

The question is never concatenated into the system message. Fencing and labelling are defence in depth
rather than a guarantee: an adversarial question can still try, which is why the safety rules are also
enforced by output validation and stated in the UI.

## Structured output

Requests use `response_format: { "type": "json_object" }` — the same mechanism the food scanner already
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
none is claimed — the boundary is instruction, validation and disclosure.

## Privacy

- No prompt or response is persisted.
- No chat history or insight-history table exists.
- No AI response caching and no streaming.
- No database migration was added in Phase 9.
- Account deletion behaviour is unchanged.
- When the provider is unavailable, the UI can show a clearly-labelled summary built locally from your
  own numbers. It is deterministic and is **never** presented as AI output.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `AI_TEXT_MODEL` | `gpt-4o-mini` | Model used by the Coach |
| `AI_VISION_MODEL` | *(unchanged)* | Model used by the food scanner |
| `AI_KEY` | *(empty)* | Provider key; empty means the Coach reports 502 |
| `AI_TOKENS_PER_DAY` | `0` | Daily token ceiling; must be positive in production |
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
