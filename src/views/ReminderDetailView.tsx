import { useCallback, useEffect, useState } from "react";
import { ArrowLeft, Bell, CalendarClock, Globe, Pencil, Repeat } from "lucide-react";

import {
  getReminder, getReminderDeliveryHistory, rescheduleReminder, setReminderEnabled,
  type DeliveryAttempt, type DeliveryHistory, type FailureCategory, type Reminder,
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

/** One page of history at a time, appended as the user asks for more. */
type HistoryState =
  | { kind: "loading" }
  | { kind: "ready"; history: DeliveryHistory }
  | { kind: "error" };

/** How many occurrences one "load more" click adds. */
const HISTORY_PAGE = 20;

/**
 * What each recorded state means, in the user's terms.
 *
 * <p>`pending` is a claim, not a verdict: it means the scheduler reserved the occurrence and has not
 * finished recording an outcome, which is different from a failure and is worded as such.
 */
const STATE_LABELS: Record<string, string> = {
  delivered: "Delivered",
  failed: "Not delivered",
  exhausted: "Not delivered after several tries",
  pending: "Not yet finished",
};

/**
 * A plain sentence for each category, phrased as something the user can act on.
 *
 * <p>No provider text, status code or error string is ever shown - the server sends only these names,
 * so there is nothing raw to leak into the DOM. `UNKNOWN` deliberately does not speculate about a
 * cause it was not told.
 */
const CATEGORY_MESSAGES: Record<FailureCategory, string> = {
  NO_SUBSCRIPTION: "Notifications are turned off for this account. Turn them on to receive reminders.",
  INVALID_SUBSCRIPTION: "This device's subscription has expired. Turn notifications off and on again to re-register it.",
  RATE_LIMITED: "The notification service was busy, so this was throttled. The next one is unaffected.",
  TEMPORARY_PROVIDER_ERROR: "The notification service could not be reached. This usually clears on its own.",
  PROVIDER_REJECTED: "The notification service declined to send this.",
  UNKNOWN: "The reason was not recorded for this attempt.",
};

function describeAttempt(attempt: DeliveryAttempt): string {
  const state = STATE_LABELS[attempt.state] ?? "Status not recognised";
  if (attempt.state === "delivered") return state;
  return `${state} - ${CATEGORY_MESSAGES[attempt.failureCategory] ?? CATEGORY_MESSAGES.UNKNOWN}`;
}

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
  const [history, setHistory] = useState<HistoryState>({ kind: "loading" });
  const [busy, setBusy] = useState<"toggle" | "reschedule" | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  /**
   * Loads one page of history, appending when the user asks for more.
   *
   * <p>Loaded separately from the reminder and never blocking it: history is supporting detail, so a
   * failure here shows a quiet inline note and leaves the reminder fully usable. Occurrences already
   * shown are never refetched, so paging cannot reorder or duplicate a row the user has read.
   */
  const loadHistory = useCallback(async (offset: number) => {
    try {
      const page = await getReminderDeliveryHistory(id, HISTORY_PAGE, offset);
      setHistory((current) => offset === 0
        ? { kind: "ready", history: page }
        : current.kind === "ready"
          ? { kind: "ready", history: { ...page, attempts: [...current.history.attempts, ...page.attempts] } }
          : { kind: "ready", history: page });
    } catch {
      setHistory((current) => (current.kind === "ready" ? current : { kind: "error" }));
    }
  }, [id]);

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
    setHistory({ kind: "loading" });
    // Started alongside the reminder, but independently: a slow or failing history request must not
    // hold up the reminder itself.
    void getReminderDeliveryHistory(id, HISTORY_PAGE, 0)
      .then((page) => { if (active) setHistory({ kind: "ready", history: page }); })
      .catch(() => { if (active) setHistory({ kind: "error" }); });
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
          history={history}
          busy={busy}
          actionError={actionError}
          onEdit={onEdit ? () => onEdit(state.reminder) : undefined}
          onToggle={() => void toggleEnabled(state.reminder)}
          onReschedule={() => void reschedule()}
          onLoadMore={() => void loadHistory(history.kind === "ready" ? history.history.attempts.length : 0)}
        />
      )}
    </div>
  );
}

function Detail({
  reminder, history, busy, actionError, onEdit, onToggle, onReschedule, onLoadMore,
}: {
  reminder: Reminder;
  history: HistoryState;
  busy: string | null;
  actionError: string | null;
  onEdit?: () => void;
  onToggle: () => void;
  onReschedule: () => void;
  onLoadMore: () => void;
}) {
  const next = formatInstant(reminder.next_occurrence_at);
  // The summary row is a single current value, so it is worded as a state rather than a cause. The
  // per-occurrence reasons live in the history below, where each one has its own occurrence to
  // belong to - this row cannot tell which occurrence it is describing.
  const lastDelivery = reminder.delivery_status
    ? (STATE_LABELS[reminder.delivery_status] ?? reminder.delivery_status)
    : "No delivery attempted yet";
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
        <Row icon={<Bell size={15} />} label="Last delivery" value={lastDelivery} />
      </dl>

      <DeliveryHistoryPanel history={history} onLoadMore={onLoadMore} />

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

/**
 * Every recorded occurrence for this reminder, newest first.
 *
 * <p>Everything here is server-owned text rendered as React children, so a stored value is displayed
 * literally and never interpreted as markup. The panel is self-contained: its own loading and error
 * states mean a history problem can never take the reminder above it down with it.
 */
function DeliveryHistoryPanel({ history, onLoadMore }: {
  history: HistoryState;
  onLoadMore: () => void;
}) {
  return (
    <section aria-labelledby="delivery-history-heading" style={{ marginTop: 22 }}>
      <h3 id="delivery-history-heading" style={{ margin: "0 0 8px", fontSize: 14, color: "#f0f6fc" }}>
        Delivery history
      </h3>

      {history.kind === "loading" && (
        <p style={{ color: "#94a3b8", fontSize: 13, margin: 0 }}>Loading delivery history…</p>
      )}

      {history.kind === "error" && (
        <p role="status" style={{ color: "#94a3b8", fontSize: 13, margin: 0 }}>
          Delivery history could not be loaded.
        </p>
      )}

      {history.kind === "ready" && history.history.attempts.length === 0 && (
        <p style={{ color: "#94a3b8", fontSize: 13, margin: 0 }}>
          This reminder has not been due yet, so there is nothing to show.
        </p>
      )}

      {history.kind === "ready" && history.history.attempts.length > 0 && (
        <>
          <ul style={{ listStyle: "none", margin: 0, padding: 0, display: "grid", gap: 8 }}>
            {history.history.attempts.map((attempt) => (
              <li
                key={`${attempt.occurrenceAt}-${attempt.state}`}
                style={{
                  borderLeft: `2px solid ${attempt.state === "delivered" ? "#4ade80" : "#f87171"}`,
                  paddingLeft: 10,
                }}
              >
                <div style={{ color: "#e2e8f0", fontSize: 13 }}>
                  {formatInstant(attempt.occurrenceAt) || "Unknown time"}
                </div>
                <div style={{ color: "#94a3b8", fontSize: 12 }}>
                  {describeAttempt(attempt)}
                  {attempt.attempts > 1 ? ` (${attempt.attempts} tries)` : ""}
                </div>
              </li>
            ))}
          </ul>

          {history.history.hasMore && (
            <button
              className="btn btn-secondary btn-sm"
              onClick={onLoadMore}
              style={{ marginTop: 10 }}
            >
              Load more
            </button>
          )}
        </>
      )}
    </section>
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
