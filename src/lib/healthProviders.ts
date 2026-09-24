import type { DailyMetric } from "./domain";
import { round } from "./utils";

/**
 * Health provider abstraction.
 *
 * FitTrack can already store daily health metrics, but reading them from a real
 * device platform requires capabilities that do not exist in a browser:
 *
 * - Apple Health has no web API. It is only reachable from a native iOS app
 *   using HealthKit, so it needs a native wrapper or a companion app.
 * - Google Health Connect is likewise Android-native only.
 * - Fitbit and Garmin are reachable over the web, but require an OAuth
 *   application registered with each provider plus a server-side token
 *   exchange, because the client secret must never ship in the browser.
 *
 * Rather than fake any of these, this module defines the contract each provider
 * must satisfy, normalises any imported data into the shape of `daily_metrics`,
 * and reports an honest status. When a provider is connected on the server, the
 * same interface is what its importer will implement.
 */

export type HealthProviderId = "apple-health" | "health-connect" | "fitbit" | "garmin" | "manual";

export type HealthProviderStatus = "available" | "requires-native-app" | "requires-oauth-setup" | "manual";

export type NormalisedHealthDay = {
  metric_date: string;
  steps: number | null;
  sleep_hours: number | null;
  resting_heart_rate: number | null;
  hrv: number | null;
  calories_burned: number | null;
  active_minutes: number | null;
  source: HealthProviderId;
};

export type HealthProvider = {
  id: HealthProviderId;
  label: string;
  status: HealthProviderStatus;
  /** Plain-language explanation of the integration boundary for this provider. */
  boundary: string;
  /** Whether FitTrack can pull data from this provider today, in this app. */
  canSyncNow: boolean;
  /** Maps a provider payload onto the shared daily metrics shape. */
  normalise: (payload: Record<string, unknown>) => NormalisedHealthDay | null;
};

function num(value: unknown): number | null {
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

/** Shared normaliser: every provider maps its own field names onto canonical ones. */
function normaliseWith(
  source: HealthProviderId,
  payload: Record<string, unknown>,
  fieldMap: Record<string, string[]>
): NormalisedHealthDay | null {
  const date = String(payload.date ?? payload.metric_date ?? "");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return null;

  const pick = (keys: string[]): number | null => {
    for (const k of keys) {
      const v = num(payload[k]);
      if (v !== null) return v;
    }
    return null;
  };

  return {
    metric_date: date,
    steps: pick(fieldMap.steps ?? []),
    sleep_hours: pick(fieldMap.sleep ?? []),
    resting_heart_rate: pick(fieldMap.restingHeartRate ?? []),
    hrv: pick(fieldMap.hrv ?? []),
    calories_burned: pick(fieldMap.calories ?? []),
    active_minutes: pick(fieldMap.activeMinutes ?? []),
    source,
  };
}

export const HEALTH_PROVIDERS: HealthProvider[] = [
  {
    id: "apple-health",
    label: "Apple Health",
    status: "requires-native-app",
    boundary:
      "Apple Health has no web API. Reading it needs a native iOS app using HealthKit, so it cannot be connected from this browser app. If FitTrack is wrapped in a native shell later, that shell implements this interface and posts normalised days to the server.",
    canSyncNow: false,
    normalise: (payload) =>
      normaliseWith("apple-health", payload, {
        steps: ["steps", "stepCount", "HKQuantityTypeIdentifierStepCount"],
        sleep: ["sleep", "sleepHours", "HKCategoryTypeIdentifierSleepAnalysis"],
        restingHeartRate: ["restingHeartRate", "restingHeartRateBpm", "HKQuantityTypeIdentifierRestingHeartRate"],
        hrv: ["hrv", "hrvMs", "HKQuantityTypeIdentifierHeartRateVariabilitySDNN"],
        calories: ["calories", "activeEnergy", "HKQuantityTypeIdentifierActiveEnergyBurned"],
        activeMinutes: ["activeMinutes", "exerciseMinutes", "appleExerciseTime"],
      }),
  },
  {
    id: "health-connect",
    label: "Android Health Connect",
    status: "requires-native-app",
    boundary:
      "Health Connect is an Android-native API. It needs an Android app with the Health Connect SDK, so it cannot be connected from this browser app. A native shell would implement this interface identically to Apple Health.",
    canSyncNow: false,
    normalise: (payload) =>
      normaliseWith("health-connect", payload, {
        steps: ["steps", "StepsRecord"],
        sleep: ["sleep", "sleepHours", "SleepSessionRecord"],
        restingHeartRate: ["restingHeartRate", "RestingHeartRateRecord"],
        hrv: ["hrv", "HeartRateVariabilityRmssdRecord"],
        calories: ["calories", "ActiveCaloriesBurnedRecord"],
        activeMinutes: ["activeMinutes", "ExerciseSessionRecord"],
      }),
  },
  {
    id: "fitbit",
    label: "Fitbit",
    status: "requires-oauth-setup",
    boundary:
      "Fitbit is reachable over the web, but only through an OAuth application registered with Fitbit. The client secret must stay on the server, so the token exchange and the API calls belong in an Edge Function, not the browser. Once that server-side app is registered, it uses this interface to write normalised days.",
    canSyncNow: false,
    normalise: (payload) =>
      normaliseWith("fitbit", payload, {
        steps: ["steps", "summary.steps", "totalSteps"],
        sleep: ["sleep", "sleepHours", "minutesAsleep"],
        restingHeartRate: ["restingHeartRate", "restingHeartRateValue"],
        hrv: ["hrv", "hrvRmssd"],
        calories: ["calories", "caloriesOut", "activityCalories"],
        activeMinutes: ["activeMinutes", "veryActiveMinutes", "fairlyActiveMinutes"],
      }),
  },
  {
    id: "garmin",
    label: "Garmin",
    status: "requires-oauth-setup",
    boundary:
      "Garmin Connect exposes data through an OAuth application. As with Fitbit, the credentials and token exchange must live server-side in an Edge Function. This interface is what that function implements.",
    canSyncNow: false,
    normalise: (payload) =>
      normaliseWith("garmin", payload, {
        steps: ["steps", "totalSteps"],
        sleep: ["sleep", "sleepHours", "sleepingSeconds"],
        restingHeartRate: ["restingHeartRate", "restingHeartRateInBeatsPerMinute"],
        hrv: ["hrv", "lastNightAvg"],
        calories: ["calories", "activeKilocalories", "totalKilocalories"],
        activeMinutes: ["activeMinutes", "moderateIntensityMinutes", "vigorousIntensityMinutes"],
      }),
  },
  {
    id: "manual",
    label: "Manual entry",
    status: "manual",
    boundary:
      "Entered by hand in FitTrack. Always available and always the fallback when no device is connected.",
    canSyncNow: true,
    normalise: (payload) =>
      normaliseWith("manual", payload, {
        steps: ["steps"],
        sleep: ["sleep_hours", "sleep"],
        restingHeartRate: ["resting_heart_rate"],
        hrv: ["hrv"],
        calories: ["calories_burned"],
        activeMinutes: ["active_minutes"],
      }),
  },
];

export function providerById(id: string): HealthProvider | null {
  return HEALTH_PROVIDERS.find((p) => p.id === id) ?? null;
}

export function statusLabel(status: HealthProviderStatus): string {
  switch (status) {
    case "available": return "Connected";
    case "manual": return "Manual";
    case "requires-native-app": return "Needs a mobile app";
    case "requires-oauth-setup": return "Needs server credentials";
  }
}

export function statusTone(status: HealthProviderStatus): string {
  switch (status) {
    case "available": return "#4ade80";
    case "manual": return "#38bdf8";
    case "requires-oauth-setup": return "#fb923c";
    case "requires-native-app": return "#94a3b8";
  }
}

/**
 * Merges normalised provider days into the existing daily metrics rows.
 * Only non-null values overwrite an existing day so a partial sync never wipes
 * data that is already recorded, and days with no existing row are appended.
 */
export function mergeHealthDays(existing: DailyMetric[], incoming: NormalisedHealthDay[]): DailyMetric[] {
  const byDate = new Map(existing.map((m) => [m.metric_date, { ...m }]));

  for (const day of incoming) {
    const current = byDate.get(day.metric_date);
    if (!current) {
      byDate.set(day.metric_date, {
        id: `imported-${day.metric_date}`,
        metric_date: day.metric_date,
        steps: day.steps ?? 0,
        sleep_hours: day.sleep_hours ?? 0,
        calories_burned: day.calories_burned ?? 0,
        water_oz: 0,
        resting_heart_rate: day.resting_heart_rate ?? 0,
        readiness: 0,
        hrv: day.hrv ?? 0,
        active_minutes: day.active_minutes ?? 0,
        stress_level: 0,
      });
      continue;
    }
    if (day.steps !== null) current.steps = day.steps;
    if (day.sleep_hours !== null) current.sleep_hours = round(day.sleep_hours, 1);
    if (day.resting_heart_rate !== null) current.resting_heart_rate = day.resting_heart_rate;
    if (day.hrv !== null) current.hrv = day.hrv;
    if (day.calories_burned !== null) current.calories_burned = day.calories_burned;
    if (day.active_minutes !== null) current.active_minutes = day.active_minutes;
  }

  return Array.from(byDate.values()).sort((a, b) => b.metric_date.localeCompare(a.metric_date));
}


