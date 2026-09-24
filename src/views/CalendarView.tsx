import { useMemo, useState } from "react";
import {
  ChevronLeft, ChevronRight, Calendar as CalendarIcon, Dumbbell, Utensils, Ruler,
  Trophy, Repeat, HeartPulse, Clock,
} from "lucide-react";
import type { DailyMetric, Workout, Meal, BodyMetric, PersonalRecord } from "../lib/domain";
import type { Habit, HabitLog } from "../lib/types";
import type { SessionWithDetail } from "../lib/workoutMetrics";
import { computeSessionMetrics } from "../lib/workoutMetrics";
import { EmptyState, SectionHeader } from "../components/ui";
import { todayISO, formatLongDate, round, DAY_LABELS_MON } from "../lib/utils";

type Filter = "all" | "workout" | "nutrition" | "body" | "health" | "pr" | "habits";

type Props = {
  metrics: DailyMetric[];
  workouts: Workout[];
  sessions: SessionWithDetail[];
  meals: Meal[];
  body: BodyMetric[];
  records: PersonalRecord[];
  habits: Habit[];
  habitLogs: HabitLog[];
};

export default function CalendarView({
  metrics, workouts, sessions, meals, body, records, habits, habitLogs,
}: Props) {
  const [monthOffset, setMonthOffset] = useState(0);
  const [selectedDate, setSelectedDate] = useState<string>(todayISO());
  const [filter, setFilter] = useState<Filter>("all");

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

  const dayData = useMemo(() => {
    const map = new Map<string, { workout: number; meals: number; habits: number; body: boolean; readiness: number | null; pr: boolean }>();
    for (const m of metrics) {
      const e = map.get(m.metric_date) ?? { workout: 0, meals: 0, habits: 0, body: false, readiness: null, pr: false };
      e.readiness = m.readiness;
      map.set(m.metric_date, e);
    }
    for (const w of workouts) {
      const e = map.get(w.workout_date) ?? { workout: 0, meals: 0, habits: 0, body: false, readiness: null, pr: false };
      if (w.completed) e.workout += 1;
      map.set(w.workout_date, e);
    }
    for (const s of sessions) {
      if (!s.completed) continue;
      const d = s.started_at.slice(0, 10);
      const e = map.get(d) ?? { workout: 0, meals: 0, habits: 0, body: false, readiness: null, pr: false };
      e.workout += 1;
      map.set(d, e);
    }
    for (const m of meals) {
      const e = map.get(m.meal_date) ?? { workout: 0, meals: 0, habits: 0, body: false, readiness: null, pr: false };
      e.meals += 1;
      map.set(m.meal_date, e);
    }
    for (const b of body) {
      const e = map.get(b.metric_date) ?? { workout: 0, meals: 0, habits: 0, body: false, readiness: null, pr: false };
      e.body = true;
      map.set(b.metric_date, e);
    }
    for (const l of habitLogs) {
      if (!l.completed) continue;
      const e = map.get(l.log_date) ?? { workout: 0, meals: 0, habits: 0, body: false, readiness: null, pr: false };
      e.habits += 1;
      map.set(l.log_date, e);
    }
    for (const p of records) {
      const e = map.get(p.achieved_date) ?? { workout: 0, meals: 0, habits: 0, body: false, readiness: null, pr: false };
      e.pr = true;
      map.set(p.achieved_date, e);
    }
    return map;
  }, [metrics, workouts, sessions, meals, body, habitLogs, records]);

  const summary = useMemo(() => {
    const d = selectedDate;
    const daySessions = sessions.filter((s) => s.started_at.slice(0, 10) === d && s.completed);
    const dayWorkouts = workouts.filter((w) => w.workout_date === d);
    const dayMeals = meals.filter((m) => m.meal_date === d);
    const dayBody = body.find((b) => b.metric_date === d) ?? null;
    const dayMetric = metrics.find((m) => m.metric_date === d) ?? null;
    const dayLogs = habitLogs.filter((l) => l.log_date === d && l.completed);
    const dayPRs = records.filter((r) => r.achieved_date === d);

    const sessionVolume = daySessions.reduce((sum, s) => sum + computeSessionMetrics(s).totalVolume, 0);
    const mealTotals = dayMeals.reduce(
      (acc, m) => ({
        calories: acc.calories + m.calories,
        protein: acc.protein + m.protein_g,
        carbs: acc.carbs + m.carbs_g,
        fat: acc.fat + m.fat_g,
      }),
      { calories: 0, protein: 0, carbs: 0, fat: 0 }
    );

    return { daySessions, dayWorkouts, dayMeals, dayBody, dayMetric, dayLogs, dayPRs, sessionVolume, mealTotals };
  }, [selectedDate, sessions, workouts, meals, body, metrics, habitLogs, records]);

  const show = (key: Exclude<Filter, "all">) => filter === "all" || filter === key;

  const timeline = useMemo(() => {
    type Event = { id: string; date: string; kind: Exclude<Filter, "all">; title: string; detail: string };
    const events: Event[] = [];
    for (const s of sessions) {
      if (!s.completed) continue;
      const m = computeSessionMetrics(s);
      events.push({
        id: `s-${s.id}`, date: s.started_at.slice(0, 10), kind: "workout",
        title: s.title, detail: `${m.totalSets} sets · ${m.totalVolume.toLocaleString()} lb volume`,
      });
    }
    for (const w of workouts) {
      events.push({
        id: `w-${w.id}`, date: w.workout_date, kind: "workout",
        title: w.title, detail: `${w.duration_minutes} min · ${w.calories_burned} cal`,
      });
    }
    for (const m of meals) {
      events.push({
        id: `m-${m.id}`, date: m.meal_date, kind: "nutrition",
        title: `${m.meal_type}: ${m.name}`, detail: `${m.calories} kcal · ${m.protein_g}g protein`,
      });
    }
    for (const b of body) {
      events.push({
        id: `b-${b.id}`, date: b.metric_date, kind: "body",
        title: `Body check-in · ${b.weight_lb} lb`, detail: `${b.body_fat_pct}% body fat · ${b.waist_in} in waist`,
      });
    }
    for (const r of records) {
      events.push({
        id: `p-${r.id}`, date: r.achieved_date, kind: "pr",
        title: `New record: ${r.exercise}`, detail: `${r.record_value} ${r.unit}`,
      });
    }
    return events.sort((a, b) => b.date.localeCompare(a.date)).slice(0, 40);
  }, [sessions, workouts, meals, body, records]);

  return (
    <div className="flex-col" style={{ animation: "fadeInUp 0.4s ease both" }}>
      <SectionHeader title="Calendar & history" subtitle="Every workout, meal and measurement in one timeline" />

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

        {show("health") && summary.dayMetric && (
          <DayRow
            icon={<HeartPulse size={16} color="#f87171" />}
            label="Daily health"
            value={`Readiness ${summary.dayMetric.readiness}/100`}
            detail={`${summary.dayMetric.sleep_hours}h sleep · ${summary.dayMetric.steps.toLocaleString()} steps · ${summary.dayMetric.resting_heart_rate} bpm resting · HRV ${summary.dayMetric.hrv}ms`}
          />
        )}

        {show("workout") && summary.daySessions.length > 0 && (
          <DayRow
            icon={<Dumbbell size={16} color="#38bdf8" />}
            label={`${summary.daySessions.length} detailed session${summary.daySessions.length === 1 ? "" : "s"}`}
            value={`${summary.sessionVolume.toLocaleString()} lb volume`}
            detail={summary.daySessions.map((s) => s.title).join(", ")}
          />
        )}

        {show("workout") && summary.dayWorkouts.length > 0 && (
          <DayRow
            icon={<Clock size={16} color="#94a3b8" />}
            label={`${summary.dayWorkouts.length} quick-logged workout${summary.dayWorkouts.length === 1 ? "" : "s"}`}
            value={`${summary.dayWorkouts.reduce((s, w) => s + w.duration_minutes, 0)} min`}
            detail={summary.dayWorkouts.map((w) => w.title).join(", ")}
          />
        )}

        {show("nutrition") && summary.dayMeals.length > 0 && (
          <DayRow
            icon={<Utensils size={16} color="#fb923c" />}
            label={`${summary.dayMeals.length} meal${summary.dayMeals.length === 1 ? "" : "s"} logged`}
            value={`${summary.mealTotals.calories} kcal`}
            detail={`${summary.mealTotals.protein}g protein · ${summary.mealTotals.carbs}g carbs · ${summary.mealTotals.fat}g fat`}
          />
        )}

        {show("body") && summary.dayBody && (
          <DayRow
            icon={<Ruler size={16} color="#4ade80" />}
            label="Body measurement"
            value={`${summary.dayBody.weight_lb} lb`}
            detail={`${summary.dayBody.body_fat_pct}% body fat · waist ${summary.dayBody.waist_in} in · chest ${summary.dayBody.chest_in} in`}
          />
        )}

        {show("pr") && summary.dayPRs.length > 0 && (
          <DayRow
            icon={<Trophy size={16} color="#fbbf24" />}
            label={`${summary.dayPRs.length} personal record${summary.dayPRs.length === 1 ? "" : "s"}`}
            value={summary.dayPRs[0].exercise}
            detail={summary.dayPRs.map((r) => `${r.exercise} ${r.record_value} ${r.unit}`).join(" · ")}
          />
        )}

        {show("habits") && summary.dayLogs.length > 0 && (
          <DayRow
            icon={<Repeat size={16} color="#a78bfa" />}
            label={`${summary.dayLogs.length} habit${summary.dayLogs.length === 1 ? "" : "s"} completed`}
            value={habits.length ? `${Math.round((summary.dayLogs.length / habits.length) * 100)}% of habits` : ""}
            detail={summary.dayLogs
              .map((l) => habits.find((h) => h.id === l.habit_id)?.name)
              .filter(Boolean)
              .join(", ")}
          />
        )}

        {summary.daySessions.length === 0 &&
          summary.dayWorkouts.length === 0 &&
          summary.dayMeals.length === 0 &&
          !summary.dayBody &&
          summary.dayPRs.length === 0 &&
          summary.dayLogs.length === 0 &&
          !summary.dayMetric && (
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


