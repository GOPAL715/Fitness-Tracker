import type { LucideIcon } from "lucide-react";
import {
  Activity, Bell, Dumbbell, TrendingUp, Utensils, Target, Repeat, User, CalendarDays, Sparkles,
} from "lucide-react";

export type Tab =
  | "today"
  | "workouts"
  | "progress"
  | "nutrition"
  | "habits"
  | "goals"
  | "reminders"
  | "coach"
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
  // Phase 16: reminders sit beside the other "things you set up and forget" surfaces, and a push
  // notification deep-links into the same detail view this tab opens.
  { id: "reminders", label: "Reminders", icon: Bell },
  { id: "habits", label: "Habits", icon: Repeat },
  { id: "goals", label: "Goals", icon: Target },
  { id: "coach", label: "Coach", icon: Sparkles },
  { id: "calendar", label: "History", icon: CalendarDays },
  { id: "profile", label: "Profile", icon: User },
];

export const DEFAULT_TAB: Tab = "today";


