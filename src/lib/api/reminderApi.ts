import { apiClient, json } from "./apiClient";
const resource = "/reminders";
export const listReminders = () => apiClient(resource);
export const createReminder = (payload: unknown) => apiClient(resource, { method: "POST", ...json(payload) });
export const updateReminder = (id: string, payload: unknown) => apiClient(`${resource}/${id}`, { method: "PUT", ...json(payload) });
export const deleteReminder = (id: string) => apiClient<void>(`${resource}/${id}`, { method: "DELETE" });
