# Spring Boot API

Base URL: `http://localhost:8080/api/v1` locally. Production base URL and TLS are deployment-specific.

## Authentication and authorization

`/api/v1/auth/**` and `/actuator/health` are public. All other endpoints require `Authorization: Bearer <access-token>`. Access JWTs expire after 15 minutes; refresh tokens expire after 30 days and rotate on refresh. Role claims are not currently enforced and no role-based endpoint is implemented.

## Endpoints

| Method | Path | Auth | Request | Actual response |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/auth/register` | No | `{ "email": "...", "password": "..." }` | `{ "accessToken", "refreshToken", "expiresAt" }`; serializes as snake case. |
| POST | `/api/v1/auth/login` | No | Same credentials | New token pair. |
| POST | `/api/v1/auth/refresh` | No | `{ "refreshToken": "..." }` | Rotated token pair; prior refresh token is revoked. |
| POST | `/api/v1/auth/logout` | No | `{ "refreshToken": "..." }` | Empty `200` response; revokes token when found. |
| GET | `/api/v1/calendar/{date}` | Yes | ISO date, e.g. `2026-01-31` | `{ "date", "summary": { "workouts": 0, "meals": 0, "habits_completed": 0 } }` |
| GET | `/api/v1/calendar/{from}/{to}` | Yes | Two inclusive ISO dates | One placeholder summary per date. |
| POST | `/api/v1/food/scans` | Yes | Multipart field `image`; non-empty, at most 8 MiB; JPEG/PNG/WebP signature | `{ "status": "ready_to_review", "items": [], "image_path": "<jwt-subject>/<uuid>.<ext>" }` |
| POST | `/api/v1/food/scans/validate` | Yes | Array of up to any number of detection objects | Accepted detections with finite values, capped at 12 results. Fields: `name`, `estimated_grams`, `confidence` (0..1), optional `calories`, macros, `sugar_g`, `sodium_mg`. |
| POST | `/api/v1/coach/weekly-review` | Yes | Optional JSON object | `{ "review": "<fixed text>" }`; request data is currently ignored. |
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

Handled argument errors return `400` with `{ "status", "error", "message" }`; missing entities return the same shape with `404`; other exceptions return a generic `500`. Authentication failures from Spring Security are not normalized into that JSON shape by the custom exception handler.

Credential records have no bean-validation annotations, so email format, required fields, and password strength are not currently enforced. Invalid refresh tokens are reported as `400`. File validation checks byte signatures, not complete image decoding.

## Contract gaps

The frontend `apiData` adapter expects generic collection/item CRUD routes and Supabase-style query behavior. Examples include `/fitness-profile`, `/daily-metrics`, `/body-metrics`, `/workouts`, `/workout-sessions`, `/meals`, `/meal-items`, `/habits`, `/habit-logs`, `/goals`, `/exercises`, and `/foods`. They are not implemented. Supported filter/ordering combinations are also not defined by the backend. Treat this document as an inventory of actual routes, not a promise of frontend compatibility or production readiness.
