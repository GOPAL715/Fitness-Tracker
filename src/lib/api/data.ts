import { apiClient, json } from "./client";
import type { DailyMetric, HealthDevice, CoachNotification } from "../domain";

export async function getAppData(): Promise<any> {
  const value = await apiClient<any>("/app-data");
  return value.appData ?? value.data ?? value;
}
// Profile reads go through getAppData and Profile writes through the apiData adapter in
// dataAdapter, so this module carries no profile-specific call of its own.
export async function updateDevice(id: string, patch: Partial<HealthDevice>) { return apiClient<HealthDevice>(`/health-devices/${id}`, { method: "PATCH", ...json(patch) }); }
export async function markNotificationRead(id: string) { return apiClient<CoachNotification>(`/coach-notifications/${id}/read`, { method: "POST" }); }
export async function analyzeCoach() { return apiClient<any>("/coach/analyze", { method: "POST", ...json({}) }); }
export async function addWater(todayMetric: DailyMetric | null, oz: number) {
  return apiClient("/water", { method: "POST", ...json(todayMetric ? { id: todayMetric.id, amount: oz, water_oz: todayMetric.water_oz + oz } : { amount: oz, oz }) });
}
export async function saveBodyMetric(payload: unknown) { return apiClient("/body-metrics", { method: "POST", ...json(payload) }); }
