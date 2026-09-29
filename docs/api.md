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
