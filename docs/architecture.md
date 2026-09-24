# FitTrack Spring Boot architecture

## Overview

The target architecture replaces direct browser access to Supabase with a React single-page app calling a Spring Boot API. PostgreSQL is the system of record. The repository is mid-transition: the HTTP client and a small Spring API exist, but the backend does not implement the generic CRUD surface used by most frontend screens.

```text
React SPA --Bearer JWT--> Spring Boot :8080
                                  |--> PostgreSQL 16 (Flyway schema)
                                  |--> private image volume (scan uploads)
                                  |--> OpenAI-compatible API (configured, not called)
                                  +--> Redis (provided; not used by Java yet)
```

Supabase Edge Functions and direct browser access remain as legacy/reference code. They are not part of the Compose deployment.

## Implemented backend

- Spring Boot 3.3 / Java 21 REST API.
- Stateless Spring Security with BCrypt password hashing, signed HS256 JWT access tokens, and opaque refresh tokens stored as SHA-256 hashes in PostgreSQL.
- Public auth and health endpoints; all other routes require a bearer access token.
- Flyway creates and Hibernate validates 28 tables for fitness, workouts, nutrition, habits, goals, devices, and coach data.
- Calendar routes return zero placeholder summaries and do not query those tables.
- Scan upload validates size and magic bytes, then writes under the JWT subject in private storage. AI recognition and scan persistence are not implemented.
- Weekly review accepts metrics but returns fixed text. AI configuration is not used by current service code.
- Handled argument/not-found exceptions map to generic 400/404 responses; other failures become a generic 500.

Redis is included for a future rate-limit, cache, or revocation design, but the application has no Redis client or runtime Redis behavior. It must not be described as an active session or resilience store.

## Authorization

`/api/v1/auth/**` and `/actuator/health` are public. Other requests require a signed, unexpired JWT whose subject is the user UUID. Scan path construction uses that subject and checks normalized path containment.

This is route authentication, not complete object authorization. Roles are stored and included in JWTs but are not enforced. No method-level ownership checks exist for future CRUD operations. Production queries must derive `user_id` from the authenticated principal on every user-owned request and test horizontal access denial.

## Frontend boundary

The client targets `VITE_API_BASE_URL`, defaulting to `http://localhost:8080/api`, stores tokens in local storage, retries once after refresh, and accepts camelCase or snake_case token fields. Jackson uses snake case for the existing frontend types.

The generic frontend adapter expects routes such as `/fitness-profile`, `/daily-metrics`, `/workouts`, `/meals`, `/habits`, and `/goals`. Those endpoints do not exist. See [backend-migration.md](backend-migration.md) and [api.md](api.md).

## AI and storage

The application reads AI URL, key, and model settings, but no service calls the provider. Scans are local files not connected to `food_scans` rows. Production still needs durable object storage, metadata persistence, scoped download/delete, stronger media validation, malware controls, retention/deletion, encryption, and AI timeouts/redaction/quotas/cost controls.

## Local Docker deployment

Run from the repository root:

```bash
docker compose up --build
```

Postgres 16, Redis 7, and the backend image use healthchecks; healthy Postgres and Redis gate backend startup. Flyway initializes the fresh database. Named volumes retain database, Redis, and image data.

Configuration uses `${VAR:-local-default}` substitutions. Embedded database and JWT defaults are **non-production local development values**. Shared and production environments must supply secrets through an uncommitted environment file or secret manager. Do not expose Postgres/Redis publicly without network restrictions, authentication, and TLS. Compose does not provide production orchestration, backups, disaster recovery, or secret rotation.

## Testing

Run backend tests with `mvn test` in `backend`; current coverage only checks scanner signatures. Tests use H2, disable Flyway, and use Hibernate create-drop. Run frontend `npm test` and `npm run build`. There are no end-to-end, real-Postgres migration, authorization, refresh-rotation, storage, Redis, or live-AI integration tests. A healthy container does not demonstrate frontend/API parity.
