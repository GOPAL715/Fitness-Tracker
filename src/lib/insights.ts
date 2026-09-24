import type { DailyMetric, Workout, Meal, Profile, PersonalRecord } from "./domain";
import { clamp, round, todayISO, dateOffset } from "./utils";

export type Insight = {
  title: string;
  detail: string;
  tone: "positive" | "caution" | "neutral";
};

export type Recommendation = {
  headline: string;
  body: string;
  action: string;
  kind: "train" | "recover" | "move" | "eat";
};

export type ScoreCard = {
  label: string;
  value: number;
  max: number;
  explanation: string;
};

export function computeReadiness(metric: DailyMetric | null, recent: DailyMetric[]): number {
  if (!metric) return 0;
  const sleepScore = clamp((metric.sleep_hours / 8) * 40, 0, 40);
  const hrvBaseline = recent.length ? recent.reduce((s, m) => s + m.hrv, 0) / recent.length : metric.hrv;
  const hrvScore = hrvBaseline > 0 ? clamp((metric.hrv / hrvBaseline) * 30, 0, 30) : 15;
  const rhrBaseline = recent.length ? recent.reduce((s, m) => s + m.resting_heart_rate, 0) / recent.length : metric.resting_heart_rate;
  const rhrScore = rhrBaseline > 0 ? clamp((rhrBaseline / Math.max(metric.resting_heart_rate, 1)) * 20, 0, 20) : 10;
  const stressScore = clamp((10 - metric.stress_level) * 1, 0, 10);
  return Math.round(clamp(sleepScore + hrvScore + rhrScore + stressScore, 0, 100));
}

export function computeTrainingLoad(workouts: Workout[]): { load: number; label: string } {
  const week = workouts.filter((w) => w.workout_date >= dateOffset(6));
  const load = week.reduce((sum, w) => sum + w.duration_minutes * (w.perceived_effort / 10), 0);
  let label = "Light";
  if (load > 900) label = "Very high";
  else if (load > 600) label = "High";
  else if (load > 300) label = "Moderate";
  return { load: Math.round(load), label };
}

export function computeStreak(workouts: Workout[]): number {
  const completedDays = new Set(workouts.filter((w) => w.completed).map((w) => w.workout_date));
  let streak = 0;
  for (let i = 0; i < 120; i++) {
    const day = dateOffset(i);
    if (completedDays.has(day)) streak++;
    else if (i === 0) continue;
    else break;
  }
  return streak;
}

export function computeWeekProgress(workouts: Workout[], profile: Profile | null): { count: number; target: number; minutes: number; minuteTarget: number } {
  const week = workouts.filter((w) => w.workout_date >= dateOffset(6) && w.completed);
  const count = new Set(week.map((w) => w.workout_date)).size;
  const minutes = week.reduce((s, w) => s + w.duration_minutes, 0);
  return {
    count,
    target: profile?.activity_target ?? 4,
    minutes,
    minuteTarget: profile?.weekly_minutes ?? 180,
  };
}

export function computeConsistency(workouts: Workout[], days: number): number {
  const completedDays = new Set(
    workouts.filter((w) => w.completed && w.workout_date >= dateOffset(days - 1)).map((w) => w.workout_date)
  );
  return Math.round((completedDays.size / days) * 100);
}

export function macroTotals(meals: Meal[]): { calories: number; protein: number; carbs: number; fat: number } {
  return meals.reduce(
    (acc, m) => ({
      calories: acc.calories + m.calories,
      protein: acc.protein + m.protein_g,
      carbs: acc.carbs + m.carbs_g,
      fat: acc.fat + m.fat_g,
    }),
    { calories: 0, protein: 0, carbs: 0, fat: 0 }
  );
}

export function buildInsights(
  todayMetric: DailyMetric | null,
  recent: DailyMetric[],
  workouts: Workout[],
  profile: Profile | null
): Insight[] {
  const insights: Insight[] = [];
  const week = workouts.filter((w) => w.workout_date >= dateOffset(6) && w.completed);

  if (recent.length >= 4) {
    const thisWeek = recent.slice(0, 3);
    const lastWeek = recent.slice(3, 7);
    if (lastWeek.length) {
      const hrvNow = thisWeek.reduce((s, m) => s + m.hrv, 0) / thisWeek.length;
      const hrvBefore = lastWeek.reduce((s, m) => s + m.hrv, 0) / lastWeek.length;
      if (hrvBefore > 0) {
        const pct = round(((hrvNow - hrvBefore) / hrvBefore) * 100);
        if (Math.abs(pct) >= 4) {
          insights.push({
            title: pct > 0 ? `Recovery improved ${pct}%` : `Recovery dropped ${Math.abs(pct)}%`,
            detail:
              pct > 0
                ? "Your heart rate variability is trending above last week. Your body is adapting well to the current training load."
                : "Heart rate variability fell below last week. Consider an easier session and prioritize sleep.",
            tone: pct > 0 ? "positive" : "caution",
          });
        }
      }
    }
  }

  if (profile && todayMetric) {
    const sleepGap = round(profile.sleep_target_hours - todayMetric.sleep_hours, 1);
    if (sleepGap > 1) {
      insights.push({
        title: `${sleepGap} hours short on sleep`,
        detail: `You logged ${todayMetric.sleep_hours} hours against a target of ${profile.sleep_target_hours}. Sleep is the strongest driver of recovery and strength gains.`,
        tone: "caution",
      });
    } else if (sleepGap <= 0.5) {
      insights.push({
        title: "Sleep on target",
        detail: `You hit ${todayMetric.sleep_hours} hours, right at your goal. Expect better recovery and focus today.`,
        tone: "positive",
      });
    }
  }

  if (week.length >= 3) {
    const avgEffort = round(week.reduce((s, w) => s + w.perceived_effort, 0) / week.length, 1);
    if (avgEffort >= 8) {
      insights.push({
        title: "High intensity week",
        detail: `Your average effort across ${week.length} sessions is ${avgEffort} out of 10. Mixing in an easy day will protect your recovery.`,
        tone: "caution",
      });
    } else {
      insights.push({
        title: "Balanced training intensity",
        detail: `Your average effort is ${avgEffort} out of 10 across ${week.length} sessions this week. Sustainable pacing.`,
        tone: "positive",
      });
    }
  }

  const streak = computeStreak(workouts);
  if (streak >= 3) {
    insights.push({
      title: `${streak} day active streak`,
      detail: "Consistency is the biggest predictor of long-term results. Keep the chain going.",
      tone: "positive",
    });
  }

  return insights.slice(0, 4);
}

export function buildRecommendation(
  todayMetric: DailyMetric | null,
  workouts: Workout[],
  profile: Profile | null,
  readiness: number
): Recommendation {
  const planned = workouts.filter((w) => w.workout_date === todayISO() && !w.completed);
  const load = computeTrainingLoad(workouts);

  if (planned.length > 0) {
    return {
      headline: `Today's plan: ${planned[0].title}`,
      body: `You have ${planned[0].duration_minutes} minutes of ${planned[0].workout_type.toLowerCase()} scheduled at ${planned[0].intensity.toLowerCase()} intensity.`,
      action: "Start session",
      kind: "train",
    };
  }

  if (readiness > 0 && readiness < 55) {
    return {
      headline: "Prioritize recovery today",
      body: "Your readiness is below your baseline because of short sleep or elevated resting heart rate. A lighter day now means a stronger session tomorrow.",
      action: "See recovery plan",
      kind: "recover",
    };
  }

  if (load.label === "Very high" || load.label === "High") {
    return {
      headline: "You have earned an easy day",
      body: `Training load is ${load.label.toLowerCase()} this week. A 25 minute walk plus mobility work keeps momentum without adding strain.`,
      action: "Log a light session",
      kind: "move",
    };
  }

  if (todayMetric && profile && todayMetric.water_oz < profile.water_target_oz * 0.6) {
    return {
      headline: "Hydration is behind pace",
      body: `You are at ${todayMetric.water_oz} of ${profile.water_target_oz} ounces. Dehydration reduces strength output and slows recovery.`,
      action: "Log water",
      kind: "eat",
    };
  }

  if (readiness >= 75) {
    return {
      headline: "Great day to train hard",
      body: "Recovery markers look strong. This is a good window for a quality strength or interval session.",
      action: "Pick a workout",
      kind: "train",
    };
  }

  return {
    headline: "Steady progress is still progress",
    body: "A moderate 30 minute session would keep your week on track without pushing recovery limits.",
    action: "Browse workouts",
    kind: "move",
  };
}

export function buildScoreCards(
  todayMetric: DailyMetric | null,
  recent: DailyMetric[],
  workouts: Workout[],
  readiness: number,
  profile: Profile | null
): ScoreCard[] {
  const load = computeTrainingLoad(workouts);
  const consistency = computeConsistency(workouts, 28);
  const sleepAvg = recent.length ? recent.reduce((s, m) => s + m.sleep_hours, 0) / recent.length : 0;
  const rhrBaseline = recent.length ? recent.reduce((s, m) => s + m.resting_heart_rate, 0) / recent.length : 0;

  return [
    {
      label: "Readiness",
      value: readiness,
      max: 100,
      explanation:
        readiness >= 75
          ? "Composed of sleep, heart rate variability, resting heart rate, and stress. You are primed to perform."
          : readiness >= 55
          ? "Composed of sleep, heart rate variability, resting heart rate, and stress. You are in a normal training range."
          : "Composed of sleep, heart rate variability, resting heart rate, and stress. Recovery is lagging today.",
    },
    {
      label: "Recovery",
      value: todayMetric && rhrBaseline > 0 ? Math.round(clamp((rhrBaseline / Math.max(todayMetric.resting_heart_rate, 1)) * 85, 30, 100)) : 70,
      max: 100,
      explanation: `Your resting heart rate is ${todayMetric?.resting_heart_rate ?? 0} bpm against a 28 day baseline of ${Math.round(rhrBaseline)} bpm.`,
    },
    {
      label: "Training load",
      value: Math.round(clamp((load.load / 900) * 100, 0, 100)),
      max: 100,
      explanation: `${load.label} load this week at ${load.load} points. Calculated from session duration multiplied by perceived effort.`,
    },
    {
      label: "Consistency",
      value: consistency,
      max: 100,
      explanation: `You trained on ${consistency}% of the last 28 days. Consistency drives long-term adaptation more than any single session.`,
    },
  ];
}

export function buildNutritionNotes(
  totals: { calories: number; protein: number },
  profile: Profile | null
): string[] {
  if (!profile) return [];
  const notes: string[] = [];
  const calPct = Math.round((totals.calories / profile.calorie_target) * 100);
  const proteinPct = Math.round((totals.protein / profile.protein_target_g) * 100);
  notes.push(`You are at ${calPct}% of your ${profile.calorie_target} calorie target today.`);
  notes.push(
    proteinPct >= 100
      ? `Protein goal reached at ${totals.protein}g. That supports muscle repair.`
      : `Protein is at ${totals.protein}g of ${profile.protein_target_g}g. Adding a protein rich snack would close the gap.`
  );
  return notes;
}

export function projectGoalDate(records: PersonalRecord[]): string | null {
  const withPrev = records.filter((r) => r.previous_value !== null && r.previous_value !== undefined);
  if (!withPrev.length) return null;
  const gain = withPrev.reduce((s, r) => s + (r.record_value - (r.previous_value ?? 0)), 0);
  if (gain <= 0) return null;
  const weeks = clamp(Math.round(60 / gain), 2, 26);
  const d = new Date();
  d.setDate(d.getDate() + weeks * 7);
  return d.toLocaleDateString(undefined, { month: "long", year: "numeric" });
}


