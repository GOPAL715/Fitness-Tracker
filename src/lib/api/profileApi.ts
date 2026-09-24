import { apiClient, json } from "./apiClient";
export { getAppData, addWater, saveBodyMetric } from "./healthApi";
export const getProfile = () => apiClient<Record<string, unknown>>("/fitness-profile");
export const updateProfile = (id: string, payload: unknown) => apiClient(`/fitness-profile/${id}`, { method: "PUT", ...json(payload) });
export const updateFitnessProfile = updateProfile;
