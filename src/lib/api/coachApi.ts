import { apiClient, json } from "./apiClient";
export const analyzeCoach = (payload: unknown = {}) => apiClient<{ review: unknown }>("/coach/analyze", { method: "POST", ...json(payload) });
export const generateCoachReview = analyzeCoach;
