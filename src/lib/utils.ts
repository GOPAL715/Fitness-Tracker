export const DAY_LABELS_MON = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] as const;

export function todayISO(): string {
  return new Date().toISOString().split("T")[0];
}

export function dateOffset(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().split("T")[0];
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


