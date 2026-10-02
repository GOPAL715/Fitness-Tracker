docs/health-integrations.md
# Health integrations

AI FitTrack stores a user's own health metrics. Phase 10 added the ability to hold **several sources
for the same day** - one hand-entered row and one row per connected device - and to decide, once and
deterministically, which value a reader sees. This document covers the storage model, the
integration boundary, and what is deliberately not implemented.

## Data model

A day is a **set of source records**, not a single row. See
[database.md](database.md) for the column and index detail.

| Concept | Meaning |
|---|---|
| Manual record | `provider_record_id IS NULL`. Unique per user per day. |
| Provider record | Carries `source`, `provider_record_id` and `device_id`. Many per day. |
| Canonical value | What a reader sees. One value per user per day, resolved per field. |

### Canonical selection

Resolved independently for each field, so a source that reports steps but not heart rate does not
erase a heart rate the user recorded by hand.

1. `NULL` is skipped. It means "not measured" and never becomes 0.
2. The source with the strongest rank wins. Ranking is explicit, not alphabetical:
   `health-connect` (10), `fitbit` (20), `garmin` (30), `apple-health` (40), any other (50), and
   **manual last (100)**. Lower wins, because the views take the first element of an ascending sort.
3. Equal ranks are broken by `device_id`, then `provider_record_id`. Both are stable identifiers,
   so the result is reproducible.
4. Import timestamps are **not** used to judge quality. Re-importing an old record does not make it
   a better measurement, and ordering by it would let analytics change without the data changing.
5. Competing values are **never summed or averaged**.

Steps, calories and active minutes are **per-day totals** in this contract, not additive events. Two
sources reporting the same day are two competing measurements of one quantity, so summing them would
double-count. Individual workouts are a different shape and belong in the `workouts` table.

Implemented as `v_daily_metrics_canonical` and `v_body_metrics_canonical`. Every reader that needs
one value per day uses them, so analytics, the calendar, app data and the AI Coach all agree.

## Unit normalisation

Canonical units are **pounds**, **minutes**, **kilocalories**, **steps**, and **percent body fat**.

`HealthNormalizer` is the single conversion point. The pipeline is fixed:

```
provider raw payload        a real adapter - none exists in this repository
     -> HealthNormalizer    raw units -> canonical units
     -> HealthProvider      the canonical contract: pounds, minutes, kilocalories
     -> HealthRecordValidator  technical validation, no medical judgement
     -> HealthSyncService   batched, owner-scoped persistence
```

Conversion happens **once**, in the adapter. `HealthSyncService` never converts, and
`HealthNormalizationBoundaryTest` asserts it holds no conversion helper. `HealthNormalizer` cannot
be runtime-verified against Health Connect, because no adapter exists and the native bridge is a
separate repository.

## Validation

`HealthRecordValidator` is **technical only**. It rejects a missing record id, a null or future date,
a negative count, a non-positive weight, a body-fat percentage over 100, and identifiers that do not
fit their column.

It makes **no medical judgement**. AI FitTrack has no clinical reference ranges, so a low HRV or an
unusual body-fat value is the user's real data and is kept. Rejected records are counted and
reported, never fatal, and never surface as an HTTP 500.

## Google Health Connect boundary

**Health Connect is Android-native. This Spring backend cannot and does not access it directly.**

```
Android AI FitTrack application
        |  Health Connect APIs (Android-only, on-device)
        v
Normalised health payload, authenticated with the user's existing AI FitTrack JWT
        |
        v
AI FitTrack backend  ->  HealthSyncService  ->  PostgreSQL
```

What exists here: the provider-neutral storage model, the normalisation boundary, technical
validation, and the authenticated sync contract. What does **not** exist: an Android app, an
HealthKit or Health Connect client, an OAuth exchange, or any network call to Google.

Deliberately **not** invented, because no such API is documented for a server:
OAuth URLs, client IDs, client secrets, REST endpoints, scopes, quotas, token formats.

The native bridge is a **separate repository**. When it exists it will post an already-normalised
payload through the existing authenticated contract; it must not be modelled as the server calling
Health Connect.

## Phase 20: the integration foundation

Phase 20 added the integration **surface** — a server-authoritative provider catalogue and one
explicit connection state — and closed a data leak in the app-data route. It deliberately added no
table, no migration, no credential storage, no OAuth and no background job.

### The catalogue is served, not hardcoded

`GET /api/v1/health/integrations` returns the providers this build knows, merged with the caller's own
connections:

```jsonc
{
  "integrations": [
    {
      "provider": "health-connect",
      "label": "Android Health Connect",
      "availability": "native_bridge",        // native_bridge | server_credentials_required
                                              // | native_app_required | manual
      "auth_model": "A separate Android app on your phone, using your existing AI FitTrack sign-in",
      "credential_model": "none",
      "supported_metrics": ["steps", "active_calories", "weight", "body_fat"],
      "connectable": true,
      "boundary": "Health Connect is an Android-native, on-device API, ...",
      "connection_state": "connecting",
      "connections": [ /* this user's own connections */ ]
    }
  ]
}
```

Before this, the provider list lived only in `src/lib/healthProviders.ts` while the authoritative
allowlist lived in the backend, and nothing kept them in step. The browser could therefore be shown a
provider the server would refuse on registration. `HealthProviderCatalog` is now the single
description, and `HealthProviderCatalogTest` asserts it covers exactly `HealthProviders.SUPPORTED`, so
neither can acquire a provider the other has not heard of.

`fake-wearable` stays in the allowlist so the in-process test double attributes its rows consistently,
but it is **never served to a client**: it reaches no external service.

`credential_model` is `"none"` for every provider, and that is a fact rather than an omission. No
adapter performs an OAuth exchange, so AI FitTrack holds no provider access token, refresh token or
client secret, and there is no column to hold one.

### Connection state

One state per provider, derived from `health_devices.sync_status`. **No column was added.**

| Stored `sync_status` | Reported state |
| --- | --- |
| *(no row for this provider)* | `disconnected` |
| `idle` | `connecting` |
| `syncing` | `syncing` |
| `synced` | `connected` |
| `error` | `sync_failed` |

`connecting` means registered but never synced — the connection exists, is owned by the user, and has
not yet produced data. That is what `HealthDeviceResponse.awaitingFirstSync` already meant, and what the
old UI already rendered as "Connected, not synced yet". Phase 20 gives that existing fact a name the
API contract can state.

Two rules the model enforces:

- **An unreadable status is a failure, never a success.** A value the server does not recognise maps
  to `sync_failed`, because a state it cannot read is not evidence that anything worked. Defaulting it
  to `connected` would show a green badge on the strength of an unknown value.
- **A provider with several connections reports the most actionable one.** One provider can own two
  watches. Priority is `sync_failed` > `syncing` > `connecting` > `connected`, evaluated as a maximum
  rather than first-wins, so the badge does not change with row order. All connections are still listed.

### The app-data leak this phase closed

`GET /api/v1/app-data` read `health_devices` with `SELECT *`. That published two columns on every
call:

- **`sync_cursor`** — the server's internal day watermark. It is not cosmetic: it decides the window
  the next sync re-reads. `docs/database.md` already promised it was never returned by the API, and
  the dedicated `/health/devices` route did exclude it — the aggregate route undid that exclusion.
- **`client_changes_token`** — the Android client's own opaque resume handle. V11 keeps it strictly
  separate from the server's cursor precisely so the two cannot be conflated.

`AppDataService` now selects an explicit allowlist for this table, so a column becomes visible because
it was named rather than because it exists. Other tables keep `SELECT *`: none holds a secret or a
resumable cursor, and rewriting eight unrelated projections would put this fix at risk for no benefit.

`HealthIntegrationController` and `HealthDeviceResponse` follow the same allowlist rule.

### `permission_status` completed

The V11 column existed, was written by every Health Connect ingest, was declared in the frontend types
and was rendered by the profile screen — but no DTO carried it and no query selected it, so the
permission badge could never appear. Phase 20 adds it to the allowlist and the DTO.

It remains a **device-reported claim**. The server cannot observe Android Health Connect permissions,
so nothing in the authentication or authorization path reads it and it can neither grant nor deny
access. The UI phrases it as what the app reports.

### Why no migration

Every change above is application-layer. The catalogue and the state model are derived from columns
that already exist; the leak fix is a projection change; `permission_status` was already in the
schema. An empty or no-op V15 would be a permanent historical artifact claiming a schema change that
did not happen, so none was added. **The next migration number remains V15.**

## Tokens and encryption

**No provider token is stored anywhere.** The registration endpoint rejects `access_token`,
`refresh_token`, `client_secret` and `token`, and no column exists to hold one.

D6 - application-level AES encryption with an environment-provided key - is **approved but
deliberately not implemented yet**, because there is no caller for it. Speculative crypto with no
credential to protect is untested code that invites misuse. It **must** be implemented before any
server-held provider credential is persisted: environment-provided key, AES-GCM, never logged, never
returned through an API, with enough metadata for rotation.

## What a provider value is

`health_devices.provider` is a **declared integration type**, validated against the allowlist in
`HealthProviders`. It is **not** proof that the user owns an external account: no integration
performs an OAuth exchange yet, so there is nothing to attest against. An unrecognised value is
refused, so a typo or a fabricated string cannot create a permanently unattributable row.

When a real adapter lands it becomes authoritative: the adapter will establish the provider from the
credential it was issued, and the client-supplied value will only select which pipeline to run.

## Sync

**Manual only.** There is no scheduler and no `@Scheduled` job. A sync runs when a user or a client
asks for one.

Within a pass, pages are followed until the provider stops returning a cursor, with a page cap and
cycle detection. The **persisted cursor is a day watermark**, not a provider token: the two are
separate, because reusing the watermark as a resume token would hand a date to a provider expecting
its own opaque token, and storing the provider's offset as the watermark would make the next pass
skip records the provider corrected in place.

## Disconnect

Disconnecting removes the connection and stops future syncing. **Imported history is kept.** The
foreign key is `ON DELETE SET NULL`, so the pointer is dropped and the metrics survive. `source`
and `provider_record_id` remain, so provenance is still readable. The API says so explicitly, and
the UI states it in the same words, because claiming a deletion that did not happen would be worse
than silence.

No provider token is revoked on disconnect, because none is stored.
## Phase 11: Health Connect ingestion contract

The server half of the Health Connect path is implemented. The Android app that consumes it is a
**separate repository** and does not exist yet.

```
Android app  ->  Health Connect APIs (on-device)  ->  POST /api/v1/health/devices/{id}/records
                                                            |
                                                            v
                                  validate -> normalise -> ledger -> aggregate -> canonical views
```

### Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/health/devices/{id}/records` | Ingest a bounded batch of source records. |
| `GET`  | `/api/v1/health/timezone` | Read the caller's stored IANA zone and whether one is set. |
| `POST` | `/api/v1/health/timezone` | Set the caller's IANA zone. Validated against `ZoneId`. |

`POST /devices/{id}/sync` is unchanged and remains the **server-pull** contract. The two routes are
deliberately separate: one has the server calling a cloud provider, the other has a device pushing
to us. They have different auth shapes and two different, unrelated "cursor" concepts.

### Request shape

```jsonc
{
  "timezone": "Asia/Kolkata",        // required, IANA, validated, never defaulted
  "records": [
    {
      "record_id": "<Health Connect metadata.id>",
      "record_type": "steps",         // steps | active_calories | weight | body_fat
      "start_time": "2026-05-05T18:00:00Z",
      "end_time":   "2026-05-05T20:00:00Z",
      "value": 600,
      "unit": "count"                 // count | kcal | kg | percent, must match the type
    }
  ],
  "deleted_record_ids": ["..."],     // record ids the source reports as deleted
  "changes_token": "<opaque>",       // the client's own Health Connect handle
  "permission_status": "connected"   // client-reported UX state only
}
```

Deliberately absent: `user_id` (ownership comes from the JWT), `provider` (the device's registered
provider is authoritative), and every credential field. Health Connect needs no server-held secret,
so there is no OAuth exchange, no token table, and no client secret.

### Platform semantics this contract is built on

Verified against Google's Health Connect documentation:

- `StepsRecord` and `ActiveCaloriesBurnedRecord` are **interval** records. Their value is the total
  over `[startTime, endTime]`, and summing a period's records gives the period total. AI FitTrack
  therefore **adds** them per day.
- `WeightRecord` and `BodyFatRecord` are **instantaneous** measurements, aggregated only as
  `WEIGHT_AVG/MAX/MIN` and never as a total. AI FitTrack therefore **selects** the latest reading per
  day and never sums them.
- A deletion is reported as a **record id only** - the entry carries no value and no timestamps. That
  is why AI FitTrack retains a source ledger: without it a deletion could not be reconciled.
- Timestamps are absolute instants. The local calendar day is derived on the server from the
  caller's IANA zone, because the platform does not supply a user calendar.

### Aggregation and reconciliation

Source records are written to `health_connect_records` first, and daily aggregates are **always
recomputed from that ledger**, never incremented. Increments would drift on correction and would be
unrecoverable after a deletion. A day whose source records are all deleted has its derived row
**removed**, not zeroed, so the canonical views fall through to whatever else exists (including a
manual entry) rather than reporting a fabricated zero.

An interval crossing local midnight is split by **duration overlap** across the days it touches, and
the split always sums back to the original value. Proportional splitting is an approximation and is
documented as such in `HealthConnectAggregator`; the alternatives lose data or overstate a day.

### Limits and abuse protection

| Control | Value | Notes |
| --- | --- | --- |
| Max records per batch | 500 | AI FitTrack's own limit, not a Google quota. Exceeded returns 413. |
| Max request body | 1 MB | Enforced at the filter, before the body is buffered. |
| Rate limit | 60 requests/min | Reuses the Phase 16 `RateLimitService`, keyed by authenticated user. |
| Accepted history | 30 days | Bounds retention so a bridge cannot make the server keep everything. |

### Permission states are UX only

`permission_required` and `permission_revoked` are **client claims**. The server structurally cannot
verify Android Health Connect permissions, so nothing in the authentication or authorization path
reads this state, and it can neither grant nor deny access. The web UI labels these as what the app
reports, and presents no way to connect Health Connect from the browser.