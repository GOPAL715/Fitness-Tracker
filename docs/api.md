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
| POST | `/api/v1/coach/weekly-review` | Yes | None or empty JSON | `{ "review": "<AI-provider response>" }`; factual context is assembled server-side. |
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

## Resource contract notes

The Spring backend implements the active frontend resource matrix. Owned resources are exposed under the
versioned `/api/v1` prefix and scoped by the JWT subject. Child resources use parent ownership checks.
`exercises` and `foods` are readable catalogs and reject writes. Parent/child workflows may still require
multiple requests; composite transaction endpoints are Phase 13 work. The Phase 12 security and rollback
matrix remains future verification work, not a claim that every edge case is covered.
