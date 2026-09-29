import { useEffect, useMemo, useState } from "react";
import { Trash2 } from "lucide-react";

import { Modal } from "../components/ui";
import {
  createReminder, deleteReminder, rescheduleReminder, updateReminder,
  type Reminder, type ReminderWrite,
} from "../lib/api/reminderApi";

/**
 * Create or edit a reminder.
 *
 * <p>One form serves both, matching the modal pattern the rest of the app uses. Everything it sends is
 * on {@link ReminderWrite}, which by construction excludes the server-owned delivery fields - the API
 * would reject them anyway, and a form that quietly submitted them would be a bug waiting to surface.
 */

/**
 * The backend's day-index contract: 0 is Sunday through 6 is Saturday.
 *
 * <p>Ordered from Sunday because that is how the index runs. Stored values are the indices, not the
 * labels, so the round trip to the API is lossless and the ordering on screen is the product's choice.
 */
export const WEEK_DAYS: { index: number; label: string; short: string }[] = [
  { index: 0, label: "Sunday", short: "S" },
  { index: 1, label: "Monday", short: "M" },
  { index: 2, label: "Tuesday", short: "T" },
  { index: 3, label: "Wednesday", short: "W" },
  { index: 4, label: "Thursday", short: "T" },
  { index: 5, label: "Friday", short: "F" },
  { index: 6, label: "Saturday", short: "S" },
];

/** Exactly the three literals the backend parses. Anything else falls back to daily, so we do not rely on that. */
export const RECURRENCES = ["once", "daily", "weekly"] as const;
export type Recurrence = (typeof RECURRENCES)[number];

/** Timezones the platform knows about, with a small fallback for runtimes without the list. */
function timezones(): string[] {
  const supported = (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf;
  if (typeof supported === "function") {
    try {
      return supported("timeZone");
    } catch {
      /* fall through to the minimal set */
    }
  }
  return ["UTC", "America/New_York", "America/Los_Angeles", "Europe/London", "Asia/Kolkata", "Australia/Sydney"];
}

/** Parses a stored day list into indices, ignoring anything unrecognised. */
export function parseDays(stored: string | null | undefined): number[] {
  if (!stored) return [];
  return stored
    .split(/[,;\s]+/)
    .map((token) => Number(token.trim()))
    .filter((n) => Number.isInteger(n) && n >= 0 && n <= 6);
}

type Draft = {
  title: string;
  message: string;
  scheduled_time: string;
  recurrence: Recurrence;
  days: number[];
  timezone: string;
  enabled: boolean;
};

function draftFrom(reminder: Reminder | null, fallbackTimezone: string): Draft {
  return {
    title: reminder?.title ?? "",
    message: reminder?.message ?? "",
    // The API returns "06:00:00"; an <input type="time"> wants "06:00".
    scheduled_time: (reminder?.scheduled_time ?? "08:00").slice(0, 5),
    recurrence: (RECURRENCES as readonly string[]).includes(reminder?.recurrence ?? "")
      ? (reminder?.recurrence as Recurrence)
      : "daily",
    days: parseDays(reminder?.days_of_week),
    // Preserved on edit. Never replaced with the browser zone: that would silently move a reminder
    // the user set in a specific place.
    timezone: reminder?.timezone ?? fallbackTimezone,
    enabled: reminder?.enabled ?? true,
  };
}

type Props = {
  reminder?: Reminder | null;
  onClose: () => void;
  onSaved: (reminder: Reminder) => void;
  onDeleted?: (id: string) => void;
};

export default function ReminderFormModal({ reminder, onClose, onSaved, onDeleted }: Props) {
  const editing = Boolean(reminder);
  const zones = useMemo(timezones, []);
  const [draft, setDraft] = useState<Draft>(() => draftFrom(reminder ?? null, zones[0] ?? "UTC"));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => setDraft(draftFrom(reminder ?? null, zones[0] ?? "UTC")), [reminder, zones]);

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) =>
    setDraft((current) => ({ ...current, [key]: value }));

  const toggleDay = (index: number) =>
    setDraft((current) => ({
      ...current,
      days: current.days.includes(index)
        ? current.days.filter((d) => d !== index)
        : [...current.days, index].sort((a, b) => a - b),
    }));

  function validate(): string | null {
    if (!draft.title.trim()) return "Give the reminder a title.";
    if (!/^\d{2}:\d{2}$/.test(draft.scheduled_time)) return "Choose a time of day.";
    if (!draft.timezone) return "Choose a timezone.";
    // A weekly reminder with no day would silently become "every day" on the server.
    if (draft.recurrence === "weekly" && draft.days.length === 0) {
      return "Choose at least one day for a weekly reminder.";
    }
    return null;
  }

  function toWrite(): ReminderWrite {
    return {
      type: reminder?.type ?? "workout",
      title: draft.title.trim(),
      message: draft.message.trim(),
      // The API stores a SQL time, so seconds are restored on the way out.
      scheduled_time: `${draft.scheduled_time}:00`,
      // Days are only meaningful for weekly, and sending them otherwise is noise the server ignores.
      days_of_week: draft.recurrence === "weekly" ? draft.days.join(",") : "",
      timezone: draft.timezone,
      recurrence: draft.recurrence,
      enabled: draft.enabled,
    };
  }

  async function save() {
    const problem = validate();
    if (problem) {
      setError(problem);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const saved = editing
        ? await updateReminder(reminder!.id, toWrite())
        : await createReminder(toWrite());
      // The schedule moved, so ask the server to recompute it. A failure here must not lose the save:
      // the reminder exists, only its stored next occurrence would be stale.
      try {
        await rescheduleReminder(saved.id);
      } catch {
        /* the next scheduler pass corrects it */
      }
      onSaved(saved);
    } catch (cause: unknown) {
      setError(cause instanceof Error ? cause.message : "Could not save this reminder.");
    } finally {
      setSaving(false);
    }
  }

  async function remove() {
    if (!reminder) return;
    setDeleting(true);
    setError(null);
    try {
      await deleteReminder(reminder.id);
      onDeleted?.(reminder.id);
    } catch (cause: unknown) {
      setError(cause instanceof Error ? cause.message : "Could not delete this reminder.");
      setDeleting(false);
    }
  }


  return (
    <Modal title={editing ? "Edit reminder" : "New reminder"} onClose={onClose}>
      <div className="form-group" style={{ marginBottom: 12 }}>
        <label className="form-label" htmlFor="reminder-title">Title</label>
        <input
          id="reminder-title"
          className="form-input"
          value={draft.title}
          onChange={(e) => set("title", e.target.value)}
          placeholder="Time to train"
        />
      </div>

      <div className="form-group" style={{ marginBottom: 12 }}>
        <label className="form-label" htmlFor="reminder-message">Message</label>
        <input
          id="reminder-message"
          className="form-input"
          value={draft.message}
          onChange={(e) => set("message", e.target.value)}
          placeholder="Session starts now"
        />
      </div>

      <div className="form-row" style={{ marginBottom: 12 }}>
        <div className="form-group">
          <label className="form-label" htmlFor="reminder-time">Time</label>
          <input
            id="reminder-time"
            className="form-input"
            type="time"
            value={draft.scheduled_time}
            onChange={(e) => set("scheduled_time", e.target.value)}
          />
        </div>
        <div className="form-group">
          <label className="form-label" htmlFor="reminder-recurrence">Repeats</label>
          <select
            id="reminder-recurrence"
            className="form-select"
            value={draft.recurrence}
            onChange={(e) => set("recurrence", e.target.value as Recurrence)}
          >
            {RECURRENCES.map((value) => (
              <option key={value} value={value}>{value}</option>
            ))}
          </select>
        </div>
      </div>
      {draft.recurrence === "weekly" && (
        <div className="form-group" style={{ marginBottom: 12 }}>
          <span className="form-label">Days</span>
          <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
            {WEEK_DAYS.map((day) => {
              const active = draft.days.includes(day.index);
              return (
                <button
                  key={day.index}
                  type="button"
                  className={active ? "btn btn-sm" : "btn btn-secondary btn-sm"}
                  aria-pressed={active}
                  aria-label={day.label}
                  onClick={() => toggleDay(day.index)}
                >
                  {day.short}
                </button>
              );
            })}
          </div>
        </div>
      )}

      <div className="form-group" style={{ marginBottom: 12 }}>
        <label className="form-label" htmlFor="reminder-timezone">Timezone</label>
        <select
          id="reminder-timezone"
          className="form-select"
          value={draft.timezone}
          onChange={(e) => set("timezone", e.target.value)}
        >
          {/* A stored zone the runtime no longer lists is still shown rather than silently replaced. */}
          {zones.includes(draft.timezone) || !draft.timezone
            ? null
            : <option value={draft.timezone}>{draft.timezone}</option>}
          {zones.map((zone) => (
            <option key={zone} value={zone}>{zone}</option>
          ))}
        </select>
        {draft.recurrence === "once" && (
          <span style={{ fontSize: 12, color: "#94a3b8" }}>
            A one-time reminder whose time has already passed today will run again tomorrow.
          </span>
        )}
      </div>

      {error && <div className="form-error" role="alert" style={{ marginBottom: 12 }}>{error}</div>}

      <div style={{ display: "flex", gap: 10, justifyContent: "flex-end", alignItems: "center" }}>
        {editing && !confirmDelete && (
          <button
            className="btn btn-secondary"
            onClick={() => setConfirmDelete(true)}
            disabled={saving || deleting}
            style={{ marginRight: "auto", display: "inline-flex", alignItems: "center", gap: 6 }}
          >
            <Trash2 size={15} /> Delete
          </button>
        )}
        {editing && confirmDelete && (
          <span style={{ marginRight: "auto", display: "flex", alignItems: "center", gap: 8 }}>
            <span style={{ fontSize: 13, color: "#f87171" }}>Delete this reminder?</span>
            <button className="btn btn-sm" onClick={remove} disabled={deleting}>Yes, delete</button>
            <button className="btn btn-secondary btn-sm" onClick={() => setConfirmDelete(false)}>Cancel</button>
          </span>
        )}
        <button className="btn btn-secondary" onClick={onClose} disabled={saving || deleting}>Cancel</button>
        <button className="btn" onClick={save} disabled={saving || deleting}>
          {saving ? "Saving…" : editing ? "Save changes" : "Create reminder"}
        </button>
      </div>
    </Modal>
  );
}
