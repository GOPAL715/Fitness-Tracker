-- Phase 13: Web Push subscriptions.
-- One row per browser push subscription (one browser profile / service worker registration).
-- The endpoint is the push service URL the browser handed us; it is the natural identity because a
-- user re-subscribing after a key rotation or a cleared site-data store produces a new endpoint.
CREATE TABLE push_subscriptions (
    id uuid primary key,
    user_id uuid not null references app_users(id) on delete cascade,
    endpoint text not null,
    -- Browser-supplied subscription material. These are the decrypting half of the subscription and
    -- are secret to the push service: they must never be logged, returned by an API, or committed.
    p256dh text not null,
    auth_secret text not null,
    created_at timestamptz default now(),
    updated_at timestamptz default now(),
    -- One endpoint belongs to exactly one registration. A user re-registering the same endpoint
    -- updates it in place rather than creating a duplicate, which is what makes registration idempotent.
    unique (endpoint)
);

-- Delivery reads by user, so that lookup must not be a sequential scan as subscriptions accumulate.
CREATE INDEX idx_push_subscriptions_user ON push_subscriptions(user_id);

-- The unique constraint already creates a unique index on endpoint, which is what the registration
-- upsert relies on. Expiry cleanup deletes by endpoint, served by that same index.
--
-- The auth secret is deliberately NOT encrypted at rest here. Encrypting it would require shipping a
-- key to the database process, which widens the blast radius without protecting against the threat
-- that matters: an attacker with read access to this table and the VAPID private key can already
-- decrypt any payload they capture. Access is bounded by the existing per-user authorization model
-- and the table is never exposed through a read API.
