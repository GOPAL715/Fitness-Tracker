# Supabase to Spring Boot migration

## Current status

The Spring Boot REST API is the active backend. The frontend uses the versioned REST client at `/api/v1`; Supabase files are retained only as historical migration reference and are not part of the runtime. Fresh PostgreSQL is initialized by Flyway. Existing production data has not been migrated.

The frontend targets the Spring Boot API at `/api/v1`. The scanner, generic owned-resource routes, analytics routes, and Testcontainers acceptance suite are implemented. Supabase files are historical reference material only.

## Supabase mapping

| Supabase capability | Spring Boot target | Status / required work |
| --- | --- | --- |
| `auth.users` and email/password sessions | `app_users`, `refresh_tokens`; signed JWT access tokens | Active replacement uses Spring accounts and rotating opaque refresh tokens. Existing Supabase password hashes and sessions cannot be imported directly. |
| PostgREST table reads/writes | Versioned Spring REST resources with JWT-derived ownership | Core owned-resource CRUD and child-parent authorization are implemented. Further edge-case verification remains Phase 12. |
| Supabase RLS | Spring service authorization plus database constraints | The active Spring schema has no Supabase RLS layer; ownership is enforced in backend services. |
| Storage objects used for scans | Private owner-scoped filesystem storage at `STORAGE_PATH` | Scanner upload, correction, confirmation, deletion, and stored-object cleanup are implemented. |
| Edge Function food analysis | Spring `ScannerService` and `AiProvider` | Active Spring path validates provider output, matches foods, and calculates nutrition. Historical Edge Functions remain reference only. |
| Redis / rate limiting or sessions | `redis` in local Compose | Redis is provisioned but currently has no Spring runtime client or application behavior. |

## Schema assumptions

`backend/src/main/resources/db/migration/V1__fittrack.sql` is authoritative for a fresh backend database. It adds `user_id` to user-owned records and uses `app_users` instead of Supabase Auth.

The checked-in Supabase migrations evolved separately. They originally described a single-device model: several core tables have globally unique dates and RLS policies explicitly granting access to `anon`. Later frontend types and SQL rely on multi-user ownership. Therefore these schemas are **not** assumed equivalent. Any data conversion must explicitly:

1. resolve each source row to a target `app_users.id` and `user_id`;
2. resolve globally unique dates to composite user/date keys where required by `V1`;
3. drop demo/seed ownership assumptions and the permissive `anon` policies;
4. preserve UUIDs only where referential integrity is checked;
5. define how auth identities and password resets are handled; and
6. reconcile nullable/non-null, generated IDs, timestamps, and enum-like text values.

No automated transform or validation report is included.

## Fresh database setup

1. Copy an environment file or export variables. Defaults in `docker-compose.yml` are explicitly local-development credentials; replace them outside local development.
2. Run `docker compose up --build`.
3. PostgreSQL becomes healthy before the backend starts. Flyway applies `V1__fittrack.sql`; Hibernate validates it.
4. Register through `POST /api/v1/auth/register`; do not import demo rows.
5. Remove the named volumes (`docker compose down -v`) only when intentional, because this destroys local data.

Redis is a startup dependency in Compose but is not evidence of implemented caching. The AI provider is
optional backend configuration; tests use a test-scoped provider and do not require a live external AI
call.

## Remaining work by phase

- **Phase 12:** executable security, two-user ownership, scanner lifecycle, and HTTP error-contract matrix.
- **Phase 13:** composite transaction endpoints for parent/child writes.
- **Phase 14:** analytics range hardening and complete AI usage/cost controls.
- **Phase 15:** reminder delivery, health-provider synchronization, and PWA offline write synchronization.
- **Phase 16:** production deployment, observability, abuse controls, backups, secret management, and operational hardening.

## Authentication reset limitation

Spring Boot password login cannot authenticate a migrated Supabase user until a valid BCrypt hash exists. No password-reset endpoint, email delivery, verification flow, or Supabase password-hash conversion is implemented. Existing users therefore need a separately designed, security-reviewed reset process or a deliberate re-registration policy. Do not import placeholder password hashes.

## Cutover checklist and limitations

Before production cutover, complete and test the Phase 12 security/ownership/error matrix, add password reset, account deletion/export, object retention/deletion, observability, rate limits, and backups/restore. Reconcile any future frontend operations against explicit versioned API contracts. Add production AI timeouts, redaction, quotas, and cost controls before enabling external providers. TLS, a secret manager, external durable Postgres/object storage, Redis policy, monitoring, and a deployment rollback plan are outside this local Compose configuration.

The stack is suitable for local backend development only. A successful container start is not evidence of functional parity or a safe production migration.
