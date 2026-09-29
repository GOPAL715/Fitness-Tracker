-- Phase 19: user-level notification preferences.
--
-- Until now the only user-facing notification control was reminders.enabled, which is per reminder.
-- "Turn off all reminders" was therefore not expressible: a user had to find and pause every
-- reminder individually. This table adds one account-wide preference row instead, so a user can
-- mute delivery once and the setting applies to every reminder they own.
--
-- Deliberately a separate table rather than columns on app_users: the auth model stays unchanged,
-- and reminder management stays independent of authentication. One row per user, created on first
-- write and read as defaults until then.
--
-- NOT stored here, on purpose:
--   * browser permission. Notification.permission is browser state. The server has no way to read
--     it and must never be told, because it is the browser's answer to the user, not a server fact.
--   * push subscriptions. push_subscriptions already holds devices. Duplicating them here would
--     create a second source of truth for a multi-device fact that Phase 13/18 already owns.
--   * delivery state. reminder_deliveries is the ledger; a preference says what should be
--     attempted, never what happened.
--
-- timezone is intentionally separate from app_users.timezone. That column is nullable, is
-- deliberately never backfilled (a real zone like UTC is indistinguishable from a fabricated one),
-- and is scoped to health calendar dates by UserTimezone. Quiet hours resolve a wall-clock window,
-- which is a different question, and defaulting a NULL to UTC here would be exactly the
-- fabrication that class exists to prevent. NULL means "not set" and quiet hours cannot be active.
CREATE TABLE user_notification_preferences (
    user_id uuid primary key references app_users(id) on delete cascade,
    -- Account-wide delivery switch. Distinct from browser permission and from the server's own
    -- push switch: this one is the user's, and it suppresses delivery before any provider is called.
    push_enabled boolean not null default true,
    -- Separate from push_enabled so "no notifications on this device, but my other device is
    -- already handled" stays expressible, and so the two are independently reportable.
    reminder_notifications_enabled boolean not null default true,
    quiet_hours_enabled boolean not null default false,
    -- [start, end) in the user's own zone. start > end is an overnight window (22:00 -> 07:00
    -- spans midnight). start = end is rejected by the API rather than resolved to a guess here.
    quiet_hours_start time,
    quiet_hours_end time,
    timezone varchar(64),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    -- Quiet hours are a wall-clock window, so both ends and a real zone are required to mean
    -- anything. Enforced here as well as in the API so a row cannot be written by any other route.
    constraint ck_unp_quiet_complete check (
        not quiet_hours_enabled
        or (quiet_hours_start is not null and quiet_hours_end is not null
            and timezone is not null and quiet_hours_start <> quiet_hours_end)
    )
);

-- The scheduler resolves preferences per user per due reminder, so this is a primary-key lookup
-- and needs no separate index. A partial index would only help a scan the query never performs.
