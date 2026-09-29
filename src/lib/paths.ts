/**
 * The one place a URL path is interpreted.
 *
 * FitTrack has no router: navigation is a `useState<Tab>`. The single exception is a reminder deep
 * link, because a push notification has to be able to land the user on a specific reminder. Every
 * path decision in the application - and the matching decision in the service worker - goes through
 * this module so there is exactly one definition of what a valid reminder path is.
 *
 * The rules are deliberately strict. Only `/reminders/{uuid}` is accepted, the id must be a
 * syntactically valid UUID, and nothing else about the incoming value is ever interpreted. A path is
 * always *constructed* here, never passed through, so a payload or a crafted URL cannot introduce a
 * scheme, a host, or a protocol-relative prefix.
 */

/** Canonical route prefix. Kept in one place so the app and the worker cannot drift apart. */
export const REMINDER_PATH_PREFIX = "/reminders/";

/**
 * Matches a bare UUID: hex groups separated by hyphens, nothing else.
 *
 * Anchored, so a value like `https://evil.example` or `../../etc/passwd` cannot match a substring of
 * it. Length is bounded by the group structure rather than by a separate check.
 */
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Whether a value is a syntactically valid reminder id. Case-insensitive; UUIDs are hex. */
export function isReminderId(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

/**
 * The reminder id encoded in a pathname, or null when the path is not a reminder route.
 *
 * Returns null - never a fallback path and never a partially-parsed value - for anything it does not
 * fully recognise, so an unknown URL simply leaves the application on its normal tab screen.
 */
export function parseReminderPath(pathname: string | undefined | null): string | null {
  if (typeof pathname !== "string") return null;
  // Only the path is considered. A query string or hash is irrelevant to routing and is dropped by
  // reading the portion before the first `?` or `#`, so it cannot be used to influence navigation.
  const path = pathname.split(/[?#]/, 1)[0];
  if (!path.startsWith(REMINDER_PATH_PREFIX)) return null;
  // Exactly one segment: a trailing slash or an extra path element is not the canonical form.
  const remainder = path.slice(REMINDER_PATH_PREFIX.length);
  if (!remainder || remainder.includes("/")) return null;
  return isReminderId(remainder) ? remainder : null;
}

/**
 * The canonical same-origin path for a reminder.
 *
 * Returns the app root for anything that is not a valid id, which is what makes this safe to call
 * directly on a value from a push payload: no input can make it produce an off-origin path.
 */
export function reminderPath(reminderId: unknown): string {
  return isReminderId(reminderId) ? `${REMINDER_PATH_PREFIX}${reminderId}` : "/";
}
