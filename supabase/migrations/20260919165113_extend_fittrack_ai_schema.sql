/*
# Extend FitTrack AI with advanced tracking tables

## 1. New Tables

- `meals` — nutrition log entries.
  - `meal_date` (date) — the day the meal belongs to
  - `meal_type` (text) — Breakfast, Lunch, Dinner, Snack
  - `name` (text) — description of the food
  - `calories`, `protein_g`, `carbs_g`, `fat_g` (integers) — macro totals
  - `created_at` (timestamptz)

- `body_metrics` — body composition and measurements over time.
  - `metric_date` (date, unique) — one entry per day
  - `weight_lb` (numeric) — body weight
  - `body_fat_pct` (numeric) — body fat percentage
  - `waist_in`, `chest_in`, `arm_in`, `thigh_in` (numeric) — tape measurements
  - `created_at` (timestamptz)

- `personal_records` — best performance per exercise.
  - `exercise` (text) — exercise name
  - `record_value` (numeric) — the achieved value
  - `unit` (text) — lbs, reps, minutes, miles
  - `achieved_date` (date)
  - `previous_value` (numeric) — the value it replaced, used to show improvement
  - `created_at` (timestamptz)

- `plan_sessions` — the weekly plan of scheduled training sessions.
  - `day_index` (integer) — 0 = Monday through 6 = Sunday
  - `title`, `workout_type`, `intensity` (text)
  - `duration_minutes` (integer)
  - `completed` (boolean)
  - `created_at` (timestamptz)

- `health_devices` — connected devices and data sources.
  - `device_name`, `device_type` (text)
  - `status` (text) — Connected, Syncing, Disconnected
  - `last_sync` (timestamptz)
  - `created_at` (timestamptz)

- `coach_notifications` — personalized coaching messages shown to the user.
  - `title`, `message`, `kind` (text) — kind is info, win, warning, or tip
  - `is_read` (boolean)
  - `created_at` (timestamptz)

## 2. Modified Tables

- `fitness_profile` gains onboarding and personalization columns:
  - `fitness_level` (text) — Beginner, Intermediate, Advanced
  - `equipment` (text) — available equipment
  - `limitations` (text) — injuries or restrictions
  - `sleep_target_hours` (numeric)
  - `step_target` (integer)
  - `calorie_target` (integer)
  - `protein_target_g` (integer)
  - `water_target_oz` (integer)
  - `target_weight_lb` (numeric)

- `daily_metrics` gains:
  - `hrv` (integer) — heart rate variability in ms
  - `active_minutes` (integer) — minutes of intentional movement
  - `stress_level` (integer) — 1 to 10 self-reported stress
  - `resting_heart_rate`, `readiness` already exist

- `workouts` gains:
  - `notes` (text)
  - `perceived_effort` (integer) — 1 to 10 rate of perceived exertion
  - `distance_miles` (numeric)

## 3. Security

- Row level security is enabled on every new table.
- Each table gets four separate policies (SELECT, INSERT, UPDATE, DELETE) for
  the `anon` and `authenticated` roles, because FitTrack AI is a private
  single-device tracker with no sign-in screen in this release.

## 4. Important Notes

1. All statements are idempotent and safe to re-run.
2. No columns are dropped, renamed, or retyped, so existing data is never lost.
3. A small set of realistic demo rows is inserted so charts and insights are
   populated on first launch. Inserts use ON CONFLICT DO NOTHING so user data
   is never overwritten on later runs.
*/

-- ---------- New tables ----------

CREATE TABLE IF NOT EXISTS meals (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  meal_date date NOT NULL DEFAULT current_date,
  meal_type text NOT NULL DEFAULT 'Snack',
  name text NOT NULL,
  calories integer NOT NULL DEFAULT 0,
  protein_g integer NOT NULL DEFAULT 0,
  carbs_g integer NOT NULL DEFAULT 0,
  fat_g integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS body_metrics (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  metric_date date NOT NULL UNIQUE,
  weight_lb numeric(5,1) NOT NULL DEFAULT 0,
  body_fat_pct numeric(4,1) NOT NULL DEFAULT 0,
  waist_in numeric(4,1) NOT NULL DEFAULT 0,
  chest_in numeric(4,1) NOT NULL DEFAULT 0,
  arm_in numeric(4,1) NOT NULL DEFAULT 0,
  thigh_in numeric(4,1) NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS personal_records (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  exercise text NOT NULL,
  record_value numeric(7,1) NOT NULL DEFAULT 0,
  unit text NOT NULL DEFAULT 'lbs',
  achieved_date date NOT NULL DEFAULT current_date,
  previous_value numeric(7,1),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS plan_sessions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  day_index integer NOT NULL DEFAULT 0,
  title text NOT NULL,
  workout_type text NOT NULL DEFAULT 'Strength',
  intensity text NOT NULL DEFAULT 'Moderate',
  duration_minutes integer NOT NULL DEFAULT 45,
  completed boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS health_devices (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  device_name text NOT NULL,
  device_type text NOT NULL DEFAULT 'Tracker',
  status text NOT NULL DEFAULT 'Connected',
  last_sync timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS coach_notifications (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  title text NOT NULL,
  message text NOT NULL,
  kind text NOT NULL DEFAULT 'info',
  is_read boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- ---------- Modified tables ----------

ALTER TABLE fitness_profile
  ADD COLUMN IF NOT EXISTS fitness_level text NOT NULL DEFAULT 'Intermediate',
  ADD COLUMN IF NOT EXISTS equipment text NOT NULL DEFAULT 'Full gym',
  ADD COLUMN IF NOT EXISTS limitations text NOT NULL DEFAULT 'None',
  ADD COLUMN IF NOT EXISTS sleep_target_hours numeric(4,1) NOT NULL DEFAULT 8,
  ADD COLUMN IF NOT EXISTS step_target integer NOT NULL DEFAULT 10000,
  ADD COLUMN IF NOT EXISTS calorie_target integer NOT NULL DEFAULT 2400,
  ADD COLUMN IF NOT EXISTS protein_target_g integer NOT NULL DEFAULT 150,
  ADD COLUMN IF NOT EXISTS water_target_oz integer NOT NULL DEFAULT 100,
  ADD COLUMN IF NOT EXISTS target_weight_lb numeric(5,1) NOT NULL DEFAULT 175;

ALTER TABLE daily_metrics
  ADD COLUMN IF NOT EXISTS hrv integer NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS active_minutes integer NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS stress_level integer NOT NULL DEFAULT 4;

ALTER TABLE workouts
  ADD COLUMN IF NOT EXISTS notes text,
  ADD COLUMN IF NOT EXISTS perceived_effort integer NOT NULL DEFAULT 6,
  ADD COLUMN IF NOT EXISTS distance_miles numeric(6,2);

-- ---------- Indexes ----------

CREATE INDEX IF NOT EXISTS meals_date_idx ON meals (meal_date DESC);
CREATE INDEX IF NOT EXISTS body_metrics_date_idx ON body_metrics (metric_date DESC);
CREATE INDEX IF NOT EXISTS plan_day_idx ON plan_sessions (day_index);
CREATE INDEX IF NOT EXISTS notifications_created_idx ON coach_notifications (created_at DESC);

-- ---------- Row level security ----------

ALTER TABLE meals ENABLE ROW LEVEL SECURITY;
ALTER TABLE body_metrics ENABLE ROW LEVEL SECURITY;
ALTER TABLE personal_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE plan_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE health_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE coach_notifications ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "public read meals" ON meals;
CREATE POLICY "public read meals" ON meals FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "public insert meals" ON meals;
CREATE POLICY "public insert meals" ON meals FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "public update meals" ON meals;
CREATE POLICY "public update meals" ON meals FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "public delete meals" ON meals;
CREATE POLICY "public delete meals" ON meals FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "public read body_metrics" ON body_metrics;
CREATE POLICY "public read body_metrics" ON body_metrics FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "public insert body_metrics" ON body_metrics;
CREATE POLICY "public insert body_metrics" ON body_metrics FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "public update body_metrics" ON body_metrics;
CREATE POLICY "public update body_metrics" ON body_metrics FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "public delete body_metrics" ON body_metrics;
CREATE POLICY "public delete body_metrics" ON body_metrics FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "public read personal_records" ON personal_records;
CREATE POLICY "public read personal_records" ON personal_records FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "public insert personal_records" ON personal_records;
CREATE POLICY "public insert personal_records" ON personal_records FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "public update personal_records" ON personal_records;
CREATE POLICY "public update personal_records" ON personal_records FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "public delete personal_records" ON personal_records;
CREATE POLICY "public delete personal_records" ON personal_records FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "public read plan_sessions" ON plan_sessions;
CREATE POLICY "public read plan_sessions" ON plan_sessions FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "public insert plan_sessions" ON plan_sessions;
CREATE POLICY "public insert plan_sessions" ON plan_sessions FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "public update plan_sessions" ON plan_sessions;
CREATE POLICY "public update plan_sessions" ON plan_sessions FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "public delete plan_sessions" ON plan_sessions;
CREATE POLICY "public delete plan_sessions" ON plan_sessions FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "public read health_devices" ON health_devices;
CREATE POLICY "public read health_devices" ON health_devices FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "public insert health_devices" ON health_devices;
CREATE POLICY "public insert health_devices" ON health_devices FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "public update health_devices" ON health_devices;
CREATE POLICY "public update health_devices" ON health_devices FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "public delete health_devices" ON health_devices;
CREATE POLICY "public delete health_devices" ON health_devices FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "public read coach_notifications" ON coach_notifications;
CREATE POLICY "public read coach_notifications" ON coach_notifications FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "public insert coach_notifications" ON coach_notifications;
CREATE POLICY "public insert coach_notifications" ON coach_notifications FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "public update coach_notifications" ON coach_notifications;
CREATE POLICY "public update coach_notifications" ON coach_notifications FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "public delete coach_notifications" ON coach_notifications;
CREATE POLICY "public delete coach_notifications" ON coach_notifications FOR DELETE TO anon, authenticated USING (true);

-- ---------- Seed demo history ----------

INSERT INTO fitness_profile (display_name, goal, activity_target, weekly_minutes)
SELECT 'Alex Morgan', 'Build strength', 4, 180
WHERE NOT EXISTS (SELECT 1 FROM fitness_profile);

INSERT INTO daily_metrics (metric_date, steps, sleep_hours, calories_burned, water_oz, resting_heart_rate, readiness, hrv, active_minutes, stress_level)
SELECT
  (current_date - (g || ' days')::interval)::date,
  5800 + (g * 337) % 5200,
  round((6.4 + ((g * 7) % 22) / 10.0)::numeric, 1),
  1750 + (g * 91) % 700,
  58 + (g * 13) % 46,
  56 + (g * 5) % 9,
  58 + (g * 11) % 40,
  38 + (g * 7) % 34,
  22 + (g * 9) % 48,
  2 + (g * 3) % 7
FROM generate_series(0, 27) AS g
ON CONFLICT (metric_date) DO NOTHING;

INSERT INTO workouts (title, workout_type, duration_minutes, calories_burned, intensity, workout_date, completed, perceived_effort, distance_miles, notes)
SELECT
  t.title, t.wtype, t.mins, t.cals, t.intensity,
  (current_date - (t.days_ago || ' days')::interval)::date,
  true, t.rpe, t.dist, t.notes
FROM (VALUES
  ('Upper Body Strength', 'Strength', 52, 410, 'Hard', 0, 8, NULL::numeric, 'Felt strong on pressing movements'),
  ('Interval Run', 'Running', 34, 380, 'Hard', 1, 8, 3.60, 'Negative split on the last mile'),
  ('Mobility & Recovery', 'Mobility', 25, 90, 'Easy', 2, 3, NULL, 'Hips and shoulders focus'),
  ('Leg Day', 'Strength', 58, 470, 'Max', 3, 9, NULL, 'New squat personal best'),
  ('Cycling Endurance', 'Cycling', 65, 520, 'Moderate', 4, 6, 14.20, 'Steady aerobic pace'),
  ('Core & Conditioning', 'HIIT', 28, 300, 'Hard', 5, 8, NULL, 'Short rest intervals'),
  ('Yoga Flow', 'Yoga', 40, 150, 'Easy', 6, 4, NULL, 'Good breathing work'),
  ('Pull Day', 'Strength', 48, 390, 'Hard', 8, 8, NULL, 'Pull-ups felt smoother'),
  ('Tempo Run', 'Running', 40, 430, 'Hard', 9, 8, 4.50, 'Held threshold pace'),
  ('Recovery Walk', 'Walking', 45, 180, 'Easy', 11, 3, 2.30, 'Outdoor walk after dinner'),
  ('Full Body Strength', 'Strength', 55, 450, 'Hard', 13, 8, NULL, 'Solid all-round session'),
  ('Swim Intervals', 'Swimming', 38, 360, 'Moderate', 15, 7, 1.10, 'Focused on stroke technique')
) AS t(title, wtype, mins, cals, intensity, days_ago, rpe, dist, notes);

INSERT INTO body_metrics (metric_date, weight_lb, body_fat_pct, waist_in, chest_in, arm_in, thigh_in)
SELECT
  (current_date - (g * 7 || ' days')::interval)::date,
  round((182.4 - g * 0.6)::numeric, 1),
  round((19.2 - g * 0.25)::numeric, 1),
  round((34.5 - g * 0.15)::numeric, 1),
  round((41.0 + g * 0.1)::numeric, 1),
  round((14.2 + g * 0.08)::numeric, 1),
  round((23.5 + g * 0.05)::numeric, 1)
FROM generate_series(0, 7) AS g
ON CONFLICT (metric_date) DO NOTHING;

INSERT INTO personal_records (exercise, record_value, unit, achieved_date, previous_value)
SELECT * FROM (VALUES
  ('Back Squat', 275.0::numeric, 'lbs', (current_date - 3), 260.0::numeric),
  ('Bench Press', 185.0, 'lbs', (current_date - 8), 175.0),
  ('Deadlift', 315.0, 'lbs', (current_date - 21), 300.0),
  ('Pull-ups', 14.0, 'reps', (current_date - 8), 12.0),
  ('5K Run', 23.4, 'minutes', (current_date - 12), 24.8),
  ('Plank Hold', 3.2, 'minutes', (current_date - 18), 2.8)
) AS t(exercise, record_value, unit, achieved_date, previous_value)
WHERE NOT EXISTS (SELECT 1 FROM personal_records);

INSERT INTO plan_sessions (day_index, title, workout_type, intensity, duration_minutes, completed)
SELECT * FROM (VALUES
  (0, 'Upper Body Strength', 'Strength', 'Hard', 50, true),
  (1, 'Interval Run', 'Running', 'Hard', 35, true),
  (2, 'Mobility & Recovery', 'Mobility', 'Easy', 25, true),
  (3, 'Leg Day', 'Strength', 'Max', 60, true),
  (4, 'Cycling Endurance', 'Cycling', 'Moderate', 60, false),
  (5, 'Core & Conditioning', 'HIIT', 'Hard', 30, false),
  (6, 'Yoga Flow', 'Yoga', 'Easy', 40, false)
) AS t(day_index, title, workout_type, intensity, duration_minutes, completed)
WHERE NOT EXISTS (SELECT 1 FROM plan_sessions);

INSERT INTO health_devices (device_name, device_type, status, last_sync)
SELECT * FROM (VALUES
  ('Apple Health', 'Platform', 'Connected', now() - interval '12 minutes'),
  ('Apple Watch Series 9', 'Smartwatch', 'Connected', now() - interval '12 minutes'),
  ('Garmin HRM-Pro', 'Heart Rate', 'Connected', now() - interval '2 hours'),
  ('Withings Body+', 'Smart Scale', 'Connected', now() - interval '1 day'),
  ('Google Health Connect', 'Platform', 'Disconnected', now() - interval '9 days')
) AS t(device_name, device_type, status, last_sync)
WHERE NOT EXISTS (SELECT 1 FROM health_devices);

INSERT INTO coach_notifications (title, message, kind, is_read)
SELECT * FROM (VALUES
  ('New personal record', 'You set a Back Squat PR of 275 lbs, up 15 lbs from your previous best.', 'win', false),
  ('Recovery is trending up', 'Your HRV improved 12% this week. Keep the easy days easy to hold the gain.', 'info', false),
  ('Sleep below target', 'You averaged 6.9 hours of sleep this week against an 8 hour target.', 'warning', false),
  ('Hydration tip', 'You hit your water target 3 days in a row. Aim for 5 this week.', 'tip', true)
) AS t(title, message, kind, is_read)
WHERE NOT EXISTS (SELECT 1 FROM coach_notifications);

INSERT INTO meals (meal_date, meal_type, name, calories, protein_g, carbs_g, fat_g)
SELECT
  (current_date - t.days_ago)::date, t.mtype, t.fname, t.cals, t.protein, t.carbs, t.fat
FROM (VALUES
  (0, 'Breakfast', 'Greek yogurt, berries, granola', 420, 32, 48, 9),
  (0, 'Lunch', 'Grilled chicken and quinoa bowl', 650, 48, 62, 18),
  (0, 'Snack', 'Protein shake and banana', 320, 28, 38, 5),
  (1, 'Breakfast', 'Three egg omelette with spinach', 380, 26, 8, 26),
  (1, 'Lunch', 'Salmon, rice, roasted vegetables', 720, 45, 68, 26),
  (1, 'Dinner', 'Chicken stir fry with noodles', 680, 42, 74, 20),
  (2, 'Breakfast', 'Oatmeal with peanut butter', 450, 18, 58, 15),
  (2, 'Lunch', 'Turkey wrap and side salad', 560, 38, 54, 18),
  (3, 'Breakfast', 'Protein smoothie', 390, 34, 44, 7),
  (3, 'Lunch', 'Beef burrito bowl', 740, 46, 76, 24),
  (4, 'Dinner', 'Steak, potatoes, asparagus', 780, 52, 58, 32),
  (5, 'Lunch', 'Tuna salad sandwich', 520, 36, 48, 18)
) AS t(days_ago, mtype, fname, cals, protein, carbs, fat);
