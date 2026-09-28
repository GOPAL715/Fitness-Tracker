-- Phase 7: calendar grouping for workout sessions, and the indexes the calendar needs.
--
-- Why a new column
-- ---------------
-- The calendar used to group a session by started_at::date, which is the date in UTC. A session
-- finished at 00:30 in Asia/Kolkata is 19:30 the previous day in UTC, so it was filed under the
-- wrong calendar day for every user whose local date is ahead of UTC. started_at is kept as the
-- real instant the session was recorded, and is not repurposed here.
--
-- How existing rows are handled
-- -----------------------------
-- session_date is left NULL for every existing row. The database stores no user timezone (the only
-- timezone column in the schema belongs to a reminder, and is not the user's), so a user's local
-- calendar date cannot be recovered from anything that is actually stored. Backfilling
-- started_at::date would re-introduce exactly the UTC bug this migration exists to remove, and
-- guessing an offset would invent history. A NULL session_date is therefore the honest value: the
-- calendar skips those rows rather than showing them on a day they did not happen.
--
-- New writes always supply session_date, so the column fills in from here on.
ALTER TABLE workout_sessions ADD COLUMN session_date date;

-- Calendar grouping index for sessions. The predicate is (user_id, session_date), so the index
-- matches it exactly and the predicate stays sargable; no started_at::date cast is used.
CREATE INDEX idx_workout_sessions_user_session_date ON workout_sessions (user_id, session_date);

-- The calendar counts a user's completed habit check-ins per day. habit_logs had no user_id index,
-- so that count filtered user_id after reading the whole table.
CREATE INDEX idx_habit_logs_user_date ON habit_logs (user_id, log_date);

-- The calendar reads a user's personal records for a date window. Only the primary key existed.
CREATE INDEX idx_personal_records_user_date ON personal_records (user_id, achieved_date);
