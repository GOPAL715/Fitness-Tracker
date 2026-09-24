// Compatibility entry point; scanner calls now live in foodScannerApi.ts.
export { analyzeFoodPhoto, confirmFoodScan } from "./foodScannerApi";
export { createMeal, createMealItems } from "./nutritionApi";
import { apiClient, json } from "./apiClient";
type Resource = "workouts" | "plan-sessions" | "workout-sessions" | "workout-templates" | "workout-template-exercises" | "exercise-sets" | "meals" | "meal-items" | "habits" | "habit-logs" | "reminders" | "goals";
export const create = <T,>(resource: Resource, payload: unknown) => apiClient<T>(`/${resource}`, { method: "POST", ...json(payload) });
export const update = <T,>(resource: Resource, id: string, payload: unknown) => apiClient<T>(`/${resource}/${id}`, { method: "PUT", ...json(payload) });
export const remove = (resource: Resource, id: string) => apiClient<void>(`/${resource}/${id}`, { method: "DELETE" });
