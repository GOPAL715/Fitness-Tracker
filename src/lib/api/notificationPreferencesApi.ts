import { apiClient, json } from "./apiClient";

/**
 * User-level notification preferences (Phase 19).
 *
 * <p>These are the user's own settings, entirely separate from the browser facts Phase 18 reports.
 * Permission, server configuration and whether this browser is subscribed are all things the app
 * observes; these are things the user decided, and the app cannot infer one from another.
 *
 * <p>No browser permission and no push endpoint is read or sent here: permission is the browser's
 * answer to the user, and an endpoint is a per-installation secret the server already owns.
 */

export type NotificationPreferences = {
  /** Account-wide delivery switch. Independent of any individual reminder's enabled flag. */
  pushEnabled: boolean;
  /** Whether reminder notifications are wanted at all. Both switches must be on to deliver. */
  reminderNotificationsEnabled: boolean;
  quietHoursEnabled: boolean;
  /** HH:mm, or null when quiet hours are off. */
  quietHoursStart: string | null;
  quietHoursEnd: string | null;
  /**
   * IANA zone id, or null when the user has not chosen one.
   *
   * <p>Null is a real answer, not a missing value to be defaulted: quiet hours resolve a wall-clock
   * window, and guessing a zone would silently place someone's night in the wrong hours.
   */
  timezone: string | null;
};

export const getNotificationPreferences = () =>
  apiClient<NotificationPreferences>("/notification-preferences");

/**
 * Saves the caller's preferences.
 *
 * <p>The server rejects an unusable timezone, a malformed time, and a start equal to the end rather
 * than repairing any of them, so a rejected save means nothing was changed.
 */
export const saveNotificationPreferences = (preferences: NotificationPreferences) =>
  apiClient<NotificationPreferences>("/notification-preferences", {
    method: "PUT",
    ...json(preferences),
  });
