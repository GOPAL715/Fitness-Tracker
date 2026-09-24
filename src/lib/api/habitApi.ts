import { apiClient, json } from "./apiClient";
const resource = "/habits";
export const listHabits = () => apiClient(resource);
export const createHabit = (payload: unknown) => apiClient(resource, { method: "POST", ...json(payload) });
export const updateHabit = (id: string, payload: unknown) => apiClient(`${resource}/${id}`, { method: "PUT", ...json(payload) });
export const deleteHabit = (id: string) => apiClient<void>(`${resource}/${id}`, { method: "DELETE" });
export const logHabit = (id: string, payload: unknown) => apiClient(`/habit-logs/${id}`, { method: "POST", ...json(payload) });
