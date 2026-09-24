/* Types for the feature tables added during the platform upgrade. */

export type Exercise = {
  id: string;
  name: string;
  description: string;
  muscle_group: string;
  secondary_muscles: string[];
  equipment: string;
  difficulty: string;
  instructions: string;
  is_compound: boolean;
};

export type WorkoutSession = {
  id: string;
  user_id: string;
  title: string;
  workout_type: string;
  started_at: string;
  completed_at: string | null;
  duration_minutes: number;
  notes: string | null;
  perceived_effort: number;
  completed: boolean;
  created_at: string;
};

export type WorkoutExercise = {
  id: string;
  workout_session_id: string;
  exercise_id: string;
  order_index: number;
  notes: string | null;
};

export type ExerciseSet = {
  id: string;
  workout_exercise_id: string;
  set_number: number;
  reps: number | null;
  weight: number | null;
  weight_unit: string;
  duration_seconds: number | null;
  distance: number | null;
  distance_unit: string | null;
  rpe: number | null;
  completed: boolean;
};

export type WorkoutTemplate = {
  id: string;
  user_id: string;
  name: string;
  description: string;
  workout_type: string;
  estimated_minutes: number;
  is_favorite: boolean;
};

export type TemplateExercise = {
  id: string;
  template_id: string;
  exercise_id: string;
  order_index: number;
  target_sets: number;
  target_reps: string;
  target_weight: number | null;
};

export type Goal = {
  id: string;
  user_id: string;
  goal_type: string;
  title: string;
  description: string;
  start_value: number;
  target_value: number;
  current_value: number;
  unit: string;
  start_date: string;
  target_date: string | null;
  status: string;
  created_at: string;
};

export type Food = {
  id: string;
  name: string;
  category: string;
  serving_size: number;
  serving_unit: string;
  calories: number;
  protein_g: number;
  carbs_g: number;
  fat_g: number;
  fiber_g: number;
  sugar_g: number;
  sodium_mg: number;
  source: string;
};

export type MealItem = {
  id: string;
  meal_id: string;
  food_id: string | null;
  food_name: string;
  quantity: number;
  grams: number;
  calories: number;
  protein_g: number;
  carbs_g: number;
  fat_g: number;
  fiber_g: number;
  source: string;
};

export type FoodScanItem = {
  id: string;
  scan_id: string;
  food_id: string | null;
  food_name: string;
  estimated_grams: number;
  confirmed_grams: number | null;
  confidence: number | null;
  calories: number;
  protein_g: number;
  carbs_g: number;
  fat_g: number;
  fiber_g: number;
  sugar_g: number;
  sodium_mg: number;
  user_edited: boolean;
};

export type Habit = {
  id: string;
  user_id: string;
  name: string;
  description: string;
  icon: string;
  target_per_week: number;
  color: string;
  active: boolean;
};

export type HabitLog = {
  id: string;
  user_id: string;
  habit_id: string;
  log_date: string;
  completed: boolean;
};

export type Reminder = {
  id: string;
  user_id: string;
  type: string;
  title: string;
  message: string;
  scheduled_time: string;
  days_of_week: number[];
  enabled: boolean;
  quiet_hours_start: string | null;
  quiet_hours_end: string | null;
};

export const MUSCLE_GROUPS = [
  "Chest", "Back", "Shoulders", "Arms", "Legs", "Core", "Cardio", "Full Body", "Mobility",
] as const;

export const GOAL_TYPES = [
  "Lose Weight", "Build Muscle", "Build Strength", "Improve Endurance", "Improve Fitness",
  "Maintain Weight", "Increase Steps", "Improve Sleep", "Improve Nutrition", "Custom",
] as const;

export const REMINDER_TYPES = [
  "WORKOUT", "WATER", "MEAL", "SLEEP", "HABIT", "GOAL", "WEEKLY_REVIEW",
] as const;

export const HABIT_PRESETS = [
  { name: "Drink water", icon: "droplet", color: "#22d3ee" },
  { name: "Hit step goal", icon: "footprints", color: "#4ade80" },
  { name: "Workout", icon: "dumbbell", color: "#38bdf8" },
  { name: "Sleep 7+ hours", icon: "moon", color: "#a78bfa" },
  { name: "Protein target", icon: "beef", color: "#fb923c" },
  { name: "Stretch", icon: "activity", color: "#fbbf24" },
  { name: "Meditation", icon: "sparkles", color: "#2dd4bf" },
  { name: "Log meals", icon: "utensils", color: "#f87171" },
] as const;


