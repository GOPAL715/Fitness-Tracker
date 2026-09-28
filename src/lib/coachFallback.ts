/**
 * Deterministic local Coach summary.
 *
 * <p>This exists so that an unavailable provider does not leave a dead screen. It is built purely
 * from the user's own recorded numbers, with no model involved and no randomness, so the same data
 * always produces the same text.
 *
 * <p>It is never presented as AI output. The caller labels it explicitly, because a fallback that
 * looked like the Coach would misrepresent where the text came from. It also states plainly when
 * there is not enough data, rather than padding a thin week with vague encouragement.
 */

export type LocalFallbackInput = {
  /** Completed sessions in the window. */
  sessions?: number | null;
  totalMinutes?: number | null;
  avgSleepHours?: number | null;
  avgSteps?: number | null;
  mealsLogged?: number | null;
  habitsTracked?: number | null;
};

/** Human phrasing for a window length, kept here so tests and UI cannot drift apart. */
export function windowLabel(days: number): string {
  if (days === 1) return "the last day";
  if (days === 7) return "the last 7 days";
  if (days === 30) return "the last 30 days";
  return `the last ${days} days`;
}

function num(value: number | null | undefined): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function round1(value: number): number {
  return Math.round(value * 10) / 10;
}

/**
 * Builds the fallback summary.
 *
 * <p>Thresholds are intentionally coarse. The point is an honest description of the recorded
 * numbers, not a coaching judgement, so the text avoids any claim that needs a standard this
 * function does not have (age, training history, injury status, clinical targets).
 */
export function buildLocalFallback(input: LocalFallbackInput, windowDays: number) {
  const label = windowLabel(windowDays);
  const sessions = num(input.sessions) ?? 0;
  const minutes = num(input.totalMinutes) ?? 0;
  const sleep = num(input.avgSleepHours);
  const steps = num(input.avgSteps);
  const meals = num(input.mealsLogged) ?? 0;
  const habits = num(input.habitsTracked) ?? 0;

  const observations: string[] = [];
  const recommendations: string[] = [];
  const nextActions: string[] = [];
  const warnings: string[] = [];

  const hasSignal = sessions > 0 || minutes > 0 || meals > 0 || habits > 0
    || (sleep !== null && sleep > 0) || (steps !== null && steps > 0);

  // Nothing at all was recorded. Saying so is more useful than listing four zeroes, and it avoids
  // implying a conclusion was drawn from an empty window.
  if (!hasSignal) {
    return {
      summary: `There is not enough recorded data across ${label} to summarise anything yet. Log a workout, some meals, or a day's metrics and ask again.`,
      observations: [] as string[],
      recommendations: [] as string[],
      next_actions: ["Log a workout or a day of metrics, then ask again."] as string[],
      warnings: [] as string[],
      model: "",
      request_id: "",
    };
  }

  if (sessions > 0) {
    observations.push(`You completed ${sessions} workout${sessions === 1 ? "" : "s"} across ${label}${minutes > 0 ? `, totalling ${Math.round(minutes)} minutes` : ""}.`);
    // Only compare against a target the user actually set; otherwise there is nothing to say.
    const perWeek = windowDays > 0 ? round1((sessions / windowDays) * 7) : 0;
    if (perWeek >= 2) {
      recommendations.push("Your training frequency is consistent. Keep the current rhythm rather than adding volume.");
    } else {
      recommendations.push("Training frequency is on the low side for this window. One or two more sessions would build on what you already have.");
    }
  } else {
    observations.push(`No completed workouts were recorded across ${label}.`);
    nextActions.push("Log a workout, or check that your recent sessions were saved.");
  }

  if (sleep !== null && sleep > 0) {
    observations.push(`You averaged ${round1(sleep)} hours of sleep per day.`);
    if (sleep < 6) {
      recommendations.push("Sleep is the clearest lever here. A consistent bedtime is usually easier to sustain than a longer one.");
    }
  }

  if (steps !== null && steps > 0) {
    observations.push(`You averaged ${Math.round(steps).toLocaleString()} steps per day.`);
  }

  if (meals > 0) {
    observations.push(`You logged ${meals} meal${meals === 1 ? "" : "s"}.`);
  } else {
    nextActions.push("Log a few meals so nutrition totals reflect reality.");
  }

  if (habits === 0) {
    nextActions.push("Set up one or two habits to track between workouts.");
  }

  if (sessions === 0 && sleep === null && steps === null) {
    warnings.push("Very little data was recorded in this window, so treat this as a reading of your logs rather than of your fitness.");
  }

  return {
    summary: `Here is what your own records show across ${label}.`,
    observations,
    recommendations,
    next_actions: nextActions,
    warnings,
    model: "",
    request_id: "",
  };
}
