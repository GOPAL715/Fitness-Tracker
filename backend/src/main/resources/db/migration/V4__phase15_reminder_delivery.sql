-- Phase 15 A: reminder delivery.
-- Adds timezone, scheduling and server-controlled delivery state to the existing reminders
-- contract, plus a delivery-attempt ledger that makes duplicate scheduler execution a
-- no-op via a uniqueness constraint.
ALTER TABLE reminders ADD COLUMN timezone varchar(64) NOT NULL DEFAULT 'UTC';
ALTER TABLE reminders ADD COLUMN recurrence varchar(16) NOT NULL DEFAULT 'daily';
ALTER TABLE reminders ADD COLUMN delivery_status varchar(20) NOT NULL DEFAULT 'pending';
ALTER TABLE reminders ADD COLUMN delivery_attempts int NOT NULL DEFAULT 0;
ALTER TABLE reminders ADD COLUMN last_error varchar(80);
ALTER TABLE reminders ADD COLUMN last_delivered_at timestamptz;
ALTER TABLE reminders ADD COLUMN next_occurrence_at timestamptz;

-- V2 added ck_reminders_enabled (enabled IS NOT NULL) without a default, so any insert that
-- omitted the flag failed. A reminder is enabled unless explicitly disabled.
ALTER TABLE reminders ALTER COLUMN enabled SET DEFAULT true;

CREATE INDEX idx_reminders_due ON reminders(enabled, next_occurrence_at);

-- One row per (reminder, scheduled occurrence). The primary key is the idempotency guarantee:
-- a second scheduler pass for the same occurrence cannot insert a second delivery.
CREATE TABLE reminder_deliveries (
    id uuid primary key,
    reminder_id uuid not null references reminders(id) on delete cascade,
    user_id uuid not null references app_users(id) on delete cascade,
    occurrence_at timestamptz not null,
    state varchar(20) not null default 'pending',
    attempts int not null default 0,
    last_error varchar(80),
    delivered_at timestamptz,
    created_at timestamptz default now(),
    unique (reminder_id, occurrence_at)
);
CREATE INDEX idx_reminder_deliveries_due ON reminder_deliveries(state, occurrence_at);
