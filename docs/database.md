# FitTrack AI â€” Database

24 tables in the `public` schema. Row level security is enabled on every one of
them, with 90 policies in total. The Supabase security advisor reports zero
findings.

## Ownership model

Every user-owned table carries:

```sql
user_id uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE
```

The client never sends `user_id`. The default fills it from the verified session
token, and the policy enforces it, so a client cannot forge ownership.

## Tables

### Profile and daily health

| Table | Rows | Notes |
| --- | --- | --- |
| `fitness_profile` | one per user | name, goal, level, equipment, and all daily targets. Unique index on `user_id` |
| `daily_metrics` | one per user per day | steps, sleep, calories, water, resting HR, readiness, HRV, active minutes, stress |
| `body_metrics` | one per user per day | weight, body fat, waist, chest, arm, thigh |

`daily_metrics` and `body_metrics` were originally unique on the date alone; they
are now unique on `(user_id, metric_date)` so two accounts can each log the same
calendar day.

### Training

| Table | Notes |
| --- | --- |
| `workouts` | the original quick-log record; retained so existing history still works |
| `workout_sessions` | a performed session with timestamps, duration and effort |
| `workout_exercises` | exercises within a session, ordered |
| `exercise_sets` | reps, weight, RPE, duration, distance per set |
| `workout_templates` | reusable blueprints |
| `workout_template_exercises` | exercises within a template with target sets and reps |
| `exercises` | shared catalogue of 38 exercises across 9 muscle groups |
| `plan_sessions` | the weekly plan |
| `personal_records` | best performance per exercise |

`exercise_sets` enforces non-negative reps, weight, duration and distance with
CHECK constraints.

### Nutrition

| Table | Notes |
| --- | --- |
| `foods` | shared catalogue of 58 foods with complete per-100g macros |
| `meals` | meal header with totals and a `source` of manual / scanner / imported |
| `meal_items` | individual foods with grams and resolved nutrition |
| `food_scans` | photo analysis records with status, model, image path and error |
| `food_scan_items` | detected items, preserving both the AI estimate and the user's correction |
| `ai_usage` | per-request token counts, model, success flag and estimated cost |

### Habits, goals, reminders, devices

| Table | Notes |
| --- | --- |
| `habits` | habit definitions with icon, colour and weekly target |
| `habit_logs` | daily check-ins, unique per habit per day |
| `goals` | goal type, start/current/target values, unit, dates and status |
| `reminders` | type, time, recurring days and the enabled flag. It also carries `quiet_hours_start` / `quiet_hours_end`, which are stored and shown but **not enforced** — Phase 19 does not activate them, because doing so would silently change delivery for every user who already set one. Enforced quiet hours live in `user_notification_preferences`. |
| `reminder_deliveries` | the per-occurrence delivery ledger: one row per `(reminder_id, occurrence_at)`, carrying `state`, `attempts`, `last_error`, `delivered_at` and `created_at`. The unique key on those two columns is what makes delivery exactly-once — a second scheduler pass for the same occurrence loses the insert and does not call the provider. `failure_category` (V13, nullable) adds the user-facing cause; it is NULL for every successful delivery and for every row recorded before V13. `state` also carries `skipped_policy` (V19) for an occurrence deliberately not sent because of a user preference; that is a policy decision rather than a failure, so it stores no `last_error` and no `failure_category`. |
| `user_notification_preferences` | one row per account (V19): `push_enabled`, `reminder_notifications_enabled`, `quiet_hours_enabled`, the `[quiet_hours_start, quiet_hours_end)` window and its IANA `timezone`. Account-wide settings the user controls, stored apart from any individual reminder so "turn everything off" is expressible. A check constraint keeps the window and its zone complete together. Deliberately holds no browser permission, no push subscription and no delivery state — those belong to the browser, to `push_subscriptions` and to `reminder_deliveries` respectively. `timezone` is nullable and is never defaulted, because a fabricated zone would silently misplace every quiet-hours decision. |
| `push_subscriptions` | browser push endpoints and their decrypting keys, scoped to one user. Never exposed through an API response. |
| `health_devices` | connected device records: `provider`, `external_device_id`, `sync_status`, `sync_cursor`, `last_error`, `last_sync`. `sync_cursor` and `client_changes_token` are internal and are never returned by any API, including the aggregate `/api/v1/app-data` route, which selects an explicit column allowlist for this table (Phase 20). |
| `coach_notifications` | weekly coach reviews and messages |

## RLS patterns

**Owner-scoped tables** get four policies, one per verb, all using
`auth.uid() = user_id`:

```sql
CREATE POLICY "select_own_meals" ON meals FOR SELECT
  TO authenticated USING (auth.uid() = user_id);
CREATE POLICY "insert_own_meals" ON meals FOR INSERT
  TO authenticated WITH CHECK (auth.uid() = user_id);
CREATE POLICY "update_own_meals" ON meals FOR UPDATE
  TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE POLICY "delete_own_meals" ON meals FOR DELETE
  TO authenticated USING (auth.uid() = user_id);
```

The UPDATE policy repeats the check in `WITH CHECK` so a row cannot be reassigned
to another owner.

**Child tables without their own `user_id`** are scoped through the parent:

```sql
CREATE POLICY "select_own_exercise_sets" ON exercise_sets FOR SELECT
  TO authenticated USING (EXISTS (
    SELECT 1 FROM workout_exercises we
    JOIN workout_sessions s ON s.id = we.workout_session_id
    WHERE we.id = exercise_sets.workout_exercise_id
      AND s.user_id = auth.uid()
  ));
```

This applies to `workout_exercises`, `exercise_sets`,
`workout_template_exercises`, `meal_items` and `food_scan_items`.

**Shared catalogues** (`exercises`, `foods`) are readable by all authenticated
users but have no write policy, so they are read-only from the client. A single
`FOR SELECT TO authenticated USING (true)` policy is correct here because the
data is intentionally public to signed-in users.

**`anon` holds no privileges** on any table â€” `REVOKE ALL` is applied
everywhere, so a signed-out request can neither read nor write health data.

## Storage

Bucket `food-images`: private, 8 MB limit, JPEG/PNG/WebP only. Four policies
restrict every operation to the caller's own folder:

```sql
(bucket_id = 'food-images' AND (storage.foldername(name))[1] = auth.uid()::text)
```

## Indexes

Beyond primary keys and foreign keys:

- `user_id` on every user-owned table
- `daily_metrics (user_id, metric_date)`, `body_metrics (user_id, metric_date)`
- `workouts (workout_date DESC)`, `workout_sessions (user_id, started_at DESC)`
- `exercises (muscle_group)`, `exercises (equipment)`
- `foods (lower(name))`, `foods (category)`
- `meal_items (meal_id)`, `workout_exercises (workout_session_id, order_index)`
- `exercise_sets (workout_exercise_id, set_number)`
- `habit_logs (habit_id, log_date DESC)` unique, `habit_logs (user_id, log_date DESC)`
- `goals (user_id, status)`, `reminders (user_id, enabled)`
- `food_scans (user_id, created_at DESC)`, `food_scan_items (scan_id)`
- `ai_usage (user_id, created_at DESC)`

## Migrations

| Migration | Purpose |
| --- | --- |
| `create_fitness_tracker_core` | profile, daily metrics, workouts |
| `extend_fittrack_ai_schema` | meals, body metrics, PRs, plan, devices, notifications |
| `add_auth_and_user_scoped_rls` | ownership columns, per-user uniqueness, owner-scoped RLS |
| `create_exercise_library_sets_and_templates` | exercises, sessions, sets, templates, goals |
| `create_food_database_habits_reminders_and_scanner` | foods, meal items, habits, reminders, scans, AI usage |
| `create_food_image_storage_bucket` | private bucket and storage policies |

## Data safety notes

- No destructive change was made to existing columns. The original `workouts`
  table and its data are intact.
- Legacy rows created before authentication existed have a `NULL user_id`. They
  are preserved but unreachable through any policy, so no data was destroyed to
  make the migration pass.
- Constraints are additive and validated (`CHECK` on numeric set fields).

## Known gaps

- No audit trail table for who changed what beyond `created_at` / `updated_at`.
- No soft deletes; deletes are hard.
- `ai_usage` records an estimated cost constant rather than reading provider
  billing data.

## Health data sources

`daily_metrics` and `body_metrics` may hold several rows for the same user and date: one manual row
and one row per connected device. Three constraints keep that safe.

| Column | Meaning |
|---|---|
| `source` | Which integration produced the row. A manual row has `provider_record_id = NULL`. |
| `provider_record_id` | The provider's stable record id. Re-importing the same record updates the row in place. |
| `device_id` | The originating connection. `NULL` on manual rows, and set to `NULL` by the foreign key when a device is disconnected. |

**Uniqueness.** Manual rows are unique per user and date, enforced by the partial unique index
`uq_daily_metrics_manual_date` / `uq_body_metrics_manual_date`. Provider rows are unique per
`(user_id, source, provider_record_id)`, enforced by `uq_daily_metrics_provider_record` /
`uq_body_metrics_provider_record`. Neither constrains the other, so a day can hold both.

**Canonical reads.** `v_daily_metrics_canonical` and `v_body_metrics_canonical` return exactly one
row per user and date, so every existing single-row-per-day reader keeps working. Selection is
per field: `NULL` is skipped, an explicit provider priority is applied (manual ranks last), equal
priorities are broken by `device_id` then `provider_record_id`, and competing values are never
summed or averaged.

**Retention on disconnect.** Deleting a `health_devices` row does not delete imported metrics. The
foreign key is `ON DELETE SET NULL`, so the pointer is dropped and the history is kept. `source` and
`provider_record_id` remain, so provenance is still readable.
## Phase 11 additions (V11)

### `app_users.timezone`

The user's IANA zone, added because Phase 1-10 stored none. The only pre-existing `timezone` column
belongs to a reminder and is not the user's, so a local calendar day could not be recovered from
stored data - the gap `V7__calendar_session_date.sql` already documents.

Nullable, and **left NULL for every existing user**. No default is invented: `UTC` is a real zone
many users genuinely live in, so seeding it would be indistinguishable from a real answer and would
silently mis-date health data for everyone else. The first successful ingest persists the zone the
client sends; before that no health record can be dated. Values are validated against
`java.time.ZoneId` before they are ever written.

Applied to **health calendar dates only**. Phase 1-10 date semantics are unchanged by this phase.

### `health_connect_records`

A source-record ledger: one row per inbound Health Connect record, holding the value in its native
unit plus the timestamps needed to re-derive which days it touched.

It exists because Health Connect reports a **deletion as a record id only** - no value, no
timestamps. Without retained source records, reconciliation would be impossible and the only options
would be to leave a stale aggregate or invent a destruction rule. Aggregates are therefore always
recomputed from this ledger rather than incremented.

Uniqueness is `(user_id, device_id, record_id)`: a record id is unique per Health Connect store, and
one person may have several Android devices.

This is an ingest working set, **not a second read model**. `daily_metrics` and `body_metrics`
remain the only read path, and the Phase 10 canonical views are untouched.

### `health_devices.client_changes_token` and `permission_status`

`client_changes_token` holds the Android client's own opaque Health Connect resume handle. It is
kept **separate from `sync_cursor`**, which is the server's date watermark for the server-pull
provider path. The two have different owners, semantics and lifetimes; conflating them would corrupt
the pull path and let a client steer the server's cursor.

`permission_status` is a **device-reported UX signal**. The server cannot verify Android Health
Connect permissions, so nothing in the authorization path reads it and it can neither grant nor deny
access. No raw permission strings, tokens, or credentials are stored.