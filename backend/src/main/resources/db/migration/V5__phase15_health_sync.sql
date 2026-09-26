-- Phase 15 B: health provider synchronization.
-- health_devices gains the provider identity, external record linkage, bounded sync window and
-- status metadata needed to synchronize idempotently. No access token is stored: a provider
-- account is referenced by an opaque external id only.
ALTER TABLE health_devices ADD COLUMN provider varchar(40) NOT NULL DEFAULT 'manual';
ALTER TABLE health_devices ADD COLUMN external_device_id varchar(120);
ALTER TABLE health_devices ADD COLUMN sync_cursor varchar(160);
ALTER TABLE health_devices ADD COLUMN last_error varchar(80);
ALTER TABLE health_devices ADD COLUMN sync_status varchar(20) NOT NULL DEFAULT 'idle';

-- Imported records carry the provider record id so a repeated sync updates rather than
-- duplicates. A NULL provider_record_id means "created locally", which stays unconstrained.
ALTER TABLE daily_metrics ADD COLUMN source varchar(20);
ALTER TABLE daily_metrics ADD COLUMN provider_record_id varchar(120);
CREATE UNIQUE INDEX uq_daily_metrics_provider_record
    ON daily_metrics(user_id, source, provider_record_id)
    WHERE provider_record_id IS NOT NULL;

ALTER TABLE body_metrics ADD COLUMN source varchar(20);
ALTER TABLE body_metrics ADD COLUMN provider_record_id varchar(120);
CREATE UNIQUE INDEX uq_body_metrics_provider_record
    ON body_metrics(user_id, source, provider_record_id)
    WHERE provider_record_id IS NOT NULL;

CREATE UNIQUE INDEX uq_health_devices_external
    ON health_devices(user_id, provider, external_device_id)
    WHERE external_device_id IS NOT NULL;

CREATE INDEX idx_health_devices_user ON health_devices(user_id);
