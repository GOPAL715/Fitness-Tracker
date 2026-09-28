import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ChevronLeft, ChevronRight, Calendar as CalendarIcon, Dumbbell, Utensils, Ruler,
  Trophy, Repeat, HeartPulse, Clock,
} from "lucide-react";
import { getCalendarSummary, type CalendarSummary } from "../lib/api/calendarApi";
import { EmptyState, SectionHeader } from "../components/ui";
import { todayISO, formatLongDate, round, DAY_LABELS_MON } from "../lib/utils";

type Filter = "all" | "workout" | "nutrition" | "body" | "health" | "pr" | "habits";

/**
 * The calendar, over one bounded window of activity.
 *
 * <p>Data comes from a single calendar-summary request for the visible month rather than from the
 * whole account history. The previous version grouped the full app-data payload in the browser, so
 * drawing one month meant downloading every row the user had ever written, and it derived a
 * session's day from a UTC timestamp, which filed an early-morning session under the previous
 * day. The server now groups by the calendar day the client recorded.
 */
export default function CalendarView() {
  const [monthOffset, setMonthOffset] = useState(0);
  const [selectedDate, setSelectedDate] = useState<string>(todayISO());
  const [filter, setFilter] = useState<Filter>("all");
  const [data, setData] = useState<CalendarSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const { year, month, cells, monthLabel } = useMemo(() => {
    const base = new Date();
    base.setDate(1);
    base.setMonth(base.getMonth() + monthOffset);
    const y = base.getFullYear();
    const m = base.getMonth();
    const firstDow = (new Date(y, m, 1).getDay() + 6) % 7;
    const daysInMonth = new Date(y, m + 1, 0).getDate();
    const list: (string | null)[] = [];
    for (let i = 0; i < firstDow; i++) list.push(null);
    for (let d = 1; d <= daysInMonth; d++) {
      list.push(`${y}-${String(m + 1).padStart(2, "0")}-${String(d).padStart(2, "0")}`);
    }
    return {
      year: y,
      month: m,
      cells: list,
      monthLabel: base.toLocaleDateString(undefined, { month: "long", year: "numeric" }),
    };
  }, [monthOffset]);

  // The exact span of real days in the grid, which is the window the summary is requested for.
  const window = useMemo(() => {
    const real = cells.filter((c): c is string => c !== null);
    return { from: real[0], to: real[real.length - 1] };
  }, [cells]);

  const load = useCallback(async () => {
    if (!window.from || !window.to) return;
    setLoading(true);
    setError(null);
    try {
      setData(await getCalendarSummary(window.from, window.to));
    } catch {
      setError("The calendar could not be loaded. Please try again.");
    } finally {
      // finally, so a thrown request can never leave the grid loading for good.
      setLoading(false);
    }
  }, [window.from, window.to]);

  useEffect(() => { load(); }, [load]);

  /** A row without a date cannot be placed on a calendar, so it is never given a date key. */
  function datedOnly<T extends { date: string }>(rows: T[]): T[] {
    return rows.filter((r) => typeof r.date === "string" && /^\d{4}-\d{2}-\d{2}$/.test(r.date));
  }

  /** Per-date activity counts for the grid dots. */
  const dayData = useMemo(() => {
    type Entry = { workout: number; meals: number; habits: number; body: boolean; readiness: number | null; pr: boolean };
    const map = new Map<string, Entry>();
    const entry = (date: string): Entry => {
      let e = map.get(date);
      if (!e) { e = { workout: 0, meals: 0, habits: 0, body: false, readiness: null, pr: false }; map.set(date, e); }
      return e;
    };
    if (!data) return map;
    for (const s of datedOnly(data.sessions)) entry(s.date).workout += 1;
    for (const m of datedOnly(data.meals)) entry(m.date).meals += 1;
    for (const l of datedOnly(data.habitLogs)) entry(l.date).habits += 1;
    for (const b of datedOnly(data.bodyMetrics)) entry(b.date).body = true;
    for (const r of datedOnly(data.personalRecords)) entry(r.date).pr = true;
    for (const d of datedOnly(data.days)) {
      if (d.readiness != null) entry(d.date).readiness = d.readiness;
    }
    return map;
  }, [data]);

  // The day panel, kept under the same shape the panel below already reads.
  const summaryPanel = useMemo(() => {
    const d = selectedDate;
    const daySessions = datedOnly(data?.sessions ?? []).filter((s) => s.date === d) ?? [];
    const dayWorkouts: typeof daySessions = [];
    const dayMeals = datedOnly(data?.meals ?? []).filter((m) => m.date === d) ?? [];
    const dayBody = data?.bodyMetrics.find((b) => b.date === d) ?? null;
    const dayMetric = data?.days.find((m) => m.date === d) ?? null;
    const dayLogs = datedOnly(data?.habitLogs ?? []).filter((l) => l.date === d) ?? [];
    const dayPRs = datedOnly(data?.personalRecords ?? []).filter((r) => r.date === d) ?? [];
    const sessionVolume = daySessions.reduce((sum, s) => sum + (s.duration_minutes ?? 0), 0);
    const mealTotals = dayMeals.reduce(
      (acc, m) => ({ calories: acc.calories + (m.calories ?? 0) }),
      { calories: 0 },
    );
    return { daySessions, dayWorkouts, dayMeals, dayBody, dayMetric, dayLogs, dayPRs, sessionVolume, mealTotals };
  }, [selectedDate, data]);

  const timeline = useMemo(() => {
    type Event = { id: string; date: string; kind: Exclude<Filter, "all">; title: string; detail: string };
    const events: Event[] = [];
    if (!data) return events;
    for (const s of datedOnly(data.sessions)) {
      events.push({ id: `s-${s.id}`, date: s.date, kind: "workout", title: s.title, detail: s.workout_type || "Session" });
    }
    for (const m of datedOnly(data.meals)) {
      events.push({
        id: `m-${m.id}`, date: m.date, kind: "nutrition", title: m.name,
        detail: `${m.meal_type.toLowerCase()}${m.calories != null ? ` · ${round(m.calories)} kcal` : ""}`,
      });
    }
    for (const l of datedOnly(data.habitLogs)) {
      events.push({ id: `h-${l.id}`, date: l.date, kind: "habits", title: l.name, detail: "Completed" });
    }
    for (const r of datedOnly(data.personalRecords)) {
      events.push({ id: `p-${r.id}`, date: r.date, kind: "pr", title: `New record: ${r.exercise}`, detail: `${r.record_value} ${r.unit}` });
    }
    // Every event carries a real date: the server excludes records that have none rather than
    // returning a null that would sort as the newest entry and format as "Invalid Date".
    return events.sort((a, b) => b.date.localeCompare(a.date)).slice(0, 40);
  }, [data]);
  const totalHabits = useMemo(() => new Set(data?.habitLogs.map((l) => l.name) ?? []).size, [data]);
  function show(kind: Exclude<Filter, "all">) {
    return filter === "all" || filter === kind;
  }

  const detail = dayData.get(selectedDate);

  return (
    <div className="flex-col" style={{ animation: "fadeInUp 0.4s ease both" }}>
      <SectionHeader title="Calendar & history" subtitle="Every workout, meal and measurement in one timeline" />
      {error && <div className="form-error" role="alert" style={{ marginBottom: 12 }}><span>{error}</span></div>}
      {loading && <p className="stat-meta" style={{ marginBottom: 12 }}>Loading calendar…</p>}

      <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
        {([
          { id: "all", label: "Everything" },
          { id: "workout", label: "Workouts" },
          { id: "nutrition", label: "Nutrition" },
          { id: "body", label: "Body" },
          { id: "health", label: "Health" },
          { id: "pr", label: "PRs" },
          { id: "habits", label: "Habits" },
        ] as const).map((f) => (
          <button key={f.id} className={`chip ${filter === f.id ? "chip-on" : ""}`} onClick={() => setFilter(f.id)}>
            {f.label}
          </button>
        ))}
      </div>

      <div className="card">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
          <button className="icon-btn" onClick={() => setMonthOffset((v) => v - 1)} aria-label="Previous month">
            <ChevronLeft size={18} color="#94a3b8" />
          </button>
          <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>{monthLabel}</span>
          <button className="icon-btn" onClick={() => setMonthOffset((v) => Math.min(0, v + 1))} aria-label="Next month">
            <ChevronRight size={18} color="#94a3b8" />
          </button>
        </div>

        <div className="cal-grid-head">
          {DAY_LABELS_MON.map((d) => <span key={d}>{d.slice(0, 1)}</span>)}
        </div>

        <div className="cal-grid">
          {cells.map((date, i) => {
            if (!date) return <div key={`empty-${i}`} />;
            const d = dayData.get(date);
            const isToday = date === todayISO();
            const isSelected = date === selectedDate;
            const isFuture = date > todayISO();
            return (
              <button
                key={date}
                className={`cal-cell ${isSelected ? "cal-cell-selected" : ""} ${isToday ? "cal-cell-today" : ""}`}
                onClick={() => setSelectedDate(date)}
                disabled={isFuture}
                aria-label={date}
                aria-pressed={isSelected}
              >
                <span className="cal-day">{Number(date.slice(8))}</span>
                <div className="cal-dots">
                  {d?.workout ? <span className="cal-dot" style={{ background: "#38bdf8" }} /> : null}
                  {d?.meals ? <span className="cal-dot" style={{ background: "#fb923c" }} /> : null}
                  {d?.body ? <span className="cal-dot" style={{ background: "#4ade80" }} /> : null}
                  {d?.pr ? <span className="cal-dot" style={{ background: "#fbbf24" }} /> : null}
                  {d?.habits ? <span className="cal-dot" style={{ background: "#a78bfa" }} /> : null}
                </div>
              </button>
            );
          })}
        </div>

        <div className="cal-legend">
          <Legend color="#38bdf8" label="Workout" />
          <Legend color="#fb923c" label="Nutrition" />
          <Legend color="#4ade80" label="Body" />
          <Legend color="#fbbf24" label="PR" />
          <Legend color="#a78bfa" label="Habits" />
        </div>
      </div>

      {/* Day summary */}
      <div className="card">
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 6 }}>
          <CalendarIcon size={18} color="#38bdf8" />
          <span style={{ fontSize: 16, fontWeight: 700, color: "#f0f6fc" }}>{formatLongDate(selectedDate)}</span>
        </div>
        <p className="stat-meta" style={{ marginBottom: 18, display: "block" }}>Complete summary for this day</p>

        {show("health") && summaryPanel.dayMetric && (
          <DayRow
            icon={<HeartPulse size={16} color="#f87171" />}
            label="Daily health"
            value={`Readiness ${summaryPanel.dayMetric.readiness}/100`}
            detail={`${summaryPanel.dayMetric.sleep_hours ?? 0}h sleep · ${(summaryPanel.dayMetric.steps ?? 0).toLocaleString()} steps · ${summaryPanel.dayMetric.water_oz ?? 0} oz water`}
          />
        )}

        {show("workout") && summaryPanel.daySessions.length > 0 && (
          <DayRow
            icon={<Dumbbell size={16} color="#38bdf8" />}
            label={`${summaryPanel.daySessions.length} detailed session${summaryPanel.daySessions.length === 1 ? "" : "s"}`}
            value={`${summaryPanel.sessionVolume.toLocaleString()} lb volume`}
            detail={summaryPanel.daySessions.map((s) => s.title).join(", ")}
          />
        )}


        {show("nutrition") && summaryPanel.dayMeals.length > 0 && (
          <DayRow
            icon={<Utensils size={16} color="#fb923c" />}
            label={`${summaryPanel.dayMeals.length} meal${summaryPanel.dayMeals.length === 1 ? "" : "s"} logged`}
            value={`${summaryPanel.mealTotals.calories} kcal`}
            detail={`${summaryPanel.dayMeals.map((m) => m.meal_type.toLowerCase()).join(", ")}`}
          />
        )}

        {show("body") && summaryPanel.dayBody && (
          <DayRow
            icon={<Ruler size={16} color="#4ade80" />}
            label="Body measurement"
            value={`${summaryPanel.dayBody.weight_lb} lb`}
            detail={`${summaryPanel.dayBody.body_fat_pct ?? 0}% body fat · waist ${summaryPanel.dayBody.waist_in ?? 0} in`}
          />
        )}

        {show("pr") && summaryPanel.dayPRs.length > 0 && (
          <DayRow
            icon={<Trophy size={16} color="#fbbf24" />}
            label={`${summaryPanel.dayPRs.length} personal record${summaryPanel.dayPRs.length === 1 ? "" : "s"}`}
            value={summaryPanel.dayPRs[0].exercise}
            detail={summaryPanel.dayPRs.map((r) => `${r.exercise} ${r.record_value} ${r.unit}`).join(" · ")}
          />
        )}

        {show("habits") && summaryPanel.dayLogs.length > 0 && (
          <DayRow
            icon={<Repeat size={16} color="#a78bfa" />}
            label={`${summaryPanel.dayLogs.length} habit${summaryPanel.dayLogs.length === 1 ? "" : "s"} completed`}
            value={totalHabits > 0 ? `${Math.round((summaryPanel.dayLogs.length / totalHabits) * 100)}% of habits` : ""}
            detail={summaryPanel.dayLogs.map((l) => l.name).join(", ")}
          />
        )}

        {summaryPanel.daySessions.length === 0 &&
        summaryPanel.daySessions.length === 0 &&
          summaryPanel.dayMeals.length === 0 &&
          !summaryPanel.dayBody &&
          summaryPanel.dayPRs.length === 0 &&
          summaryPanel.dayLogs.length === 0 &&
          !summaryPanel.dayMetric && (
            <EmptyState
              icon={<CalendarIcon size={28} color="#64748b" />}
              title="Nothing recorded this day"
              message="Pick another date, or log a workout or meal to fill in this day."
            />
          )}
      </div>

      {/* History timeline */}
      <div>
        <h3 style={{ fontSize: 16, fontWeight: 700, color: "#cbd5e1", margin: "0 0 12px" }}>Recent history</h3>
        {timeline.filter((e) => show(e.kind)).length === 0 ? (
          <div className="card">
            <EmptyState
              icon={<Clock size={28} color="#64748b" />}
              title="No history yet"
              message="Your logged workouts, meals and measurements will appear here."
            />
          </div>
        ) : (
          <div className="flex-col" style={{ gap: 0 }}>
            {timeline
              .filter((e) => show(e.kind))
              .map((e) => (
                <div key={e.id} className="timeline-row">
                  <div className="timeline-marker" style={{ background: kindColor(e.kind) }} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: "flex", justifyContent: "space-between", gap: 10, flexWrap: "wrap" }}>
                      <span style={{ fontSize: 14, fontWeight: 700, color: "#f0f6fc" }}>{e.title}</span>
                      <span className="stat-meta">{formatDayMonth(e.date)}</span>
                    </div>
                    <span className="stat-meta">{e.detail}</span>
                  </div>
                </div>
              ))}
          </div>
        )}
      </div>
    </div>
  );
}

function Legend({ color, label }: { color: string; label: string }) {
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 5, fontSize: 11, color: "#94a3b8", fontWeight: 600 }}>
      <span className="cal-dot" style={{ background: color }} />
      {label}
    </span>
  );
}

function DayRow({ icon, label, value, detail }: { icon: React.ReactNode; label: string; value: string; detail: string }) {
  return (
    <div className="day-row">
      <div className="stat-icon" style={{ background: "rgba(148,163,184,0.1)", width: 34, height: 34 }}>{icon}</div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14, fontWeight: 700, color: "#f0f6fc" }}>{label}</div>
        <span className="stat-meta">{detail}</span>
      </div>
      <span style={{ fontSize: 13, fontWeight: 700, color: "#38bdf8", whiteSpace: "nowrap" }}>{value}</span>
    </div>
  );
}

function kindColor(kind: Filter): string {
  switch (kind) {
    case "workout": return "#38bdf8";
    case "nutrition": return "#fb923c";
    case "body": return "#4ade80";
    case "pr": return "#fbbf24";
    case "habits": return "#a78bfa";
    default: return "#64748b";
  }
}

function formatDayMonth(date: string): string {
  const d = new Date(date + "T00:00:00");
  return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}


