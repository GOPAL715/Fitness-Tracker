# Security notes for the Spring Boot replacement

## Implemented controls

- BCrypt hashes are used for new Spring accounts.
- Access tokens are signed HS256 JWTs with a 15-minute configured lifetime. The secret must be at least 32 bytes when supplied by the JJWT library.
- Refresh tokens are random opaque values; only SHA-256 hashes are stored. Refresh rotation revokes the old token. Logout revokes a known token.
- Sessions are stateless. CSRF is disabled because browser API calls use bearer tokens rather than cookies.
- Public auth, actuator health, and `/api/v1/health` endpoints; all other routes require authentication.
- Scan uploads are capped at 8 MiB and checked for JPEG, PNG, or WebP magic bytes. Paths are normalized and must remain below the configured root.
- Server error configuration suppresses exception messages globally; the custom 500 response is generic.
- Flyway owns fresh schema creation and Hibernate runs with `ddl-auto: validate`.

## Deployment secret boundary

`docker-compose.yml` contains substitution-based environment variables. Defaults such as `fittrack_local_only` and `fittrack_local_development_jwt_secret_change_me_32_bytes` exist only to make an isolated workstation start quickly. They are public credentials and must never be used on a shared host or in production.

Production must inject database credentials, a high-entropy JWT secret, and any AI key through a secret manager or protected runtime secrets. Never bake them into an image, commit them, expose them through Vite variables, or log them. Rotate the Compose-local JWT default before any shared deployment. The current image build copies application source but contains no intended production secret.

## Ownership and current limitations

The API derives ownership from the authenticated JWT subject. Direct resources are filtered by the JWT
user ID; child resources are joined to their owned parent. The writable-column service rejects
`user_id` and keeps shared `exercises` and `foods` catalogs read-only. Scan rows and stored objects are
owner-scoped, and scan deletion removes the stored object. Invalid bearer tokens are rejected with 401;
Spring Security maps denied authenticated requests to 403.

The CORS bean in `SecurityConfig` allows `http://localhost:5173` and `http://localhost:3000`, permits
`GET`, `POST`, `PUT`, `PATCH`, `DELETE`, and `OPTIONS`, allows `Authorization` and `Content-Type`, and
allows credentials. Production origins must be explicitly configured and reviewed; this is not a complete
production abuse-control policy.

The current browser client stores access and refresh tokens in `localStorage`, which exposes them to XSS.
There is no password recovery, email verification, MFA, explicit rate limiting, account lockout, breached
password check, or production security monitoring. The full refresh, ownership, scanner rollback, and HTTP
error matrix remains Phase 12 verification work.

## AI Coach boundaries

The AI Coach is general-wellness guidance only. It does not diagnose, prescribe, or treat, and it
refers users to qualified professionals or emergency services where appropriate. That boundary is
enforced by system instructions, by strict server-side validation of the model's output, and by a
permanent disclaimer in the UI. There is no medical classifier in the codebase, and none is claimed.

Context sent to the provider is assembled server-side from an explicit column allowlist and is
deliberately narrow. It excludes email and display name, every database identifier,
`fitness_profile.limitations` (free text a user may use for medical information), health-device
identifiers, raw exercise sets, and raw food-scan history. The user's untrusted question is sent as a
separate, fenced message and never concatenated into the system instruction.

Neither prompts nor responses are persisted, and there is no response cache, no conversation history
and no insight-history table. Provider failures are categorised by cause — timeout, credential
rejection, provider rate limit, 4xx, 5xx, malformed response, unavailable — and no provider body,
credential or stack frame is returned to a client or written to a log line.

Two independent limits apply, and both are intentional: a Coach-specific HTTP bucket of 20/min guards
the endpoint, while a database-enforced AI quota of 10/min bounds actual provider consumption. The
quota is the effective spend control. In production a non-positive `AI_TOKENS_PER_DAY` is refused at
startup, because unlimited tokens are acceptable in development but not as an uncapped production
bill. See [ai-coach.md](ai-coach.md).

## Operational hardening before production

Terminate TLS at a trusted ingress; isolate Postgres and Redis on a private network; require Redis authentication/TLS if enabled; use durable managed Postgres with backups and restore tests; use private durable object storage; configure a restrictive CORS allowlist; add security headers; centralize structured redacted logs; add metrics/alerts and audit events; enforce user ownership and role policy with integration tests; add rate limits and abuse controls; and establish retention/deletion/export workflows.

Secrets rotation must account for existing JWTs. Changing the JWT secret invalidates all access tokens. Refresh records can be revoked, but there is no implemented global session-invalidation operation. Run dependency and container scans, test malformed JWTs and cross-user access, and complete a security review before cutover. Compose itself supplies no production availability, secret rotation, backup, restore, TLS, or network-policy guarantees.

## Data privacy

AI FitTrack data includes health, nutrition, biometric, body, and activity information. Treat it as sensitive even when individual fields look ordinary. Define retention, consent, user export/deletion, incident response, and audit requirements before importing real data. Do not place real health data in logs, test fixtures, AI prompts, or local development logs.
