

export type Profile = {
  id: string;
  display_name: string;
  goal: string;
  activity_target: number;
  weekly_minutes: number;
  fitness_level: string;
  equipment: string;
  limitations: string;
  sleep_target_hours: number;
  step_target: number;
  calorie_target: number;
  protein_target_g: number;
  water_target_oz: number;
  target_weight_lb: number;
};

export type DailyMetric = {
  id: string;
  metric_date: string;
  steps: number;
  sleep_hours: number;
  calories_burned: number;
  water_oz: number;
  resting_heart_rate: number;
  readiness: number;
  hrv: number;
  active_minutes: number;
  stress_level: number;
};

export type Workout = {
  id: string;
  title: string;
  workout_type: string;
  duration_minutes: number;
  calories_burned: number;
  intensity: string;
  workout_date: string;
  completed: boolean;
  notes: string | null;
  perceived_effort: number;
  distance_miles: number | null;
  created_at: string;
};

export type Meal = {
  id: string;
  meal_date: string;
  meal_type: string;
  name: string;
  calories: number;
  protein_g: number;
  carbs_g: number;
  fat_g: number;
  fiber_g: number;
  source: string;
  notes: string | null;
  created_at: string;
};

export type BodyMetric = {
  id: string;
  metric_date: string;
  weight_lb: number;
  body_fat_pct: number;
  waist_in: number;
  chest_in: number;
  arm_in: number;
  thigh_in: number;
};

export type PersonalRecord = {
  id: string;
  exercise: string;
  record_value: number;
  unit: string;
  achieved_date: string;
  previous_value: number | null;
};

export type PlanSession = {
  id: string;
  day_index: number;
  title: string;
  workout_type: string;
  intensity: string;
  duration_minutes: number;
  completed: boolean;
};

export type HealthDevice = {
  id: string;
  device_name: string;
  device_type: string;
  status: string;
  last_sync: string;
};

export type CoachNotification = {
  id: string;
  title: string;
  message: string;
  kind: string;
  is_read: boolean;
  created_at: string;
};

export const WORKOUT_TYPES = [
  "Strength",
  "Cardio",
  "HIIT",
  "Yoga",
  "Mobility",
  "Cycling",
  "Running",
  "Swimming",
  "Walking",
] as const;

export const INTENSITY_LEVELS = ["Easy", "Moderate", "Hard", "Max"] as const;

export const MEAL_TYPES = ["Breakfast", "Lunch", "Dinner", "Snack"] as const;

export const FITNESS_LEVELS = ["Beginner", "Intermediate", "Advanced"] as const;

export const GOALS = [
  "Lose fat",
  "Build strength",
  "Improve endurance",
  "Stay active",
  "Improve mobility",
  "General health",
] as const;

export const EQUIPMENT_OPTIONS = [
  "Full gym",
  "Home gym",
  "Dumbbells only",
  "Bodyweight only",
  "Outdoor only",
] as const;

export const DAY_LABELS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] as const;



