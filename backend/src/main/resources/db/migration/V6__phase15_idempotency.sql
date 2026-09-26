-- Phase 15 C: idempotency for retried offline composite writes.
-- An offline client may submit the same logical operation more than once after a retry. The
-- (user, key) primary key makes a repeat submission return the original result instead of
-- creating a second aggregate.
CREATE TABLE idempotency_keys (
    user_id uuid not null references app_users(id) on delete cascade,
    idempotency_key varchar(120) not null,
    resource varchar(60) not null,
    result_id uuid,
    child_count int,
    created_at timestamptz not null default now(),
    primary key (user_id, idempotency_key)
);
CREATE INDEX idx_idempotency_created ON idempotency_keys(created_at);
