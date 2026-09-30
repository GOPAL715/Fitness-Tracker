import type { ReactNode } from "react";
import { AlertTriangle, BarChart3, Clock } from "lucide-react";
import { EmptyState } from "../../components/ui";

/**
 * Phase 21: the three states an analytics panel can be in besides "has data".
 *
 * <p>Each is a component rather than a branch inside a view so the same wording appears everywhere a
 * series is shown, and so no view can accidentally render an empty chart instead of saying why it is
 * empty.
 */

/** A request is in flight. Says so, rather than showing a blank panel that looks like no data. */
export function AnalyticsLoading({ label = "Loading your analytics" }: { label?: string }) {
  return (
    <div className="empty-state" role="status" aria-live="polite">
      <div className="empty-icon">
        <Clock size={28} color="#38bdf8" />
      </div>
      <h3 style={{ color: "#cbd5e1", fontSize: 16, fontWeight: 700, margin: "0 0 6px" }}>{label}</h3>
      <p style={{ fontSize: 14, margin: 0, maxWidth: 320, marginLeft: "auto", marginRight: "auto", lineHeight: 1.5 }}>
        One moment.
      </p>
    </div>
  );
}

/** The request failed. Reports it and offers the one action that can help. */
export function AnalyticsError({
  message,
  onRetry,
}: {
  message: string;
  onRetry?: () => void;
}) {
  return (
    <div className="empty-state" role="alert">
      <div className="empty-icon">
        <AlertTriangle size={28} color="#fb923c" />
      </div>
      <h3 style={{ color: "#cbd5e1", fontSize: 16, fontWeight: 700, margin: "0 0 6px" }}>
        Analytics unavailable
      </h3>
      <p style={{ fontSize: 14, margin: "0 0 16px", maxWidth: 340, marginLeft: "auto", marginRight: "auto", lineHeight: 1.5 }}>
        {message}
      </p>
      {onRetry && (
        <button className="btn btn-secondary btn-sm" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}

/** The request succeeded and there is genuinely nothing to show. */
export function AnalyticsEmpty({
  title = "No analytics yet",
  message = "Log activity, meals or sessions and your trends will appear here.",
}: {
  title?: string;
  message?: string;
}) {
  return <EmptyState icon={<BarChart3 size={28} color="#64748b" />} title={title} message={message} />;
}

/**
 * Says that the figures are in UTC because the user has not set a timezone.
 *
 * <p>The server applies a UTC fallback rather than rejecting the request, and reports
 * `timezone_resolved: false`. Presenting that silently would let a user read a UTC day boundary as
 * their own, which for anyone east or west of UTC misfiles part of their day. The notice is factual
 * and does not block the data.
 */
export function TimezoneNotice({ timezone, resolved }: { timezone: string; resolved: boolean }) {
  if (resolved) return null;
  return (
    <span className="stat-meta" data-testid="analytics-timezone-notice">
      Times shown in UTC — set your timezone in Profile for your local day.
    </span>
  );
}

/**
 * A value that may be absent, rendered so absence is visible.
 *
 * <p>`null` and `0` are different facts: zero is a measurement, null is an absence. Passing a plain
 * number and letting a view render it directly is what previously let an unrecorded day display as
 * "0 steps", claiming the user stood still all day.
 */
export function Measure({
  value,
  format,
  absent = "No data",
}: {
  value: number | null | undefined;
  format?: (value: number) => string;
  absent?: string;
}) {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return (
      <span data-testid="measure-absent" style={{ color: "#64748b" }}>
        {absent}
      </span>
    );
  }
  return <span data-testid="measure-value">{format ? format(value) : value}</span>;
}

/** A labelled section wrapper, so every analytics block reads the same way. */
export function AnalyticsPanel({
  title,
  icon,
  children,
}: {
  title: string;
  icon?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="card">
      <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 12 }}>
        {icon}
        <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>{title}</span>
      </div>
      {children}
    </div>
  );
}
