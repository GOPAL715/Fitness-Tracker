import { useEffect, useState } from "react";
import { ArrowLeft, Bell, CalendarClock, Globe, Repeat } from "lucide-react";

import { getReminder, type Reminder } from "../lib/api/reminderApi";
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

export default function ReminderDetailView({ id, onBack }: { id: string; onBack: () => void }) {
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    let active = true;
    setState({ kind: "loading" });
    getReminder(id)
      .then((reminder) => { if (active) setState({ kind: "ready", reminder }); })
      .catch((error: unknown) => {
        if (!active) return;
        // The server answers 404 for a reminder that does not exist, and for one belonging to
        // somebody else. Both are shown the same way, so this view can never be used to discover
        // that another user's reminder exists.
        if (error instanceof ApiError && error.status === 404) setState({ kind: "missing" });
        else if (error instanceof ApiError && error.status === 401) setState({ kind: "unauthenticated" });
        else setState({ kind: "error", message: "Could not load this reminder. Please try again." });
      });
    return () => { active = false; };
  }, [id]);

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

      {state.kind === "ready" && <Detail reminder={state.reminder} />}
    </div>
  );
}

function Detail({ reminder }: { reminder: Reminder }) {
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
