# Supabase to Spring Boot migration

## Current status

The Spring Boot replacement is a partial implementation, not a production-equivalent cutover. This repository contains no live Supabase export/import job, and no claim is made that existing production users or rows have been migrated. The supported starting point is a fresh PostgreSQL database initialized by Flyway.

The frontend now targets the Spring Boot API. The scanner and ownership implementations are present, but the Docker-backed acceptance suite cannot execute until Docker Desktop is running.

## Supabase mapping

| Supabase capability | Spring Boot target | Status / required work |
| --- | --- | --- |
| `auth.users` and email/password sessions | `app_users`, `refresh_tokens`; signed JWT access tokens | Different model. Preserve user UUIDs only with a reviewed transform. Existing Supabase password hashes and sessions cannot be imported directly. |
| PostgREST table reads/writes | REST resources with `user_id` from JWT | Contract and resource controllers still need implementation. Enforce ownership in every query, not only client filters. |
| Supabase RLS | Spring service authorization plus database grants | There is no RLS in the fresh Spring schema. API-level checks must be added per resource and integration-tested. |
| Storage objects used for scans | Private filesystem volume at `STORAGE_PATH` | The current scan route stores files under an authenticated user ID, but no read/delete endpoint or shared durable object store is implemented. |
| Edge Function food analysis | Spring scanner/coach service | Configuration keys exist, but current scanner only validates/stores an image and coach returns fixed text. No AI request is implemented. |
| Redis / rate limiting or sessions | `redis` in local Compose | Redis is provisioned for the target architecture but no Spring Redis client/configuration or Redis behavior is implemented. |

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

Redis is a startup dependency in Compose but is not evidence of implemented caching. The AI variables may be empty; current endpoints do not call the provider.

## Authentication reset limitation

Spring Boot password login cannot authenticate a migrated Supabase user until a valid BCrypt hash exists. No password-reset endpoint, email delivery, verification flow, or Supabase password-hash conversion is implemented. Existing users therefore need a separately designed, security-reviewed reset process or a deliberate re-registration policy. Do not import placeholder password hashes.

## Cutover checklist and limitations

Before production cutover, implement and test the missing resource APIs, per-user authorization, refresh-token revocation/expiry cleanup, password reset, account deletion/export, object retention/deletion, observability, rate limits, and backups/restore. Reconcile every frontend table query with an explicit versioned API contract. Add AI provider calls, timeouts, redaction, quotas, and cost controls before enabling AI. TLS, a secret manager, external durable Postgres/object storage, Redis policy, monitoring, and a deployment rollback plan are outside this local Compose configuration.

The stack is suitable for local backend development only. A successful container start is not evidence of functional parity or a safe production migration.
