import { apiClient } from "./apiClient";
const range = (params?: Record<string, string | number>) => {
  const query = new URLSearchParams(params as Record<string, string>).toString();
  return query ? `?${query}` : "";
};
export const getDashboardAnalytics = () => apiClient("/analytics/dashboard");
export const getWorkoutAnalytics = (params?: Record<string, string | number>) => apiClient(`/analytics/workouts${range(params)}`);
export const getNutritionAnalytics = (params?: Record<string, string | number>) => apiClient(`/analytics/nutrition${range(params)}`);
export const getProgressAnalytics = (params?: Record<string, string | number>) => apiClient(`/analytics/progress${range(params)}`);
export const getWeeklyAnalytics = (params?: Record<string, string | number>) => apiClient(`/analytics/weekly${range(params)}`);
export const getAnalytics = getDashboardAnalytics;
