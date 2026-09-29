
-- Phase 11: Google Health Connect ingestion contract.
--
-- Health Connect is an Android-native, on-device API. There is no server endpoint FitTrack could
-- call, so a future Android bridge reads Health Connect and pushes bounded records to this backend.
-- This migration adds only what that push path genuinely needs. It is additive: no existing row is
-- deleted, rewritten, or re-dated.
--
-- -----------------------------------------------------------------------------
-- 1. User timezone (D3) - THE MISSING CALENDAR MODEL
-- -----------------------------------------------------------------------------
-- Phase 1-10 stored no user timezone. The only timezone column in the schema belongs to a reminder
-- and is not the user's, so a user's local calendar date could not be recovered from stored data
-- (this gap is already documented in V7__calendar_session_date.sql).
--
-- Health Connect records carry absolute instants. Filing them under a server-local or UTC calendar
-- day would misfile data for most of the planet, so a health calendar date cannot be derived without
-- an explicit IANA zone.
--
-- The column is deliberately NULLABLE and deliberately left NULL for every existing user.
--   * A fake per-user default is not invented. "UTC" is a real zone many users genuinely are in, so
--     seeding it would be indistinguishable from a real answer and would silently mis-date data for
--     everyone else. NULL is the only honest default: it means "not set".
--   * It stays non-destructive: adding a nullable column rewrites no user row.
--   * Ingress does not guess. The Android bridge always sends its IANA zone and the first successful
--     ingest persists it. Until then no health record can be dated.
-- Values are validated against java.time.ZoneId before they are ever written, so an invalid zone can
-- never reach this column. Length 64 comfortably covers the longest current IANA identifier.
ALTER TABLE app_users ADD COLUMN timezone varchar(64);

-- -----------------------------------------------------------------------------
-- 2. The missing model that D5 deletion handling requires (REPORTED BLOCKER)
-- -----------------------------------------------------------------------------
-- Phase 10's daily_metrics holds ONE row per (user, source, provider_record_id) and the canonical
-- views apply per-field SELECTION across sources. A pre-aggregated daily row therefore has no
-- per-record decomposition: once a day's steps are summed, the individual records that produced them
-- are gone.
--
-- Verified against Google's Health Connect sync guide: a deletion is reported as a record id ONLY.
-- The deletion entry does not carry the record's value or its start/end time, and the guide's own
-- recovery strategy ("delete then read data for the last 30 days") exists precisely because of that.
-- So on a deletion FitTrack knows "record X is gone" and nothing about which day it covered or what
-- it contributed.
--
-- Without a retained source record, recomputation is impossible, and the only alternatives are to
-- leave a stale aggregate (silently preserving a value the source says is deleted) or to invent a
-- destruction rule. Neither is acceptable, so the source records are retained here.
--
-- health_connect_records is that ledger: one row per inbound source record, holding everything
-- needed to recompute any day it touches. It is an ingest working set, not a second read model.
--   * daily_metrics remains the only read path; the Phase 10 canonical views are untouched.
--   * The derived provider row for a day is keyed by the synthetic, device-scoped id
--     'hc-agg:<device>:<date>' so two devices of one user never collide, because the existing unique
--     index is (user_id, source, provider_record_id) and does NOT include device_id.
--   * Reconciliation always recomputes from this ledger, never from a remembered total.
--
-- ON DELETE CASCADE on device_id is deliberate and matches the Phase 10 retention contract:
-- disconnecting stops future syncing, so that device's recomputation working set is no longer
-- reachable. Readable history survives in daily_metrics, whose device_id is merely set to NULL by
-- the same disconnect. Reconnecting and re-sending rebuilds the ledger and reproduces exactly the
-- same aggregate, because ingest is idempotent on record_id.
CREATE TABLE health_connect_records (
    id uuid primary key,
    user_id uuid not null references app_users(id) on delete cascade,
    device_id uuid not null references health_devices(id) on delete cascade,
    record_id varchar(120) not null,
    -- steps | active_calories | weight | body_fat : the Phase 11 scope, nothing else.
    record_type varchar(24) not null,
    -- The record's own start day, for indexing. Day attribution for interval records is DERIVED from
    -- start_time/end_time at recompute time, because a record crossing local midnight contributes to
    -- two days and a single stored date would be wrong for one of them.
    local_date date not null,
    start_time timestamptz not null,
    end_time timestamptz not null,
    value numeric not null,
    -- The unit as sent by the bridge (count, kcal, kg, percent). Retained so the ledger is
    -- self-describing and a correction can be checked against the original. Never a credential.
    unit varchar(16) not null,
    created_at timestamptz default now(),
    updated_at timestamptz default now()
);

-- Idempotency key. It is scoped to the user AND the device AND the platform record id, which is
-- exactly the identity Health Connect itself uses: a record id is unique per Health Connect store,
-- and the same person can have two Android devices. device_id is part of the key rather than the
-- value so two devices can never overwrite each other's records.
CREATE UNIQUE INDEX uq_health_connect_records_source
    ON health_connect_records(user_id, device_id, record_id);

-- Recomputation reads every record of one type that OVERLAPS a local day. start_time < dayEnd AND
-- end_time > dayStart is the correct predicate, and it must find a record that began the previous
-- day, which an equality test on local_date would miss.
CREATE INDEX idx_health_connect_records_overlap
    ON health_connect_records(user_id, device_id, record_type, start_time, end_time);

-- -----------------------------------------------------------------------------
-- 3. Android-owned state, kept strictly separate from the server-pull sync_cursor
-- -----------------------------------------------------------------------------
-- health_devices.sync_cursor is documented in HealthSyncService as the SERVER'S date watermark for
-- the server-pull provider path. The Android client owns a Health Connect Changes token, which is an
-- opaque, platform-specific resume handle with different semantics, a different lifetime and a
-- different owner. Reusing sync_cursor for it would corrupt the pull path and conflate two unrelated
-- concepts, so it gets its own column and is never read by the pull sync.
ALTER TABLE health_devices ADD COLUMN client_changes_token text;

-- Permission state is DEVICE-REPORTED metadata. The server structurally cannot verify Android Health
-- Connect permissions: they are granted on the handset and only the native app can observe them.
-- This column is therefore a UX signal for the web UI, never an authorization input. Nothing in the
-- authorization path reads it, and it cannot grant or deny data access. permission_required and
-- permission_revoked are client claims and are labelled as such in the response DTO and the docs.
ALTER TABLE health_devices ADD COLUMN permission_status varchar(24);
