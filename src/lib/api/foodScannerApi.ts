import { apiClient, json } from "./apiClient";
export type FoodScan = {
  scan_id?: string;
  apiDataScanId?: string;
  status?: string;
  items?: Array<Record<string, unknown>>;
  ai_model?: string | null;
  [key: string]: unknown;
};
export async function scanFood(file: Blob) {
  const form = new FormData();
  form.append("file", file, "meal.jpg");
  return apiClient<FoodScan>("/food-scans", { method: "POST", body: form });
}
export const analyzeFoodPhoto = scanFood;
export const confirmFoodScan = (scanId: string, payload: unknown) => apiClient(`/food-scans/${scanId}/confirm`, { method: "POST", ...json(payload) });
