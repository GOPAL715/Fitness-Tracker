import { apiClient } from "./apiClient";
export const getAnalytics = () => apiClient("/analytics");
export const getProgressAnalytics = (params?: Record<string, string | number>) => {
  const query = new URLSearchParams(params as Record<string, string>).toString();
  return apiClient(`/analytics/progress${query ? `?${query}` : ""}`);
};
