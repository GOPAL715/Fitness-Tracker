import { apiClient, json } from "./apiClient";
const resource = "/reminders";

/**
 * A reminder as the generic API actually returns it.
 *
 * <p>The list and detail endpoints both issue `SELECT t.*`, so this mirrors the stored row rather than
 * a curated subset. Fields the server owns - `user_id`, `next_occurrence_at`, `delivery_status`,
 * `delivery_attempts`, `last_error`, `last_delivered_at` - are readable here and are deliberately
 * absent from {@link ReminderWrite}: the API rejects them on every write verb, and the UI must not
 * attempt to send what it cannot own.
 */
export type Reminder = {
  id: string;
  user_id?: string | null;
  type?: string | null;
  title?: string | null;
  message?: string | null;
  /** SQL `time`, serialised with seconds, e.g. "06:00:00". */
  scheduled_time?: string | null;
  /** Comma-separated day indices, 0 = Sunday through 6 = Saturday. */
  days_of_week?: string | null;
  enabled?: boolean;
  quiet_hours_start?: string | null;
  quiet_hours_end?: string | null;
  timezone?: string | null;
  /** One of the three literals the backend understands: "once", "daily", "weekly". */
  recurrence?: string | null;
  delivery_status?: string | null;
  delivery_attempts?: number | null;
  last_error?: string | null;
  last_delivered_at?: string | null;
  next_occurrence_at?: string | null;
};

/** Exactly the fields a client may write. */
export type ReminderWrite = {
  type?: string;
  title?: string;
  message?: string;
  scheduled_time?: string;
  days_of_week?: string;
  enabled?: boolean;
  timezone?: string;
  recurrence?: string;
};

export const listReminders = () => apiClient<Reminder[]>(resource);

/**
 * One reminder by id, for the deep-linked detail view.
 *
 * Scoped by the server to the authenticated user, so a 404 is the answer for another user's id -
 * the endpoint never confirms that someone else's reminder exists.
 */
export const getReminder = (id: string) => apiClient<Reminder>(`${resource}/${id}`);

export const createReminder = (payload: ReminderWrite) =>
  apiClient<Reminder>(resource, { method: "POST", ...json(payload) });

export const updateReminder = (id: string, payload: ReminderWrite) =>
  apiClient<Reminder>(`${resource}/${id}`, { method: "PUT", ...json(payload) });

/** Flips a reminder on or off without resending the rest of its fields. */
export const setReminderEnabled = (id: string, enabled: boolean) =>
  apiClient<void>(`${resource}/${id}`, { method: "PUT", ...json({ enabled }) });

export const deleteReminder = (id: string) =>
  apiClient<void>(`${resource}/${id}`, { method: "DELETE" });

/**
 * Recomputes the stored next occurrence server-side, in the reminder's own timezone.
 *
 * <p>Sent explicitly rather than assumed: the server owns the calculation, so the UI asks for it and
 * reads the new {@code next_occurrence_at} back rather than computing anything locally.
 */
export const rescheduleReminder = (id: string) =>
  apiClient<{ id: string; next_occurrence_at: string; timezone: string }>(
    `${resource}/${id}/reschedule`,
    { method: "POST", ...json({}) }
  );
