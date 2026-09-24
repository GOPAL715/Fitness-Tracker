import { apiClient, json } from "./apiClient";
const resources = ["workouts", "workout-sessions", "workout-templates", "workout-template-exercises", "exercise-sets", "plan-sessions"] as const;
type Resource = typeof resources[number];
const resourceApi = <T,>(resource: Resource) => ({
  list: () => apiClient<T>(`/${resource}`),
  get: (id: string) => apiClient<T>(`/${resource}/${id}`),
  create: (payload: unknown) => apiClient<T>(`/${resource}`, { method: "POST", ...json(payload) }),
  update: (id: string, payload: unknown) => apiClient<T>(`/${resource}/${id}`, { method: "PUT", ...json(payload) }),
  remove: (id: string) => apiClient<void>(`/${resource}/${id}`, { method: "DELETE" }),
});
export const workoutsApi = resourceApi<unknown>("workouts");
export const workoutSessionsApi = resourceApi<unknown>("workout-sessions");
export const workoutTemplatesApi = resourceApi<unknown>("workout-templates");
export const workoutTemplateExercisesApi = resourceApi<unknown>("workout-template-exercises");
export const exerciseSetsApi = resourceApi<unknown>("exercise-sets");
export const planSessionsApi = resourceApi<unknown>("plan-sessions");
