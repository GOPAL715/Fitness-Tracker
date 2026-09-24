import { apiClient, json } from "./apiClient";
const resource = "/goals";
export const listGoals = () => apiClient(resource);
export const createGoal = (payload: unknown) => apiClient(resource, { method: "POST", ...json(payload) });
export const updateGoal = (id: string, payload: unknown) => apiClient(`${resource}/${id}`, { method: "PUT", ...json(payload) });
export const deleteGoal = (id: string) => apiClient<void>(`${resource}/${id}`, { method: "DELETE" });
