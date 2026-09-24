import { apiClient, json } from "./apiClient";
const resource = "/foods";
export const listFoods = () => apiClient(resource);
export const getFood = (id: string) => apiClient(`${resource}/${id}`);
export const createFood = (payload: unknown) => apiClient(resource, { method: "POST", ...json(payload) });
export const updateFood = (id: string, payload: unknown) => apiClient(`${resource}/${id}`, { method: "PUT", ...json(payload) });
export const deleteFood = (id: string) => apiClient<void>(`${resource}/${id}`, { method: "DELETE" });
