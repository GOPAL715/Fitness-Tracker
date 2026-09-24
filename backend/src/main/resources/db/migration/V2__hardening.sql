-- Hardening migration: enforce ownership, stable generated identifiers, and data integrity.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE app_users ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE user_roles ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE refresh_tokens ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE fitness_profile ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE daily_metrics ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE body_metrics ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE exercises ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE foods ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE workouts ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE workout_sessions ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE workout_exercises ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE exercise_sets ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE workout_templates ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE workout_template_exercises ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE plan_sessions ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE personal_records ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE meals ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE meal_items ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE food_scans ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE food_scan_items ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE ai_usage ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE habits ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE habit_logs ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE goals ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE reminders ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE health_devices ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE coach_notifications ALTER COLUMN id SET DEFAULT gen_random_uuid();

ALTER TABLE fitness_profile ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE fitness_profile ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE workout_sessions ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE meals ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE food_scans ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE ai_usage ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE goals ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE health_devices ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE coach_notifications ALTER COLUMN created_at SET DEFAULT now();

-- These legacy tables previously allowed orphaned records.
ALTER TABLE workouts ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE plan_sessions ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE personal_records ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE daily_metrics ADD CONSTRAINT ck_daily_metrics_nonnegative CHECK (steps IS NULL OR steps >= 0);
ALTER TABLE body_metrics ADD CONSTRAINT ck_body_metrics_weight CHECK (weight_lb IS NULL OR weight_lb > 0);
ALTER TABLE exercise_sets ADD CONSTRAINT ck_exercise_sets_order CHECK (set_number IS NULL OR set_number > 0);
ALTER TABLE reminders ADD CONSTRAINT ck_reminders_enabled CHECK (enabled IS NOT NULL);

CREATE INDEX IF NOT EXISTS idx_daily_metrics_user_date ON daily_metrics(user_id, metric_date);
CREATE INDEX IF NOT EXISTS idx_body_metrics_user_date ON body_metrics(user_id, metric_date);
CREATE INDEX IF NOT EXISTS idx_workouts_user_date ON workouts(user_id, workout_date);
CREATE INDEX IF NOT EXISTS idx_meals_user_date ON meals(user_id, meal_date);
CREATE INDEX IF NOT EXISTS idx_habits_user ON habits(user_id);
CREATE INDEX IF NOT EXISTS idx_goals_user ON goals(user_id);
CREATE INDEX IF NOT EXISTS idx_food_scans_user ON food_scans(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expiry ON refresh_tokens(expires_at);

-- Refresh-token rotation/reuse detection. Existing rows each become their own family.
ALTER TABLE refresh_tokens ADD COLUMN family_id uuid;
ALTER TABLE refresh_tokens ADD COLUMN used boolean NOT NULL DEFAULT false;
ALTER TABLE refresh_tokens ADD COLUMN replaced_by_hash varchar(64);
UPDATE refresh_tokens SET family_id = id WHERE family_id IS NULL;
ALTER TABLE refresh_tokens ALTER COLUMN family_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family ON refresh_tokens(family_id);
