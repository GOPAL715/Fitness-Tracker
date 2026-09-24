# FitTrack Spring Boot architecture

## Overview

The active architecture is React + TypeScript/Vite calling a versioned Spring Boot REST API. PostgreSQL is the system of record.

```text
React SPA --Bearer JWT--> Spring Boot :8080
                                  |--> PostgreSQL 16 (Flyway schema)
                                  |--> private image volume (scan uploads)
                                  |--> OpenAI-compatible API (optional backend provider)
                                  +--> Redis (provided; no active Java behavior yet)
```

Supabase Edge Functions and direct browser access remain as legacy/reference code. They are not part of the Compose deployment.

## Implemented backend

- Spring Boot 3.3 / Java 21 REST API.
- Stateless Spring Security with BCrypt password hashing, signed HS256 JWT access tokens, and opaque refresh tokens stored as SHA-256 hashes in PostgreSQL.
- Public auth and health endpoints; all other routes require a bearer access token.
- Flyway creates and Hibernate validates 28 tables for fitness, workouts, nutrition, habits, goals, devices, and coach data.
- Calendar routes return persisted user-scoped daily summaries.
- Scan upload validates size and magic bytes, stores files under the JWT subject, persists scans, and supports correction/confirmation/deletion.
- Coach endpoints load backend-generated user facts and delegate narrative generation to the configured AI provider.
- Handled argument/not-found exceptions map to generic 400/404 responses; other failures become a generic 500.

Redis is provisioned for future cache/rate-limit/revocation design, but the application has no Redis client or runtime Redis behavior. It must not be described as an active session or resilience store.

## Authorization

`/api/v1/auth/**`, `/actuator/health`, and the configured health endpoint are public. Other requests require
a signed, unexpired JWT whose subject is the user UUID. Owned-resource services derive `user_id` from
that subject, reject client-supplied ownership fields, and verify child resources through their parent
chain. Scanner paths and rows are owner-scoped. Catalog resources (`exercises` and `foods`) are read-only.
Phase 12 will expand the executable security and ownership verification matrix.

## Frontend boundary

The client targets `VITE_API_BASE_URL`, defaulting to `http://localhost:8080/api/v1`, stores tokens in
local storage, retries once after refresh, and accepts camelCase or snake_case token fields. Jackson
uses snake case for the existing frontend types. The frontend uses explicit versioned REST modules; the
remaining generic adapter behavior is limited to the resource shapes currently used by the application,
not arbitrary PostgREST query support.

## AI and storage

The backend provides an `AiProvider` abstraction for scanner analysis and coach operations. Scanner
uploads are persisted as scan rows and private owner-scoped files; correction, confirmation, deletion,
and cleanup are implemented. Provider-specific production timeouts, redaction, quotas, cost controls,
and durable managed object storage remain future work.

## Local Docker deployment

Run from the repository root:

```bash
docker compose up --build
```

Postgres 16, Redis 7, and the backend image use healthchecks; healthy Postgres and Redis gate backend startup. Flyway initializes the fresh database. Named volumes retain database, Redis, and image data.

Configuration uses `${VAR:-local-default}` substitutions. Embedded database and JWT defaults are **non-production local development values**. Shared and production environments must supply secrets through an uncommitted environment file or secret manager. Do not expose Postgres/Redis publicly without network restrictions, authentication, and TLS. Compose does not provide production orchestration, backups, disaster recovery, or secret rotation.

## Testing

Run backend tests with `mvn -f backend/pom.xml clean test`; the Testcontainers PostgreSQL acceptance
suite executes Flyway and Spring Boot HTTP flows. The current verified backend baseline is 13 tests passed.
Run frontend `npm run test` and `npm run build`. Phase 12 still needs expanded refresh, ownership,
scanner rollback, and HTTP error-contract coverage.
