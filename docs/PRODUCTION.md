# Production operations

Full production posture: rate limits, HTTP security, secrets, observability, backup and restore,
deployment, incident response and rollback.

## Required environment variables

| Variable | Required | Purpose |
| --- | --- | --- |
| `APP_PRODUCTION` | yes | Enables production guardrails. Startup fails on a development secret. |
| `JWT_SECRET` | yes | HMAC signing key for access tokens. Minimum 32 characters. |
| `DATABASE_URL` | yes | JDBC URL. Must not point at localhost in production. |
| `DATABASE_USERNAME` | yes | Database role. |
| `DATABASE_PASSWORD` | yes | Database password. Must not be the default. |
| `CORS_ALLOWED_ORIGINS` | yes | Comma-separated exact frontend origins. Never a wildcard. |
| `STORAGE_PATH` | yes | Directory for private scan images. Mount as a persistent volume. |
| `REDIS_HOST` / `REDIS_PORT` | recommended | Shared rate-limit state when running more than one instance. |
| `RATE_LIMIT_REDIS_ENABLED` | recommended | Set true to use Redis for rate limits across instances. |
| `OPENAI_API_KEY` | optional | Enables the AI features. Absent means a clear "not configured" response. |

Rate limits are tuned with `RATE_LIMIT_AUTH_REQUESTS`, `RATE_LIMIT_API_REQUESTS` and
`RATE_LIMIT_AI_REQUESTS`, each with a matching `RATE_LIMIT_*_WINDOW` in seconds.

## Development versus production

| Setting | Development | Production |
| --- | --- | --- |
| `APP_PRODUCTION` | false | true |
| `CORS_ALLOWED_ORIGINS` | localhost defaults | exact deployed origins |
| `JWT_SECRET` | shipped development value | secret manager, 32+ chars |
| HSTS | not sent | sent over HTTPS |
| Rate limit state | in-process | Redis |

## Rate limits

Independent buckets:

- **auth** (`/api/v1/auth/**`): keyed by client address, and fails **closed** if the shared store
  is unreachable, so a Redis outage cannot disable brute-force protection.
- **ai**: keyed by authenticated user, for coach and food-scanner routes.
- **api**: keyed by authenticated user, or by client address when anonymous.

Going over the limit returns `429` with `Retry-After` and a `rate_limited` code. Keys never come
from a request body, so a caller cannot claim another identity to dodge a limit. The AI quotas
in README section 11 are a separate, per-user spend control and remain enforced independently.

## HTTP security

All API responses carry `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer`, `Permissions-Policy`, a `frame-ancestors 'none'` CSP and
`Cache-Control: no-store`. HSTS is added when `APP_PRODUCTION=true` and the request arrived over
HTTPS. Error responses never include stack traces, SQL, JDBC URLs, file paths or JWT contents.

## Health, readiness and logging

| Endpoint | Purpose |
| --- | --- |
| `GET /actuator/health` | Aggregate status, no component details |
| `GET /actuator/health/liveness` | Process alive; never checks dependencies |
| `GET /actuator/health/readiness` | Ready for traffic; includes database and Redis |
| `GET /api/v1/health` | Legacy application-level check |

Only `health` is exposed. `env`, `configprops`, `beans`, `mappings`, `loggers` and `heapdump` are
not reachable, and health output shows no credentials or connection strings.

Each request gets a correlation id, echoed in `X-Request-Id` when supplied and returned in the
error body as `request_id`. Every log line carries it, so an error response maps directly to its
log lines.

## Backup and restore

```
scripts/backup.sh  <database-url> <out-file>    # custom-format dump plus a SHA-256 checksum
scripts/restore.sh <database-url> <dump-file>   # verifies the checksum, then restores
```

Recommended posture:

- Frequency: daily full dumps, more often if the data warrants it.
- Retention: 7 daily, 4 weekly, 6 monthly.
- Encryption at rest: enabled on the storage holding the dumps.
- Off-site: replicate to a different provider or region.
- RPO: 24 hours with daily dumps, or the last dump interval.
- RTO: 2 hours, dominated by provisioning PostgreSQL and replaying migrations.

**Verify restores, not just backups.** At least quarterly, restore into a scratch database and
start the application against it. The application runs with `ddl-auto=validate`, so a successful
startup is proof the schema matches. Migrations are append-only: a new migration is added and
existing ones are never edited, so a restored database is always forward-migratable.

## Deployment

```
docker compose up --build    # local: PostgreSQL, Redis and the backend
```

The backend image is multi-stage and runs as a non-root user with no build tooling and no
baked-in secrets. It defines its own healthcheck and shuts down gracefully on SIGTERM.

A production deployment needs the application container, PostgreSQL, Redis for shared rate-limit
state, and a TLS-terminating reverse proxy in front. Terminate HTTPS at the proxy and forward
`X-Forwarded-For` so rate limiting can identify clients. Serve `dist/` as static files and route
unknown paths to `index.html`. Scale horizontally only with Redis rate limiting enabled; otherwise
each instance keeps its own counters.

## Account deletion and export

`DELETE /api/v1/me` removes the caller's account: private storage objects first, then refresh
tokens, then the user row, which cascades to every owned table. It cannot be aimed at another
account, and repeating it is a safe no-op.

Data export is deliberately **not** an inline endpoint. A full export is unbounded, so a
synchronous response would be a denial-of-service risk. Export is an operational procedure: dump
the user's rows with a filtered query and deliver the archive out of band. `GET /api/v1/me/export`
returns `501` to make that boundary explicit rather than silently returning partial data.

## Troubleshooting

- **Startup fails immediately** — a production secret is missing or still the development value;
  the message names the variable and never prints its value.
- **429s in normal use** — check which bucket is exhausted; `X-RateLimit-Remaining` reports the
  remaining budget.
- **Intermittent 429s across users** — Redis rate limiting is off, so limits are per instance.
- **Readiness failing but the app works** — a dependency is down. Liveness staying `UP` confirms
  the process itself is fine and should not be restarted.
- **Uploads rejected** — the limit is 8 MB per file and 9 MB per request.

## Incident response

1. Correlate: take the `request_id` from the report and pull the matching log lines.
2. Contain: if a secret may have been exposed, rotate it first. Rotating `JWT_SECRET` invalidates
   every session, which is the intended emergency action.
3. Assess data impact with owner-scoped queries. An unauthenticated read shows as `401` in logs.
4. Recover: restore from the most recent verified backup if data integrity is in question.

## Rollback

```
# 1. Redeploy the previous image tag.
# 2. Flyway is forward-only, so roll back the application first.
```

If a migration must be reverted, write a new migration rather than editing history. Rolling the
application back past a migration leaves the database ahead of the code, so deploy the older image
only when it tolerates the newer schema.

## Deferred to later phases

- Password reset and email verification need an outbound mail provider.
- Web Push remains an external deployment capability; no VAPID keys are shipped.
- Device health integrations remain abstractions, as described in the README.
- Access tokens are stateless and short-lived. Refresh rotation and replay detection are the
  current revocation mechanism, so "sign out of all devices" is not yet available.

## External provider boundaries

Three capabilities need credentials or a channel that FitTrack does not ship. Each is represented
by an explicit, honest default rather than a stub that pretends to work, so a deployment starts
cleanly and reports the boundary instead of failing to boot or silently inventing data.

| Capability | Default when unconfigured | To enable |
| --- | --- | --- |
| Reminder push delivery | `UnconfiguredDeliveryProvider` reports a permanent failure and closes the occurrence, so sync is not retried forever against a channel that was never configured. | Register a `NotificationDeliveryProvider` bean backed by Web Push and supply VAPID keys. |
| Health provider sync | `UnconfiguredHealthProvider` reports the `unavailable` category; no rows are written and the API returns a categorized error. | Register a `HealthProvider` bean and supply provider OAuth credentials. |
| AI features | `AiProvider` reports "not configured"; the two AI features are unavailable, everything else works. | Set `OPENAI_API_KEY`. |

Each default yields automatically to a real implementation, so adding a provider is a matter of
supplying one bean plus its credentials, with no change to the sync, delivery or quota logic.

One related rule is worth knowing when adding a bean yourself: a default that is conditional on
another bean not being present must be declared inside a `@Configuration` class. On an ordinary
`@Component` the evaluation order is undefined, which previously left the application with no
provider to inject and unable to start.


