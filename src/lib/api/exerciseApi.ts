import { apiClient, json } from "./apiClient";
const resource = "/exercises";
export const listExercises = () => apiClient(resource);
export const getExercise = (id: string) => apiClient(`${resource}/${id}`);
export const createExercise = (payload: unknown) => apiClient(resource, { method: "POST", ...json(payload) });
export const updateExercise = (id: string, payload: unknown) => apiClient(`${resource}/${id}`, { method: "PUT", ...json(payload) });
export const deleteExercise = (id: string) => apiClient<void>(`${resource}/${id}`, { method: "DELETE" });
