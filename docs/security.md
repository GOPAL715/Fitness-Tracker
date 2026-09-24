# Security notes for the Spring Boot replacement

## Implemented controls

- BCrypt hashes are used for new Spring accounts.
- Access tokens are signed HS256 JWTs with a 15-minute configured lifetime. The secret must be at least 32 bytes when supplied by the JJWT library.
- Refresh tokens are random opaque values; only SHA-256 hashes are stored. Refresh rotation revokes the old token. Logout revokes a known token.
- Sessions are stateless. CSRF is disabled because browser API calls use bearer tokens rather than cookies.
- All routes except auth and `/actuator/health` require authentication.
- Scan uploads are capped at 8 MiB and checked for JPEG, PNG, or WebP magic bytes. Paths are normalized and must remain below the configured root.
- Server error configuration suppresses exception messages globally; the custom 500 response is generic.
- Flyway owns fresh schema creation and Hibernate runs with `ddl-auto: validate`.

## Deployment secret boundary

`docker-compose.yml` contains substitution-based environment variables. Defaults such as `fittrack_local_only` and `fittrack_local_development_jwt_secret_change_me_32_bytes` exist only to make an isolated workstation start quickly. They are public credentials and must never be used on a shared host or in production.

Production must inject database credentials, a high-entropy JWT secret, and any AI key through a secret manager or protected runtime secrets. Never bake them into an image, commit them, expose them through Vite variables, or log them. Rotate the Compose-local JWT default before any shared deployment. The current image build copies application source but contains no intended production secret.

## Significant gaps

- No password reset, email verification, email delivery, account recovery, MFA, rate limiting, lockout, or breached-password check exists.
- Supabase Auth users/password hashes cannot be directly imported. See [backend-migration.md](backend-migration.md).
- `user_roles` and JWT roles exist, but there is no role enforcement.
- Authentication and ownership are implemented in Spring Security and server-side services. The executable Docker-backed acceptance suite is present but requires Docker Desktop to run.
- The frontend stores access and refresh tokens in `localStorage`, which exposes them to XSS. A hardened browser design should prefer secure, HttpOnly, SameSite cookies with CSRF protection or a carefully reviewed in-memory/session design.
- The JWT filter silently ignores invalid bearer tokens, leaving requests unauthenticated; it does not establish a rich principal or verify that the user is still enabled.
- No CORS configuration is defined. A separate frontend origin therefore needs an explicit reviewed policy; do not solve this with wildcard origins and credentials.
- No security headers, request-size/rate controls beyond multipart upload, audit logging, dependency scanning, SAST/DAST, or penetration test is evidenced here.
- Images are detected by signature only, stored on local disk, and have no serving route, encryption policy, malware scanning, quota, retention, or deletion workflow. `image_path` is an internal path and must not become an unrestricted filesystem URL.
- Logs and AI inputs are not reviewed for health data/PII. No AI request is currently made, despite AI configuration being present.

## Operational hardening before production

Terminate TLS at a trusted ingress; isolate Postgres and Redis on a private network; require Redis authentication/TLS if enabled; use durable managed Postgres with backups and restore tests; use private durable object storage; configure a restrictive CORS allowlist; add security headers; centralize structured redacted logs; add metrics/alerts and audit events; enforce user ownership and role policy with integration tests; add rate limits and abuse controls; and establish retention/deletion/export workflows.

Secrets rotation must account for existing JWTs. Changing the JWT secret invalidates all access tokens. Refresh records can be revoked, but there is no implemented global session-invalidation operation. Run dependency and container scans, test malformed JWTs and cross-user access, and complete a security review before cutover. Compose itself supplies no production availability, secret rotation, backup, restore, TLS, or network-policy guarantees.

## Data privacy

FitTrack data includes health, nutrition, biometric, body, and activity information. Treat it as sensitive even when individual fields look ordinary. Define retention, consent, user export/deletion, incident response, and audit requirements before importing real data. Do not place real health data in logs, test fixtures, AI prompts, or local development logs.
