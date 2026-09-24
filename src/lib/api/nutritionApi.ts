import { apiClient, json } from "./apiClient";
const resource = "/meals";
export const listMeals = () => apiClient(resource);
export const getMeal = (id: string) => apiClient(`${resource}/${id}`);
export const createMeal = (payload: unknown) => apiClient(resource, { method: "POST", ...json(payload) });
export const updateMeal = (id: string, payload: unknown) => apiClient(`${resource}/${id}`, { method: "PUT", ...json(payload) });
export const deleteMeal = (id: string) => apiClient<void>(`${resource}/${id}`, { method: "DELETE" });
export const createMealItems = (mealId: string, items: unknown[]) => apiClient("/meal-items", { method: "POST", ...json({ mealId, items }) });
