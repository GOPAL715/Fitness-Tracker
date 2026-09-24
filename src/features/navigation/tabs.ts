import type { LucideIcon } from "lucide-react";
import {
  Activity, Dumbbell, TrendingUp, Utensils, Target, Repeat, User, CalendarDays,
} from "lucide-react";

export type Tab =
  | "today"
  | "workouts"
  | "progress"
  | "nutrition"
  | "habits"
  | "goals"
  | "calendar"
  | "profile";

export type TabDefinition = {
  id: Tab;
  label: string;
  icon: LucideIcon;
};

export const TABS: TabDefinition[] = [
  { id: "today", label: "Today", icon: Activity },
  { id: "workouts", label: "Workouts", icon: Dumbbell },
  { id: "progress", label: "Progress", icon: TrendingUp },
  { id: "nutrition", label: "Nutrition", icon: Utensils },
  { id: "habits", label: "Habits", icon: Repeat },
  { id: "goals", label: "Goals", icon: Target },
  { id: "calendar", label: "History", icon: CalendarDays },
  { id: "profile", label: "Profile", icon: User },
];

export const DEFAULT_TAB: Tab = "today";


