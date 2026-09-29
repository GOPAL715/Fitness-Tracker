import { useCallback, useEffect, useState } from "react";
import { ArrowLeft, Bell, CalendarClock, Globe, Pencil, Repeat } from "lucide-react";

import {
  getReminder, rescheduleReminder, setReminderEnabled, type Reminder,
} from "../lib/api/reminderApi";
import { ApiError } from "../lib/api/apiClient";

/**
 * One reminder, reached by tapping a push notification.
 *
 * Read-only. Every value shown here is server-owned or user-authored text, and all of it is rendered
 * as React children - never as HTML - so a reminder containing markup is displayed literally instead
 * of being interpreted.
 */

type State =
  | { kind: "loading" }
  | { kind: "ready"; reminder: Reminder }
  | { kind: "missing" }
  | { kind: "unauthenticated" }
  | { kind: "error"; message: string };

function formatInstant(value: string | null | undefined): string {
  if (!value) return "";
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString();
}

function formatSchedule(reminder: Reminder): string {
  const time = reminder.scheduled_time ?? "";
  if (!time) return "No time set";
  const days = (reminder.days_of_week ?? "").split(/[,;\s]+/).filter(Boolean);
  return days.length > 0 ? `${time} on ${days.join(", ")}` : time;
}

type Props = { id: string; onBack: () => void; onEdit?: (reminder: Reminder) => void };

export default function ReminderDetailView({ id, onBack, onEdit }: Props) {
  const [state, setState] = useState<State>({ kind: "loading" });
  const [busy, setBusy] = useState<"toggle" | "reschedule" | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  /** Re-reads after a write, so the screen never shows state the server rejected. */
  const reload = useCallback(async () => {
    try {
      setState({ kind: "ready", reminder: await getReminder(id) });
    } catch (error: unknown) {
      // The server answers 404 for a reminder that does not exist, and for one belonging to
      // somebody else. Both are shown the same way, so this view can never be used to discover
      // that another user's reminder exists.
      if (error instanceof ApiError && error.status === 404) setState({ kind: "missing" });
      else if (error instanceof ApiError && error.status === 401) setState({ kind: "unauthenticated" });
      else setState({ kind: "error", message: "Could not load this reminder. Please try again." });
    }
  }, [id]);

  useEffect(() => {
    let active = true;
    setState({ kind: "loading" });
    setActionError(null);
    getReminder(id)
      .then((reminder) => { if (active) setState({ kind: "ready", reminder }); })
      .catch((error: unknown) => {
        if (!active) return;
        if (error instanceof ApiError && error.status === 404) setState({ kind: "missing" });
        else if (error instanceof ApiError && error.status === 401) setState({ kind: "unauthenticated" });
        else setState({ kind: "error", message: "Could not load this reminder. Please try again." });
      });
    return () => { active = false; };
  }, [id]);

  async function toggleEnabled(reminder: Reminder) {
    setBusy("toggle");
    setActionError(null);
    try {
      await setReminderEnabled(reminder.id, !reminder.enabled);
      await reload();
    } catch {
      setActionError("Could not change whether this reminder is active.");
    } finally {
      setBusy(null);
    }
  }

  async function reschedule() {
    setBusy("reschedule");
    setActionError(null);
    try {
      await rescheduleReminder(id);
      await reload();
    } catch {
      setActionError("Could not recompute the next reminder time.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="card">
      <button
        className="btn btn-secondary"
        onClick={onBack}
        style={{ display: "inline-flex", alignItems: "center", gap: 6, marginBottom: 16 }}
      >
        <ArrowLeft size={15} /> Back to FitTrack
      </button>

      {state.kind === "loading" && <p style={{ color: "#94a3b8" }}>Loading reminder…</p>}

      {state.kind === "missing" && (
        <div role="status">
          <h2 style={{ margin: "0 0 8px", fontSize: 17, color: "#f0f6fc" }}>Reminder not found</h2>
          <p style={{ color: "#94a3b8", margin: 0, lineHeight: 1.55 }}>
            This reminder no longer exists, or it belongs to another account.
          </p>
        </div>
      )}

      {state.kind === "unauthenticated" && (
        <div role="status">
          <h2 style={{ margin: "0 0 8px", fontSize: 17, color: "#f0f6fc" }}>Sign in again</h2>
          <p style={{ color: "#94a3b8", margin: 0, lineHeight: 1.55 }}>
            Your session has expired. Sign in and open this reminder again.
          </p>
        </div>
      )}

      {state.kind === "error" && (
        <p role="alert" style={{ color: "#f87171", margin: 0 }}>{state.message}</p>
      )}

      {state.kind === "ready" && (
        <Detail
          reminder={state.reminder}
          busy={busy}
          actionError={actionError}
          onEdit={onEdit ? () => onEdit(state.reminder) : undefined}
          onToggle={() => void toggleEnabled(state.reminder)}
          onReschedule={() => void reschedule()}
        />
      )}
    </div>
  );
}

function Detail({
  reminder, busy, actionError, onEdit, onToggle, onReschedule,
}: {
  reminder: Reminder;
  busy: string | null;
  actionError: string | null;
  onEdit?: () => void;
  onToggle: () => void;
  onReschedule: () => void;
}) {
  const next = formatInstant(reminder.next_occurrence_at);
  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 4 }}>
        <Bell size={18} color={reminder.enabled ? "#4ade80" : "#94a3b8"} />
        <h2 style={{ margin: 0, fontSize: 17, color: "#f0f6fc" }}>{reminder.title || "Reminder"}</h2>
      </div>
      <p style={{ color: "#94a3b8", margin: "0 0 4px", fontSize: 13 }}>
        {reminder.enabled ? "Active" : "Paused"}
        {reminder.type ? ` · ${reminder.type}` : ""}
      </p>
      {reminder.message && (
        <p style={{ color: "#cbd5e1", margin: "0 0 16px", lineHeight: 1.55 }}>{reminder.message}</p>
      )}

      <dl style={{ display: "grid", gap: 12, margin: 0 }}>
        <Row icon={<CalendarClock size={15} />} label="Schedule" value={formatSchedule(reminder)} />
        <Row icon={<Repeat size={15} />} label="Repeats" value={reminder.recurrence ?? "daily"} />
        <Row icon={<Globe size={15} />} label="Timezone" value={reminder.timezone ?? "UTC"} />
        <Row icon={<CalendarClock size={15} />} label="Next reminder" value={next || "Not scheduled"} />
        <Row icon={<Bell size={15} />} label="Last delivery" value={reminder.delivery_status ?? "pending"} />
      </dl>

      {actionError && (
        <p role="alert" style={{ color: "#f87171", fontSize: 13, margin: "14px 0 0" }}>{actionError}</p>
      )}

      <div style={{ display: "flex", gap: 8, marginTop: 16, flexWrap: "wrap" }}>
        {onEdit && (
          <button className="btn btn-secondary btn-sm" onClick={onEdit} disabled={busy !== null}
            style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
            <Pencil size={14} /> Edit
          </button>
        )}
        <button className="btn btn-secondary btn-sm" onClick={onToggle} disabled={busy !== null}>
          {reminder.enabled ? "Pause reminder" : "Resume reminder"}
        </button>
        <button className="btn btn-secondary btn-sm" onClick={onReschedule} disabled={busy !== null}>
          {busy === "reschedule" ? "Recomputing…" : "Recompute next time"}
        </button>
      </div>
    </div>
  );
}

function Row({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
      <span style={{ color: "#4ade80", display: "inline-flex" }}>{icon}</span>
      <span style={{ color: "#94a3b8", fontSize: 13, minWidth: 120 }}>{label}</span>
      <span style={{ color: "#e2e8f0", fontSize: 13 }}>{value}</span>
    </div>
  );
}
