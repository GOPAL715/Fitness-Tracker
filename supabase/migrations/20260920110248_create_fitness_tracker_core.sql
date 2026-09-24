/*
# Create fitness tracker core data

## 1. New Tables

- `fitness_profile` — the tracking profile for this device.
  - `display_name` (text), `goal` (text), `activity_target` (integer), `weekly_minutes` (integer)
  - `created_at`, `updated_at` (timestamptz)

- `daily_metrics` — one row per calendar day.
  - `metric_date` (date, unique)
  - `steps`, `calories_burned`, `water_oz`, `resting_heart_rate`, `readiness` (integers)
  - `sleep_hours` (numeric)

- `workouts` — logged or planned training sessions.
  - `title`, `workout_type`, `intensity` (text)
  - `duration_minutes`, `calories_burned` (integers)
  - `workout_date` (date), `completed` (boolean)

## 2. Security

- Row level security enabled on all three tables.
- Four separate policies (SELECT, INSERT, UPDATE, DELETE) per table for the
  `anon` and `authenticated` roles, since FitTrack AI is a private
  single-device tracker with no sign-in screen in this release.

## 3. Important Notes

1. All statements are idempotent and safe to re-run.
2. No existing data is dropped, renamed, or retyped.
3. This migration is recorded so the project download contains the full
   database setup, not just the later additions.
*/

CREATE TABLE IF NOT EXISTS fitness_profile (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  display_name text NOT NULL DEFAULT 'Alex Morgan',
  goal text NOT NULL DEFAULT 'Build strength',
  activity_target integer NOT NULL DEFAULT 4,
  weekly_minutes integer NOT NULL DEFAULT 180,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS daily_metrics (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  metric_date date NOT NULL UNIQUE,
  steps integer NOT NULL DEFAULT 0,
  sleep_hours numeric(4,1) NOT NULL DEFAULT 0,
  calories_burned integer NOT NULL DEFAULT 0,
  water_oz integer NOT NULL DEFAULT 0,
  resting_heart_rate integer NOT NULL DEFAULT 0,
  readiness integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS workouts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  title text NOT NULL,
  workout_type text NOT NULL DEFAULT 'Strength',
  duration_minutes integer NOT NULL DEFAULT 0,
  calories_burned integer NOT NULL DEFAULT 0,
  intensity text NOT NULL DEFAULT 'Moderate',
  workout_date date NOT NULL DEFAULT current_date,
  completed boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE fitness_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE daily_metrics ENABLE ROW LEVEL SECURITY;
ALTER TABLE workouts ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "public read fitness profile" ON fitness_profile;
CREATE POLICY "public read fitness profile" ON fitness_profile FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "public insert fitness profile" ON fitness_profile;
CREATE POLICY "public insert fitness profile" ON fitness_profile FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "public update fitness profile" ON fitness_profile;
CREATE POLICY "public update fitness profile" ON fitness_profile FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "public delete fitness profile" ON fitness_profile;
CREATE POLICY "public delete fitness profile" ON fitness_profile FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "public read daily metrics" ON daily_metrics;
CREATE POLICY "public read daily metrics" ON daily_metrics FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "public insert daily metrics" ON daily_metrics;
CREATE POLICY "public insert daily metrics" ON daily_metrics FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "public update daily metrics" ON daily_metrics;
CREATE POLICY "public update daily metrics" ON daily_metrics FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "public delete daily metrics" ON daily_metrics;
CREATE POLICY "public delete daily metrics" ON daily_metrics FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "public read workouts" ON workouts;
CREATE POLICY "public read workouts" ON workouts FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "public insert workouts" ON workouts;
CREATE POLICY "public insert workouts" ON workouts FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "public update workouts" ON workouts;
CREATE POLICY "public update workouts" ON workouts FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "public delete workouts" ON workouts;
CREATE POLICY "public delete workouts" ON workouts FOR DELETE TO anon, authenticated USING (true);

CREATE INDEX IF NOT EXISTS daily_metrics_date_idx ON daily_metrics (metric_date DESC);
CREATE INDEX IF NOT EXISTS workouts_date_idx ON workouts (workout_date DESC);

INSERT INTO fitness_profile (display_name, goal, activity_target, weekly_minutes)
SELECT 'Alex Morgan', 'Build strength', 4, 180
WHERE NOT EXISTS (SELECT 1 FROM fitness_profile);
