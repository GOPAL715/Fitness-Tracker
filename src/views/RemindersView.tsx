import { useCallback, useEffect, useState } from "react";
import { Bell, BellOff, Plus } from "lucide-react";

import { EmptyState, SectionHeader } from "../components/ui";
import { listReminders, setReminderEnabled, type Reminder } from "../lib/api/reminderApi";
import ReminderFormModal, { WEEK_DAYS } from "./ReminderFormModal";

/**
 * The Reminders tab: every reminder the signed-in user owns.
 *
 * <p>Delivery state is shown as what the server recorded it to be. It is deliberately not presented as
 * "notifications on": whether anything is actually delivered also depends on the server's push
 * configuration and on browser permission, neither of which this screen can observe. The push controls
 * live in Profile, where that state is already shown.
 */

type State =
  | { kind: "loading" }
  | { kind: "ready"; reminders: Reminder[] }
  | { kind: "error"; message: string };

function formatWhen(value: string | null | undefined): string {
  if (!value) return "Not scheduled yet";
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString();
}

function formatDays(stored: string | null | undefined, recurrence: string | null | undefined): string {
  if (recurrence === "daily") return "Every day";
  if (recurrence !== "weekly") return "Once";
  const indices = (stored ?? "")
    .split(/[,;\s]+/)
    .map((t) => Number(t.trim()))
    .filter((n) => Number.isInteger(n) && n >= 0 && n <= 6);
  if (indices.length === 0) return "No days selected";
  if (indices.length === 7) return "Every day";
  return indices
    .map((i) => WEEK_DAYS.find((d) => d.index === i)?.label)
    .filter(Boolean)
    .join(", ");
}

type Props = {
  /** Opens a reminder through the shared path layer, the same route a push tap uses. */
  onOpen: (id: string) => void;
};

export default function RemindersView({ onOpen }: Props) {
  const [state, setState] = useState<State>({ kind: "loading" });
  const [editing, setEditing] = useState<Reminder | null | undefined>(undefined);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setState({ kind: "ready", reminders: await listReminders() });
    } catch {
      setState({ kind: "error", message: "Could not load your reminders. Please try again." });
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  async function toggle(reminder: Reminder) {
    setBusyId(reminder.id);
    try {
      await setReminderEnabled(reminder.id, !reminder.enabled);
      await load();
    } catch {
      // Leave the list as it was: the row keeps its previous state rather than flipping optimistically.
    } finally {
      setBusyId(null);
    }
  }

  if (state.kind === "loading") {
    return (
      <div className="card">
        <p style={{ color: "#94a3b8", margin: 0 }}>Loading reminders…</p>
      </div>
    );
  }

  if (state.kind === "error") {
    return (
      <div className="card">
        <p role="alert" style={{ color: "#f87171", margin: 0 }}>{state.message}</p>
        <button className="btn btn-secondary" onClick={() => void load()} style={{ marginTop: 12 }}>
          Try again
        </button>
      </div>
    );
  }

  const reminders = state.reminders;

  return (
    <div className="flex-col">
      <SectionHeader title="Reminders" subtitle="Scheduled nudges, and how they were last delivered" />

      <div style={{ display: "flex", justifyContent: "flex-end" }}>
        <button
          className="btn"
          onClick={() => setEditing(null)}
          style={{ display: "inline-flex", alignItems: "center", gap: 6 }}
        >
          <Plus size={15} /> New reminder
        </button>
      </div>

      {reminders.length === 0 ? (
        <div className="card">
          <EmptyState
            icon={<Bell size={22} />}
            title="No reminders yet"
            message="Create one and AI FitTrack will nudge you at the time you choose."
          />
        </div>
      ) : (
        reminders.map((reminder) => (
          <div className="card" key={reminder.id}>
            <div style={{ display: "flex", alignItems: "flex-start", gap: 10 }}>
              <span style={{ color: reminder.enabled ? "#4ade80" : "#64748b", marginTop: 2 }}>
                {reminder.enabled ? <Bell size={18} /> : <BellOff size={18} />}
              </span>
              <div style={{ flex: 1, minWidth: 0 }}>
                {/* Rendered as text, so a title containing markup is shown literally. */}
                <button
                  onClick={() => onOpen(reminder.id)}
                  style={{
                    background: "none", border: "none", padding: 0, cursor: "pointer",
                    color: "#f0f6fc", fontSize: 15, fontWeight: 700, textAlign: "left",
                  }}
                >
                  {reminder.title || "Untitled reminder"}
                </button>
                {reminder.message && (
                  <p style={{ margin: "4px 0 0", color: "#94a3b8", fontSize: 13 }}>{reminder.message}</p>
                )}
                <p style={{ margin: "8px 0 0", color: "#94a3b8", fontSize: 13 }}>
                  {reminder.enabled ? "Active" : "Paused"} · {formatDays(reminder.days_of_week, reminder.recurrence)}
                  {" · "}
                  {(reminder.scheduled_time ?? "").slice(0, 5)} {reminder.timezone ?? "UTC"}
                </p>
                <p style={{ margin: "4px 0 0", color: "#64748b", fontSize: 12 }}>
                  Next: {formatWhen(reminder.next_occurrence_at)}
                  {" · Last delivery: "}
                  {reminder.delivery_status ?? "pending"}
                </p>
              </div>
              <div style={{ display: "flex", gap: 8, flexShrink: 0 }}>
                <button
                  className="btn btn-secondary btn-sm"
                  onClick={() => setEditing(reminder)}
                  disabled={busyId === reminder.id}
                >
                  Edit
                </button>
                <button
                  className="btn btn-secondary btn-sm"
                  onClick={() => void toggle(reminder)}
                  disabled={busyId === reminder.id}
                >
                  {reminder.enabled ? "Pause" : "Resume"}
                </button>
              </div>
            </div>
          </div>
        ))
      )}

      {editing !== undefined && (
        <ReminderFormModal
          reminder={editing}
          onClose={() => setEditing(undefined)}
          onSaved={() => { setEditing(undefined); void load(); }}
          onDeleted={() => { setEditing(undefined); void load(); }}
        />
      )}
    </div>
  );
}