import { apiClient } from "./apiClient";
export const getCalendarDay = (date: string) => apiClient(`/calendar/${encodeURIComponent(date)}`);
export const getCalendarRange = (from: string, to: string) => apiClient(`/calendar/${encodeURIComponent(from)}/${encodeURIComponent(to)}`);
