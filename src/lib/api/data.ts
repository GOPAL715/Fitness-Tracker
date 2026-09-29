import { apiClient, json } from "./client";
import type { DailyMetric, CoachNotification } from "../domain";

export async function getAppData(): Promise<any> {
  const value = await apiClient<any>("/app-data");
  return value.appData ?? value.data ?? value;
}
// Profile reads go through getAppData and Profile writes through the apiData adapter in
// dataAdapter, so this module carries no profile-specific call of its own.
//
// Phase 20: updateDevice was removed from this module. It targeted PATCH /api/v1/health-devices/{id},
// a hyphenated path that has never existed on the server - the same mistake healthApi.ts had already
// fixed and documented. It was dead code that pointed at a 404, and leaving it would have invited a
// second caller to wire up a route that is not there. Health writes go through healthApi.ts and
// healthIntegrationsApi.ts, which target the served /health/devices routes.
export async function markNotificationRead(id: string) { return apiClient<CoachNotification>(`/coach-notifications/${id}/read`, { method: "POST" }); }
export async function analyzeCoach() { return apiClient<any>("/coach/analyze", { method: "POST", ...json({}) }); }
export async function addWater(todayMetric: DailyMetric | null, oz: number) {
  // water_oz is null when nothing was recorded, and null is not zero: the server accumulates
  // onto whatever is stored, so sending a locally summed total would double-count. Only the amount
  // is sent, and the response reports the authoritative total.
  return apiClient("/water", { method: "POST", ...json({ amount: oz, oz }) });
}
export async function saveBodyMetric(payload: unknown) { return apiClient("/body-metrics", { method: "POST", ...json(payload) }); }
