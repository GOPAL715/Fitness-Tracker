# Spring Boot API

Base URL: `http://localhost:8080/api/v1` locally. Production base URL and TLS are deployment-specific.

## Authentication and authorization

`/api/v1/auth/**`, `/actuator/health`, and `/api/v1/health` are public. All other endpoints require `Authorization: Bearer <access-token>`. Access JWTs expire after 15 minutes; refresh tokens expire after 30 days and rotate on refresh. Role claims are not currently enforced and no role-based endpoint is implemented.

## Endpoints

| Method | Path | Auth | Request | Actual response |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/auth/register` | No | `{ "email": "...", "password": "..." }` | `{ "accessToken", "refreshToken", "expiresAt" }`; serializes as snake case. |
| POST | `/api/v1/auth/login` | No | Same credentials | New token pair. |
| POST | `/api/v1/auth/refresh` | No | `{ "refreshToken": "..." }` | Rotated token pair; prior refresh token is revoked. |
| POST | `/api/v1/auth/logout` | No | `{ "refreshToken": "..." }` | Empty `200` response; revokes token when found. |
| GET | `/api/v1/calendar/{date}` | Yes | ISO date, e.g. `2026-01-31` | `{ "date", "summary": { "workouts": 0, "meals": 0, "habits_completed": 0 } }` |
| GET | `/api/v1/calendar/{from}/{to}` | Yes | Two inclusive ISO dates, maximum 366 days | One persisted, owner-scoped summary per date |
| POST | `/api/v1/food-scans` | Yes | Multipart field `file`; non-empty, at most 8 MiB; JPEG/PNG/WebP signature | Persisted review DTO with status and controlled-food items |
| GET | `/api/v1/food-scans` | Yes | None | Owner-scoped scan list |
| GET | `/api/v1/food-scans/{id}` | Yes | UUID scan ID | Owner-scoped scan DTO or 404 |
| PUT | `/api/v1/food-scans/{id}/items` | Yes | Scan item corrections | Recalculated review DTO |
| POST | `/api/v1/food-scans/{id}/confirm` | Yes | Optional meal metadata | Confirmed scan DTO and meal ID |
| DELETE | `/api/v1/food-scans/{id}` | Yes | UUID scan ID | 204 after row and stored-object cleanup |
| POST | `/api/v1/coach/insights` | Yes | Optional `{ "question"?: string (max 500), "window_days"?: 7 \| 30 \| 90 }`; optional `Idempotency-Key` header | Structured `summary`, `observations`, `recommendations`, `next_actions`, `warnings`, `model`, `request_id`. Context is assembled and allowlisted server-side. See [ai-coach.md](ai-coach.md). |
| GET | `/actuator/health` | No | None | Spring health payload. |

Example:

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"local@example.test","password":"replace-with-a-strong-password"}'

curl http://localhost:8080/api/v1/calendar/2026-01-31 \
  -H 'Authorization: Bearer <accessToken>'
```

## Errors and validation

Handled argument errors return `400`; missing entities return `404`; unauthorized requests return `401`,
and denied authenticated requests return `403` through Spring Security. Other failures return a generic
`500`. Authentication failures from Spring Security use the servlet error response rather than the custom
JSON exception shape.

Credential records are validated by the auth service, including required values and minimum password
length. Scanner file validation checks declared MIME type, byte signature, and the 8 MiB limit; it does not
fully decode images.

## Notification settings and push semantics

The notification settings centre is a screen, not a new capability. It reads the four existing push
endpoints and adds no new ones.

### Three independent facts

The screen deliberately never merges these into a single "notifications are on" claim, because each
answers a different question and only one of them is about the user's device:

| Fact | Source | What it does not mean |
|---|---|---|
| Browser support | `Notification`, `PushManager`, service worker in the client | that permission is granted |
| Permission | `Notification.permission` in the browser | that a subscription exists |
| Server configuration | `GET /api/v1/push/config` | that this device is subscribed |
| This device's subscription | the browser's own `PushManager` | that other devices are subscribed |

A deployment with push configured can still have no subscribers; a granted permission can still have
no subscription. The screen shows each separately so a user is never told "you're all set" when only
one of the preconditions holds.

### Permission semantics

- **Not yet asked** (`default`) — the enable button is offered and requests permission from the click.
- **Allowed** (`granted`) — permission is reused, never re-requested.
- **Blocked** (`denied`) — **no enable button is offered and no prompt is ever raised again.** Only the
  browser's site settings can undo this, and repeated prompting is treated as untrustworthy. The
  screen says so plainly instead of presenting a control that cannot succeed.
- **Insecure context** — the Push API is unavailable outside HTTPS. `localhost` counts as secure for
  development. This is reported as its own condition, not folded into "not supported".
- **Unsupported browser** — the API is absent entirely; nothing is offered.

Permission is only ever requested from an explicit user gesture. Nothing on this screen prompts on load.

### Enable and disable sequencing

Enable reports success **only after the server has accepted the subscription**. If the browser
subscribes and the backend then rejects it, the newly-created browser subscription is removed again,
because a registration the server does not hold is a dead record that silently swallows every future
send. If that cleanup also fails, the screen reports the partial state rather than claiming a clean
result. A pre-existing subscription is never torn down by a failed re-registration.

Disable asks the server to drop the row **first**, then unsubscribes locally. The reverse order would
destroy the only copy of the endpoint needed to identify the row. If the delete fails, the browser is
left subscribed and the screen says the subscription is **still active** — it is, because the server
can still deliver to it. If the delete succeeds but the local unsubscribe fails, the screen reports
"Removed from FitTrack. Browser subscription cleanup could not be completed." rather than implying
everything is clean.

### Multi-device behaviour

Push is per browser registration, not per account. Turning notifications off affects **this device
only**; devices registered separately keep receiving reminders. The screen says so explicitly.

The API cannot prove which stored row belongs to which device, so devices are reported as a count
("1 other device") and are never named, labelled or fingerprinted.

### Endpoint non-disclosure

A push endpoint contains a per-installation secret path. The server returns it because it needs it for
delivery, but **no endpoint, hostname, or fragment of one is ever rendered in the UI** — the user has
no functional need for it. The client's `listPushDevices` strips the field entirely rather than
masking it, because a partial endpoint is still a secret fragment.

### Test notification

> Test notification intentionally deferred because the existing delivery architecture does not provide
> a safe device-scoped test path. The only send path fans out to every subscription a user owns and
> records to the real delivery ledger, so a test send would either pollute the user's reminder history
> or require a new delivery path outside this phase's scope.

## Notification preferences

```
GET /api/v1/notification-preferences
PUT /api/v1/notification-preferences
```

User-level notification settings, one row per account. These are **preferences the user set**, and
they are deliberately not the same thing as anything the server can observe about a browser.

```json
{
  "pushEnabled": true,
  "reminderNotificationsEnabled": true,
  "quietHoursEnabled": true,
  "quietHoursStart": "22:00",
  "quietHoursEnd": "07:00",
  "timezone": "Asia/Kolkata"
}
```

Both routes are authenticated and scoped to the JWT principal. There is no path parameter and no
`user_id` is read from the body, so a client that posts one has it ignored — the same property the
push subscription routes have. A read never writes: a user who has never opened the settings screen
reports the defaults and accumulates no row.

`timezone` is `null` until the user chooses one. That is a real answer, not a missing value: quiet
hours resolve a **wall-clock** window, and defaulting an unset zone to UTC would silently place
someone's night in the wrong hours.

### What is deliberately not stored here

| Not stored | Why |
|---|---|
| Browser permission | `Notification.permission` is the browser's answer to the user. The server cannot read it and must not be told. |
| Push subscriptions | `push_subscriptions` already owns devices. Duplicating them would create a second source of truth for a multi-device fact. |
| Delivery state | `reminder_deliveries` is the ledger. A preference says what should be *attempted*, never what happened. |

### Turning preferences off does not unsubscribe anything

Disabling a preference suppresses **future delivery only**. It does not delete a `push_subscriptions`
row and does not pause or delete a reminder. A user who mutes reminders on their phone and later
wants them back has lost nothing.

### Reminder state and user preference are independent

Neither is derived from the other, and neither is ever mutated to represent the other:

| User preference | Reminder | Result |
|---|---|---|
| `pushEnabled: false` | `enabled: true` | Reminder stays enabled and is never deleted. The occurrence is skipped. |
| `pushEnabled: true` | `enabled: false` | Nothing is delivered. Permissive preferences do not resurrect a paused reminder. |

### Policy suppression is not a delivery failure

A reminder deliberately not sent is recorded as its own ledger state, `skipped_policy`, with **no**
`last_error` and **no** `failure_category`.

This is deliberately distinct from every Phase 17 category. `NO_SUBSCRIPTION` means "you have no
registered device", which would be false for a user who chose silence. `TEMPORARY_PROVIDER_ERROR`
and friends mean something failed and was retried; nothing failed here. A user turning notifications
off is not a provider fault, and the history must not say it is.

`skipped_policy` uses the `state` column only. `FailureCategory` is unchanged and a skipped
occurrence never receives one.

### Quiet hours

A wall-clock window in the user's own stored timezone, evaluated with `java.time` only. The server's
zone is never consulted and no UTC offset is ever added or subtracted by hand.

- The window is **half-open, `[start, end)`**. `22:00 → 07:00` means 22:00:00 through 06:59:59.
- `end <= start` is an **overnight** window that rolls past midnight into the next local day.
- `start == end` is **rejected** with `400`. It is ambiguous between "no quiet hours" and "all day",
  and there is no safe reading.
- Enabling quiet hours **requires** a valid IANA timezone. A zone is never defaulted.
- An unusable timezone is rejected, never canonicalised into a guess.

**Daylight saving** is handled by `ZonedDateTime.atZone` against the local date, so the JVM's own
transition rules apply and a wall-clock window keeps its meaning across a transition. A start that
falls in a spring-forward gap moves to the first instant that actually exists locally.

### What happens to a reminder due during quiet hours

It is **deferred**, not dropped and not failed:

1. The occurrence is **not claimed**. The unique key on `(reminder_id, occurrence_at)` is left
   untouched, so the occurrence can still be delivered.
2. `next_occurrence_at` is moved to the instant the window closes. The schedule advances no further,
   so the reminder is not offered a second, different occurrence.
3. On the next tick the occurrence is delivered exactly once.

A deferral is therefore invisible in the ledger until it actually delivers — the reminder is simply
not yet due. This is what keeps a deferral from consuming the retry budget or generating a duplicate.

Because the window's end instant is itself outside the window, a deferral always terminates: the
occurrence becomes eligible exactly at the resume instant and is delivered on the following tick.

The occurrence that eventually records is the deferred one, timestamped at the instant it became
deliverable — the deterministic quiet-hours end, not the originally scheduled time the user asked
not to be woken at.

A **disabled preference takes precedence** over quiet hours, and suppresses rather than defers. A
user who muted everything entirely should not have reminders queued for their morning.

### The existing `reminders.quiet_hours_start` / `quiet_hours_end` columns

These have existed since `V1` and are user-writable, but **they are not enforced anywhere**:
`dueReminders` does not select them, `deliver` never reads them, and the Web Push provider never
sees them. A user can set quiet hours on an individual reminder today and a notification will still
arrive inside that window.

Phase 19 **does not activate, migrate or reinterpret them.** Doing so would silently change delivery
behaviour for every existing user who has one set, with no migration, no consent and no release
note. The two features are independent: the columns remain a per-reminder field with no effect, and
the preferences table is the only enforced quiet-hours mechanism.

## Reminder delivery history

```
GET /api/v1/reminders/{id}/delivery-history?limit=20&offset=0
```

Returns the caller's recorded occurrences for one reminder, newest first, paginated. Scoped to the
owner in the query: another user's reminder answers `404`, the same as an id that does not exist.
`limit` is capped server-side at 100; `hasMore` reports whether a further page exists.

```json
{
  "attempts": [
    {
      "occurrenceAt": "2026-09-29T01:30:00Z",
      "state": "failed",
      "attempts": 1,
      "reason": "permanent",
      "failureCategory": "INVALID_SUBSCRIPTION",
      "deliveredAt": null,
      "recordedAt": "2026-09-29T01:30:01Z"
    }
  ],
  "limit": 20,
  "offset": 0,
  "hasMore": false
}
```

`state` is the recorded outcome: `delivered`, `failed` (permanent, never retried), `exhausted` (the
retry budget was spent) or `pending` (claimed, not yet finished).

`failureCategory` is a closed vocabulary naming the cause in terms the user can act on:
`NO_SUBSCRIPTION`, `INVALID_SUBSCRIPTION`, `RATE_LIMITED`, `TEMPORARY_PROVIDER_ERROR`,
`PROVIDER_REJECTED` or `UNKNOWN`. A successful delivery has no category, and `UNKNOWN` is also what
every record predating the field reports — it is never filled in by guesswork.

`reason` is the coarse value that predates this endpoint (`permanent`, `temporary`, `provider_error`),
retained for backward compatibility. No provider message, exception text, push endpoint, subscription
key or token is ever returned or logged through this route.

Push being enabled or disabled on the server is reported separately by `GET /api/v1/push/config`; it is
deliberately not recorded as a per-reminder delivery failure, because it is a property of the
deployment rather than of the user's reminder.

## Resource contract notes

The Spring backend implements the active frontend resource matrix. Owned resources are exposed under the
versioned `/api/v1` prefix and scoped by the JWT subject. Child resources use parent ownership checks.
`exercises` and `foods` are readable catalogs and reject writes. Parent/child workflows may still require
multiple requests; composite transaction endpoints are Phase 13 work. The Phase 12 security and rollback
matrix remains future verification work, not a claim that every edge case is covered.
