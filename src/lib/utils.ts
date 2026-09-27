export const DAY_LABELS_MON = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] as const;

/**
 * Formats a Date as the calendar date it falls on in the viewer own timezone.
 *
 * <p>toISOString() cannot be used here: it converts to UTC first, so for anyone east of
 * UTC the reported day is yesterday for the first hours of the morning, and for anyone west
 * it can be tomorrow. A habit check-in is a statement about a day in the user own calendar,
 * so the local calendar date is the only correct basis for it.
 */
function localDateISO(d: Date): string {
  const month = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${month}-${day}`;
}

export function todayISO(): string {
  return localDateISO(new Date());
}

export function dateOffset(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  // The offset is walked in local time and then formatted locally, so it shares the basis that
  // todayISO uses: a streak counted across these dates cannot disagree with the date a check-in
  // was written against.
  return localDateISO(d);
}

export function dayIndex(dateISO: string): number {
  const d = new Date(dateISO + "T00:00:00");
  return (d.getDay() + 6) % 7;
}

export function formatDate(dateISO: string): string {
  const d = new Date(dateISO + "T00:00:00");
  return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

export function formatLongDate(dateISO: string): string {
  const d = new Date(dateISO + "T00:00:00");
  return d.toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" });
}

export function relativeTime(iso: string): string {
  const diffMs = Date.now() - new Date(iso).getTime();
  const mins = Math.round(diffMs / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins} min ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours} hr ago`;
  const days = Math.round(hours / 24);
  return days === 1 ? "yesterday" : `${days} days ago`;
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export function round(value: number, decimals = 0): number {
  const f = Math.pow(10, decimals);
  return Math.round(value * f) / f;
}


