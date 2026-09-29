import { apiClient, json } from "./apiClient";
const resource = "/reminders";

/** A reminder as the generic API returns it. Delivery state is server-owned and read-only. */
export type Reminder = {
  id: string;
  type?: string | null;
  title?: string | null;
  message?: string | null;
  scheduled_time?: string | null;
  days_of_week?: string | null;
  enabled?: boolean;
  timezone?: string | null;
  recurrence?: string | null;
  quiet_hours_start?: string | null;
  quiet_hours_end?: string | null;
  next_occurrence_at?: string | null;
  delivery_status?: string | null;
  delivery_attempts?: number | null;
  last_error?: string | null;
  last_delivered_at?: string | null;
};

export const listReminders = () => apiClient<Reminder[]>(resource);

/**
 * One reminder by id, for the deep-linked detail view.
 *
 * Scoped by the server to the authenticated user, so a 404 is the answer for another user's id -
 * the endpoint never confirms that someone else's reminder exists.
 */
export const getReminder = (id: string) => apiClient<Reminder>(`${resource}/${id}`);

export const createReminder = (payload: unknown) => apiClient(resource, { method: "POST", ...json(payload) });
export const updateReminder = (id: string, payload: unknown) => apiClient(`${resource}/${id}`, { method: "PUT", ...json(payload) });
export const deleteReminder = (id: string) => apiClient<void>(`${resource}/${id}`, { method: "DELETE" });
