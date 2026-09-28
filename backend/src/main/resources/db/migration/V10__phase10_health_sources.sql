-- Phase 10: source-aware health storage, canonical views, and device linkage.
--
-- Phase 15 could not import a second health source for a day that already had a record, because
-- V1 put UNIQUE(user_id, metric_date) on daily_metrics and body_metrics. That constraint encoded
-- the Phase 1-9 world, where every row was hand-entered and there was exactly one row per day.
-- With a provider also writing, a day legitimately has a manual row AND one row per device.
--
-- This migration separates the two concerns:
--   * MANUAL rows (provider_record_id IS NULL) stay unique per user per day, which is what the
--     manual write paths - water logging and body-metric edits - already assume. Replacing the
--     table constraint with a partial unique index preserves that behaviour exactly.
--   * PROVIDER rows are already keyed by uq_*_provider_record and may be many per day.
--
-- No row is deleted, merged, or rewritten. Existing manual data is untouched by the new index.

-- 1. device_id (D2): provenance for provider rows, NULL for manual rows.
--    ON DELETE SET NULL is deliberate (D3). Disconnecting a device deletes the connection row
--    set-null keeps every imported metric and only drops the pointer, so health history survives.
--    source and provider_record_id remain, so provenance is still readable after a disconnect.
ALTER TABLE daily_metrics ADD COLUMN device_id uuid REFERENCES health_devices(id) ON DELETE SET NULL;
ALTER TABLE body_metrics  ADD COLUMN device_id uuid REFERENCES health_devices(id) ON DELETE SET NULL;

-- 2. Replace the one-row-per-day table constraints with manual-only partial unique indexes.
ALTER TABLE daily_metrics DROP CONSTRAINT IF EXISTS daily_metrics_user_id_metric_date_key;
ALTER TABLE body_metrics  DROP CONSTRAINT IF EXISTS body_metrics_user_id_metric_date_key;

CREATE UNIQUE INDEX uq_daily_metrics_manual_date
    ON daily_metrics(user_id, metric_date) WHERE provider_record_id IS NULL;
CREATE UNIQUE INDEX uq_body_metrics_manual_date
    ON body_metrics(user_id, metric_date) WHERE provider_record_id IS NULL;

-- 3. Indexes for the new access patterns.
--    device + date supports "re-pull everything from this device" and the FK.

-- 4. Canonical source priority.
--
-- The ranking is explicit, not alphabetical, and manual always ranks last so a hand-entered
-- fallback can never displace a device measurement. Unknown sources share one rank and are then
-- separated by the stable identifier tie-breakers in the views below, so the result stays
-- deterministic even when a new provider appears before it has its own rank.
--
-- Written with inline literals rather than a dollar-quoted body so that a naive semicolon splitter
-- - which some tooling applies when replaying migration scripts - cannot cut the function in half.
-- Higher number wins, provider_record_id IS NULL marks a manual row, ranked below every device row.
CREATE FUNCTION health_source_priority(source varchar, provider_record_id varchar)
RETURNS integer
LANGUAGE sql
IMMUTABLE
RETURN CASE
    WHEN provider_record_id IS NULL THEN 100
    WHEN source = 'health-connect' THEN 10
    WHEN source = 'fitbit'         THEN 20
    WHEN source = 'garmin'         THEN 30
    WHEN source = 'apple-health'   THEN 40
    ELSE 50
END;

-- 5. Canonical views.
--
-- Exactly one row per (user_id, metric_date), so every Phase 1-9 consumer that assumes a single
-- row per day keeps working by reading the view instead of the table.
--
-- Per-field selection policy (Q2), applied independently to each column:
--   a. NULL is "no measurement" and is skipped, never selected and never treated as 0.
--   b. An explicit provider priority is used (see health_source_priority above).
CREATE VIEW v_daily_metrics_canonical AS
SELECT
    d.user_id,
    d.metric_date,
    -- Row handle for the generic resource reader, which orders and looks rows up by id. It is the
    -- id of the lowest-priority row for the day, not of any single winning field, because fields
    -- are resolved independently. It is a handle, never a claim about which row supplied what.
    (array_agg(d.id ORDER BY health_source_priority(d.source, d.provider_record_id) DESC NULLS LAST, d.device_id NULLS LAST, d.provider_record_id NULLS LAST))[1] AS id,
    (array_agg(d.steps              ORDER BY health_source_priority(d.source, d.provider_record_id), d.device_id NULLS LAST, d.provider_record_id) FILTER (WHERE d.steps              IS NOT NULL))[1] AS steps,
    (array_agg(d.sleep_hours        ORDER BY health_source_priority(d.source, d.provider_record_id), d.device_id NULLS LAST, d.provider_record_id) FILTER (WHERE d.sleep_hours        IS NOT NULL))[1] AS sleep_hours,
    (array_agg(d.calories_burned    ORDER BY health_source_priority(d.source, d.provider_record_id), d.device_id NULLS LAST, d.provider_record_id) FILTER (WHERE d.calories_burned    IS NOT NULL))[1] AS calories_burned,
    (array_agg(d.water_oz           ORDER BY health_source_priority(d.source, d.provider_record_id), d.device_id NULLS LAST, d.provider_record_id) FILTER (WHERE d.water_oz           IS NOT NULL))[1] AS water_oz,
    (array_agg(d.resting_heart_rate ORDER BY health_source_priority(d.source, d.provider_record_id), d.device_id NULLS LAST, d.provider_record_id) FILTER (WHERE d.resting_heart_rate IS NOT NULL))[1] AS resting_heart_rate,
    (array_agg(d.readiness          ORDER BY health_source_priority(d.source, d.provider_record_id), d.device_id NULLS LAST, d.provider_record_id) FILTER (WHERE d.readiness          IS NOT NULL))[1] AS readiness,
    (array_agg(d.hrv                ORDER BY health_source_priority(d.source, d.provider_record_id), d.device_id NULLS LAST, d.provider_record_id) FILTER (WHERE d.hrv                IS NOT NULL))[1] AS hrv,
    (array_agg(d.active_minutes     ORDER BY health_source_priority(d.source, d.provider_record_id), d.device_id NULLS LAST, d.provider_record_id) FILTER (WHERE d.active_minutes     IS NOT NULL))[1] AS active_minutes,
    (array_agg(d.stress_level       ORDER BY health_source_priority(d.source, d.provider_record_id), d.device_id NULLS LAST, d.provider_record_id) FILTER (WHERE d.stress_level       IS NOT NULL))[1] AS stress_level
FROM daily_metrics d
GROUP BY d.user_id, d.metric_date;

CREATE VIEW v_body_metrics_canonical AS
SELECT
    b.user_id,
    b.metric_date,
    -- Row handle, see the note on the daily view. Always present, so no null filter is needed.
    (array_agg(b.id ORDER BY health_source_priority(b.source, b.provider_record_id) DESC NULLS LAST, b.device_id NULLS LAST, b.provider_record_id NULLS LAST))[1] AS id,
    (array_agg(b.weight_lb    ORDER BY health_source_priority(b.source, b.provider_record_id), b.device_id NULLS LAST, b.provider_record_id) FILTER (WHERE b.weight_lb    IS NOT NULL))[1] AS weight_lb,
    (array_agg(b.body_fat_pct ORDER BY health_source_priority(b.source, b.provider_record_id), b.device_id NULLS LAST, b.provider_record_id) FILTER (WHERE b.body_fat_pct IS NOT NULL))[1] AS body_fat_pct,
    (array_agg(b.waist_in     ORDER BY health_source_priority(b.source, b.provider_record_id), b.device_id NULLS LAST, b.provider_record_id) FILTER (WHERE b.waist_in     IS NOT NULL))[1] AS waist_in,
    (array_agg(b.chest_in     ORDER BY health_source_priority(b.source, b.provider_record_id), b.device_id NULLS LAST, b.provider_record_id) FILTER (WHERE b.chest_in     IS NOT NULL))[1] AS chest_in,
    (array_agg(b.arm_in       ORDER BY health_source_priority(b.source, b.provider_record_id), b.device_id NULLS LAST, b.provider_record_id) FILTER (WHERE b.arm_in       IS NOT NULL))[1] AS arm_in,
    (array_agg(b.thigh_in     ORDER BY health_source_priority(b.source, b.provider_record_id), b.device_id NULLS LAST, b.provider_record_id) FILTER (WHERE b.thigh_in     IS NOT NULL))[1] AS thigh_in
FROM body_metrics b
GROUP BY b.user_id, b.metric_date;

--   c. Equal-priority sources are broken by stable identifiers - device_id, then
--      provider_record_id - so the result is deterministic and reproducible.
--   d. Import timestamps are deliberately not used: a re-imported old record is not a better
--      measurement, and ordering by it would make analytics change without the data changing.
--   e. Competing rows are never summed or averaged.
--
-- AGGREGATION SEMANTICS (Q2 investigation): steps, calories and active minutes are per-day
-- TOTALS in this contract - HealthProvider.ActivityRecord carries one aggregate per date, and
-- Health Connect's daily step/calorie/active-minute records are cumulative daily values, not
-- additive per-event samples. Two sources reporting the same day are therefore two competing
-- measurements of one quantity, not two contributions to add. Summing them would double-count,
-- so selection is the correct operation and the policy above is applied per field.
--
-- Activity-level records (individual workouts) are a different shape and are not part of these
-- views, they belong in the workouts table, which is already keyed by user and date.

--    user + device + date supports per-device history scoped to an owner.
CREATE INDEX idx_daily_metrics_device_date ON daily_metrics(device_id, metric_date) WHERE device_id IS NOT NULL;
CREATE INDEX idx_body_metrics_device_date  ON body_metrics(device_id, metric_date)  WHERE device_id IS NOT NULL;
CREATE INDEX idx_daily_metrics_user_device_date ON daily_metrics(user_id, device_id, metric_date) WHERE device_id IS NOT NULL;
CREATE INDEX idx_body_metrics_user_device_date  ON body_metrics(user_id, device_id, metric_date)  WHERE device_id IS NOT NULL;
