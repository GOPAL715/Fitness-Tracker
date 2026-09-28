import { apiClient, json } from "./apiClient";

/**
 * One detected item, in exactly the shape ScannerService serialises it.
 *
 * <p>These records are not snake-cased by the server, so the field names are the record component
 * names: foodId and name, not food_id and food_name. Reading the wrong ones silently produced items
 * with no food and no name.
 */
export type ScanItem = {
  id: string;
  foodId: string | null;
  name: string;
  grams: number;
  confidence: number | null;
  calories?: number | null;
  proteinG?: number | null;
  fiberG?: number | null;
  userEdited?: boolean;
};

export type FoodScan = {
  id: string;
  status: string;
  model?: string | null;
  meal_id?: string | null;
  error?: string | null;
  items: ScanItem[];
  totalCalories?: number;
  totalProteinG?: number;
  totalCarbsG?: number;
  totalFatG?: number;
};

/** Uploads a photo for analysis. The server owns the image and the whole AI round trip. */
export async function scanFood(file: Blob): Promise<FoodScan> {
  const form = new FormData();
  form.append("file", file, "meal.jpg");
  return apiClient<FoodScan>("/food-scans", { method: "POST", body: form });
}
export const analyzeFoodPhoto = scanFood;

/** One correction, addressed by the server's item id rather than by the food's name. */
export type ScanItemCorrection = { itemId: string; foodId?: string | null; name?: string; grams: number };

/** Applies the user's corrections server-side, which recalculates the item's nutrition. */
export const correctScanItems = (scanId: string, items: ScanItemCorrection[]) =>
  apiClient<FoodScan>(`/food-scans/${scanId}/items`, { method: "PUT", ...json(items) });

/**
 * Confirms a scan into a meal. The server creates the meal and its items in one transaction and
 * derives the totals, so a partial failure cannot leave a half-written meal behind.
 */
export const confirmFoodScan = (scanId: string, payload?: { mealDate?: string; mealType?: string; mealName?: string }) =>
  apiClient<FoodScan>(`/food-scans/${scanId}/confirm`, { method: "POST", ...json(payload ?? {}) });
