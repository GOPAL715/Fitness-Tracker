import { useState } from "react";
import {
  TrendingUp,
  Trophy,
  Ruler,
  Plus,
  ArrowUpRight,
  Activity,
  Moon,
  Footprints,
  HeartPulse,
  Sparkles,
  Scale,
  Target,
} from "lucide-react";
import { apiData, type DailyMetric, type Workout, type BodyMetric, type PersonalRecord } from "../lib/api/dataAdapter";
import type { Goal } from "../lib/types";
import { BarChart, EmptyState, Modal, SectionHeader, ProgressRing } from "../components/ui";
import { formatDate, round } from "../lib/utils";
import {
  weeklyVolumeSeries, muscleDistribution, underTrainedMuscles, rollingAverage,
  type SessionWithDetail,
} from "../lib/workoutMetrics";
import { computeGoalProgress } from "./GoalsView";

type Props = {
  metrics: DailyMetric[];
  workouts: Workout[];
  records: PersonalRecord[];
  body: BodyMetric[];
  sessions: SessionWithDetail[];
  goals: Goal[];
  onRefresh: () => void;
};

type ChartKind = "readiness" | "sleep" | "steps" | "hrv";

export default function ProgressView({ metrics, workouts, records, body, sessions, goals, onRefresh }: Props) {
  const [chartKind, setChartKind] = useState<ChartKind>("readiness");
  const [openBody, setOpenBody] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState({
    weight_lb: 180,
    body_fat_pct: 18,
    waist_in: 34,
    chest_in: 41,
    arm_in: 14,
    thigh_in: 23,
  });

  const chrono = [...metrics].sort((a, b) => a.metric_date.localeCompare(b.metric_date));
  const last14 = chrono.slice(-14);

  const chartData = last14.map((m) => {
    const label = formatDate(m.metric_date).split(" ")[1] ?? "";
    switch (chartKind) {
      case "sleep":
        return { label, value: Math.round(m.sleep_hours * 10) / 10 };
      case "steps":
        return { label, value: Math.round(m.steps / 100) };
      case "hrv":
        return { label, value: m.hrv };
      default:
        return { label, value: m.readiness };
    }
  });

  const chartUnit = chartKind === "steps" ? "00" : chartKind === "sleep" ? "h" : "";
  const chartColor = chartKind === "sleep" ? "#a78bfa" : chartKind === "steps" ? "#4ade80" : chartKind === "hrv" ? "#22d3ee" : "#38bdf8";

  const latest = chrono[chrono.length - 1];
  const first = chrono[0];
  const weekWorkouts = workouts.filter((w) => w.completed);
  const avgEffort = weekWorkouts.length
    ? round(weekWorkouts.reduce((s, w) => s + w.perceived_effort, 0) / weekWorkouts.length, 1)
    : 0;
  const totalDistance = workouts.reduce((s, w) => s + (w.distance_miles ?? 0), 0);

  const bodyChrono = [...body].sort((a, b) => a.metric_date.localeCompare(b.metric_date));
  const latestBody = bodyChrono[bodyChrono.length - 1];
  const firstBody = bodyChrono[0];
  const weightChange = latestBody && firstBody ? round(latestBody.weight_lb - firstBody.weight_lb, 1) : 0;

  async function saveBody() {
    setSaving(true);
    setError(null);
    const { error: insertError } = await apiData.from("body_metrics").upsert(
      {
        metric_date: new Date().toISOString().split("T")[0],
        weight_lb: Number(form.weight_lb),
        body_fat_pct: Number(form.body_fat_pct),
        waist_in: Number(form.waist_in),
        chest_in: Number(form.chest_in),
        arm_in: Number(form.arm_in),
        thigh_in: Number(form.thigh_in),
      },
      { onConflict: "metric_date" }
    );
    setSaving(false);
    if (insertError) {
      setError("Those measurements could not be saved. Please try again.");
      return;
    }
    setOpenBody(false);
    onRefresh();
  }

  const monthlyReview = buildMonthlyReview(metrics, workouts, records, weightChange);
  const volumeSeries = weeklyVolumeSeries(sessions);
  const distribution = muscleDistribution(sessions);
  const lagging = underTrainedMuscles(distribution);
  const activeGoals = goals.filter((g) => g.status === "active");
  const readinessValues = chrono.map((m) => m.readiness);
  const readinessAvg = readinessValues.length ? rollingAverage(readinessValues)[readinessValues.length - 1] : 0;

  return (
    <div className="flex-col" style={{ animation: "fadeInUp 0.4s ease both" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: 12 }}>
        <SectionHeader title="Progress" subtitle="Trends, personal records, and body measurements over time" />
        <button className="btn btn-secondary" onClick={() => setOpenBody(true)}>
          <Plus size={16} /> Add measurement
        </button>
      </div>

      {/* Momentum summary */}
      <div className="grid-4">
        <div className="card">
          <div className="stat-tile-top" style={{ marginBottom: 8 }}>
            <span className="stat-label">Readiness today</span>
            <div className="stat-icon" style={{ background: "rgba(56,189,248,0.14)", color: "#38bdf8" }}><Activity size={18} /></div>
          </div>
          <div className="stat-value">{latest?.readiness ?? 0}</div>
          <span className="stat-meta">{first ? `Started at ${first.readiness}` : "No baseline yet"}</span>
        </div>
        <div className="card">
          <div className="stat-tile-top" style={{ marginBottom: 8 }}>
            <span className="stat-label">Avg sleep</span>
            <div className="stat-icon" style={{ background: "rgba(167,139,250,0.14)", color: "#a78bfa" }}><Moon size={18} /></div>
          </div>
          <div className="stat-value">
            {chrono.length ? round(chrono.reduce((s, m) => s + m.sleep_hours, 0) / chrono.length, 1) : 0}
          </div>
          <span className="stat-meta">hours per night</span>
        </div>
        <div className="card">
          <div className="stat-tile-top" style={{ marginBottom: 8 }}>
            <span className="stat-label">Avg steps</span>
            <div className="stat-icon" style={{ background: "rgba(74,222,128,0.14)", color: "#4ade80" }}><Footprints size={18} /></div>
          </div>
          <div className="stat-value">
            {chrono.length ? Math.round(chrono.reduce((s, m) => s + m.steps, 0) / chrono.length).toLocaleString() : 0}
          </div>
          <span className="stat-meta">per day</span>
        </div>
        <div className="card">
          <div className="stat-tile-top" style={{ marginBottom: 8 }}>
            <span className="stat-label">Avg effort</span>
            <div className="stat-icon" style={{ background: "rgba(251,146,60,0.14)", color: "#fb923c" }}><HeartPulse size={18} /></div>
          </div>
          <div className="stat-value">{avgEffort}</div>
          <span className="stat-meta">RPE across sessions</span>
        </div>
      </div>

      {/* Chart */}
      <div className="card">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 12, marginBottom: 8 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <TrendingUp size={18} color="#38bdf8" />
            <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>14 day trend</span>
          </div>
          <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
            {([
              { id: "readiness", label: "Readiness" },
              { id: "sleep", label: "Sleep" },
              { id: "steps", label: "Steps" },
              { id: "hrv", label: "HRV" },
            ] as const).map((c) => (
              <button
                key={c.id}
                className={`nav-btn ${chartKind === c.id ? "nav-btn-active" : ""}`}
                style={{ padding: "6px 12px", fontSize: 13 }}
                onClick={() => setChartKind(c.id)}
              >
                {c.label}
              </button>
            ))}
          </div>
        </div>
        {chartData.length === 0 ? (
          <EmptyState
            icon={<TrendingUp size={28} color="#64748b" />}
            title="No trend data yet"
            message="Once you have a few days of data, your trend line will appear here."
          />
        ) : (
          <>
            <BarChart data={chartData} color={chartColor} unit={chartUnit} />
            <p style={{ fontSize: 12, color: "#64748b", margin: "8px 0 0" }}>
              {chartKind === "readiness" && "Daily readiness blends sleep, heart rate variability, resting heart rate, and stress."}
              {chartKind === "sleep" && "Nightly sleep duration in hours. Deep and consistent sleep drives adaptation."}
              {chartKind === "steps" && "Daily step count shown in hundreds. Movement outside training still counts."}
              {chartKind === "hrv" && "Heart rate variability in milliseconds. Higher generally means better recovery."}
            </p>
          </>
        )}
      </div>

      {/* Training volume */}
      <div className="card">
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 4 }}>
          <TrendingUp size={18} color="#4ade80" />
          <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>Weekly training volume</span>
        </div>
        <p style={{ fontSize: 12, color: "#64748b", margin: "0 0 4px" }}>
          Total weight lifted each week, from weight multiplied by reps across every logged set.
        </p>
        {volumeSeries.every((v) => v.value === 0) ? (
          <EmptyState
            icon={<TrendingUp size={28} color="#64748b" />}
            title="No volume recorded yet"
            message="Log a workout with sets and weights to see your weekly volume build up."
          />
        ) : (
          <BarChart data={volumeSeries} color="#4ade80" unit=" lb" />
        )}
      </div>

      {/* Muscle distribution */}
      {distribution.length > 0 && (
        <div className="card">
          <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 14 }}>
            <Activity size={18} color="#38bdf8" />
            <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>Muscle group balance</span>
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
            {distribution.map((d) => (
              <div key={d.muscle}>
                <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 5 }}>
                  <span style={{ fontSize: 13, color: "#cbd5e1", fontWeight: 600 }}>{d.muscle}</span>
                  <span className="stat-meta">{d.sets} sets · {d.share}%</span>
                </div>
                <div className="stat-bar">
                  <div className="stat-bar-fill" style={{ width: `${d.share}%`, background: d.share > 30 ? "#fb923c" : "#38bdf8" }} />
                </div>
              </div>
            ))}
          </div>
          {lagging.length > 0 && (
            <p className="estimate-note" style={{ marginTop: 14 }}>
              Relatively under-trained based on your logged sets: {lagging.join(", ")}. Adding a few sets there would
              balance your training.
            </p>
          )}
        </div>
      )}

      {/* Goal progress on the analytics screen */}
      {activeGoals.length > 0 && (
        <div className="card">
          <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 14 }}>
            <Target size={18} color="#38bdf8" />
            <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>Goal progress</span>
          </div>
          <div className="flex-col" style={{ gap: 14 }}>
            {activeGoals.map((g) => {
              const p = computeGoalProgress(g);
              return (
                <div key={g.id}>
                  <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 5, gap: 10 }}>
                    <span style={{ fontSize: 13, color: "#cbd5e1", fontWeight: 600 }}>{g.title}</span>
                    <span className="stat-meta">{g.current_value} / {g.target_value} {g.unit} · {p.pct}%</span>
                  </div>
                  <div className="stat-bar">
                    <div className="stat-bar-fill" style={{ width: `${p.pct}%`, background: p.pct >= 100 ? "#4ade80" : "#38bdf8" }} />
                  </div>
                </div>
              );
            })}
          </div>
          <p className="estimate-note" style={{ marginTop: 12 }}>
            Readiness is currently averaging {readinessAvg} over the recent window. Estimated goal dates are projections
            from your own logged pace, not guarantees.
          </p>
        </div>
      )}

      {/* Monthly review */}
      <div className="card" style={{ background: "linear-gradient(135deg, rgba(14,165,233,0.08), rgba(15,23,42,0.4))" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 16 }}>
          <Sparkles size={18} color="#38bdf8" />
          <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>FitTrack AI monthly review</span>
        </div>
        <div className="grid-2">
          <div className="flex-col" style={{ gap: 12 }}>
            {monthlyReview.map((r) => (
              <div key={r.label} style={{ display: "flex", gap: 12 }}>
                <div style={{ width: 4, borderRadius: 2, background: r.color, flexShrink: 0 }} />
                <div>
                  <div style={{ fontSize: 14, fontWeight: 700, color: "#f0f6fc", marginBottom: 2 }}>{r.label}</div>
                  <p style={{ fontSize: 13, color: "#94a3b8", margin: 0, lineHeight: 1.55 }}>{r.detail}</p>
                </div>
              </div>
            ))}
          </div>
          <div style={{ display: "flex", alignItems: "center", justifyContent: "center", gap: 24, flexWrap: "wrap" }}>
            <ProgressRing
              value={metrics.length ? round((metrics.reduce((s, m) => s + m.steps, 0) / metrics.length / 10000) * 100) : 0}
              max={100}
              size={120}
              stroke={11}
              color="#4ade80"
              sublabel="STEP GOAL"
            />
            <ProgressRing
              value={chrono.length ? round((chrono.reduce((s, m) => s + m.sleep_hours, 0) / chrono.length / 8) * 100) : 0}
              max={100}
              size={120}
              stroke={11}
              color="#a78bfa"
              sublabel="SLEEP GOAL"
            />
          </div>
        </div>
      </div>

      {/* Personal records */}
      <div>
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 12 }}>
          <Trophy size={18} color="#fbbf24" />
          <h3 style={{ fontSize: 16, fontWeight: 700, color: "#cbd5e1", margin: 0 }}>Personal records</h3>
        </div>
        {records.length === 0 ? (
          <div className="card">
            <EmptyState
              icon={<Trophy size={28} color="#64748b" />}
              title="No records yet"
              message="Your best lifts and fastest times will be tracked here as you train."
            />
          </div>
        ) : (
          <div className="grid-3">
            {records.map((r) => {
              const gain = r.previous_value !== null && r.previous_value !== undefined ? round(r.record_value - r.previous_value, 1) : null;
              return (
                <div className="card" key={r.id}>
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 8 }}>
                    <div>
                      <div style={{ fontSize: 14, fontWeight: 700, color: "#f0f6fc" }}>{r.exercise}</div>
                      <div style={{ fontSize: 12, color: "#64748b", marginTop: 3 }}>{formatDate(r.achieved_date)}</div>
                    </div>
                    <div className="stat-icon" style={{ background: "rgba(251,191,36,0.14)", color: "#fbbf24", width: 32, height: 32 }}>
                      <Trophy size={15} />
                    </div>
                  </div>
                  <div style={{ marginTop: 12, display: "flex", alignItems: "baseline", gap: 6 }}>
                    <span style={{ fontSize: 24, fontWeight: 800, color: "#f0f6fc" }}>{r.record_value}</span>
                    <span className="stat-unit">{r.unit}</span>
                    {gain !== null && gain > 0 && (
                      <span style={{ display: "inline-flex", alignItems: "center", gap: 2, color: "#4ade80", fontSize: 12, fontWeight: 700, marginLeft: "auto" }}>
                        <ArrowUpRight size={13} /> +{gain}
                      </span>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Body measurements */}
      <div>
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 12 }}>
          <Ruler size={18} color="#22d3ee" />
          <h3 style={{ fontSize: 16, fontWeight: 700, color: "#cbd5e1", margin: 0 }}>Body measurements</h3>
        </div>
        {!latestBody ? (
          <div className="card">
            <EmptyState
              icon={<Scale size={28} color="#64748b" />}
              title="No measurements yet"
              message="Add your first measurement to start tracking body composition changes."
              action={
                <button className="btn btn-sm" onClick={() => setOpenBody(true)}>
                  <Plus size={14} /> Add measurement
                </button>
              }
            />
          </div>
        ) : (
          <div className="card">
            <div className="grid-4" style={{ marginBottom: 16 }}>
              <div>
                <div style={{ fontSize: 26, fontWeight: 800, color: "#f0f6fc" }}>
                  {latestBody.weight_lb} <span className="stat-unit">lb</span>
                </div>
                <span className="stat-meta">
                  {weightChange === 0 ? "Unchanged" : `${weightChange > 0 ? "+" : ""}${weightChange} lb since start`}
                </span>
              </div>
              <div>
                <div style={{ fontSize: 26, fontWeight: 800, color: "#f0f6fc" }}>
                  {latestBody.body_fat_pct} <span className="stat-unit">%</span>
                </div>
                <span className="stat-meta">Body fat</span>
              </div>
              <div>
                <div style={{ fontSize: 26, fontWeight: 800, color: "#f0f6fc" }}>
                  {latestBody.waist_in} <span className="stat-unit">in</span>
                </div>
                <span className="stat-meta">Waist</span>
              </div>
              <div>
                <div style={{ fontSize: 26, fontWeight: 800, color: "#f0f6fc" }}>
                  {latestBody.arm_in} <span className="stat-unit">in</span>
                </div>
                <span className="stat-meta">Arm</span>
              </div>
            </div>
            <p style={{ fontSize: 12, color: "#64748b", margin: 0 }}>
              Measured {formatDate(latestBody.metric_date)} · {bodyChrono.length} entries recorded
            </p>
          </div>
        )}
      </div>

      {/* Distance */}
      {totalDistance > 0 && (
        <div className="card">
          <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 10 }}>
            <Activity size={18} color="#4ade80" />
            <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>Distance covered</span>
          </div>
          <div style={{ fontSize: 28, fontWeight: 800, color: "#f0f6fc" }}>
            {round(totalDistance, 1)} <span className="stat-unit">miles</span>
          </div>
          <span className="stat-meta">Built up across {workouts.filter((w) => w.distance_miles).length} sessions</span>
        </div>
      )}

      {openBody && (
        <Modal title="Add body measurement" onClose={() => setOpenBody(false)}>
          <div className="flex-col" style={{ gap: 16 }}>
            {error && (
              <div style={{ background: "rgba(239,68,68,0.12)", border: "1px solid rgba(239,68,68,0.3)", color: "#fca5a5", padding: "10px 14px", borderRadius: 10, fontSize: 13 }}>
                {error}
              </div>
            )}
            <div className="form-row">
              <div className="form-group">
                <label className="form-label">Weight (lb)</label>
                <input className="form-input" type="number" step="0.1" value={form.weight_lb} onChange={(e) => setForm({ ...form, weight_lb: Number(e.target.value) })} />
              </div>
              <div className="form-group">
                <label className="form-label">Body fat (%)</label>
                <input className="form-input" type="number" step="0.1" value={form.body_fat_pct} onChange={(e) => setForm({ ...form, body_fat_pct: Number(e.target.value) })} />
              </div>
            </div>
            <div className="form-row">
              <div className="form-group">
                <label className="form-label">Waist (in)</label>
                <input className="form-input" type="number" step="0.1" value={form.waist_in} onChange={(e) => setForm({ ...form, waist_in: Number(e.target.value) })} />
              </div>
              <div className="form-group">
                <label className="form-label">Chest (in)</label>
                <input className="form-input" type="number" step="0.1" value={form.chest_in} onChange={(e) => setForm({ ...form, chest_in: Number(e.target.value) })} />
              </div>
            </div>
            <div className="form-row">
              <div className="form-group">
                <label className="form-label">Arm (in)</label>
                <input className="form-input" type="number" step="0.1" value={form.arm_in} onChange={(e) => setForm({ ...form, arm_in: Number(e.target.value) })} />
              </div>
              <div className="form-group">
                <label className="form-label">Thigh (in)</label>
                <input className="form-input" type="number" step="0.1" value={form.thigh_in} onChange={(e) => setForm({ ...form, thigh_in: Number(e.target.value) })} />
              </div>
            </div>
            <div style={{ display: "flex", gap: 10, justifyContent: "flex-end" }}>
              <button className="btn btn-secondary" onClick={() => setOpenBody(false)}>Cancel</button>
              <button className="btn" onClick={saveBody} disabled={saving}>{saving ? "Saving…" : "Save measurement"}</button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}

function buildMonthlyReview(
  metrics: DailyMetric[],
  workouts: Workout[],
  records: PersonalRecord[],
  weightChange: number
): { label: string; detail: string; color: string }[] {
  const reviews: { label: string; detail: string; color: string }[] = [];
  const completed = workouts.filter((w) => w.completed);

  if (completed.length) {
    const totalMin = completed.reduce((s, w) => s + w.duration_minutes, 0);
    reviews.push({
      label: `${completed.length} sessions completed`,
      detail: `You logged ${totalMin} training minutes. That consistency is what drives long-term change.`,
      color: "#38bdf8",
    });
  }

  if (records.length) {
    reviews.push({
      label: `${records.length} personal records tracked`,
      detail: `Your standout is ${records[0].exercise} at ${records[0].record_value} ${records[0].unit}.`,
      color: "#fbbf24",
    });
  }

  if (metrics.length >= 7) {
    const half = Math.floor(metrics.length / 2);
    const recent = metrics.slice(0, half);
    const older = metrics.slice(half);
    const recentAvg = recent.reduce((s, m) => s + m.readiness, 0) / recent.length;
    const olderAvg = older.reduce((s, m) => s + m.readiness, 0) / older.length;
    const diff = Math.round(recentAvg - olderAvg);
    reviews.push({
      label: diff >= 0 ? `Readiness up ${diff} points` : `Readiness down ${Math.abs(diff)} points`,
      detail:
        diff >= 0
          ? "Recovery capacity is improving compared with the earlier part of this period."
          : "Recovery dipped recently. More sleep and fewer back to back hard days would help.",
      color: diff >= 0 ? "#4ade80" : "#fb923c",
    });
  }

  if (weightChange !== 0) {
    reviews.push({
      label: `Weight ${weightChange > 0 ? "up" : "down"} ${Math.abs(weightChange)} lb`,
      detail: "Body composition changes slowly. Pair this with strength and measurement trends, not weight alone.",
      color: "#22d3ee",
    });
  }

  if (!reviews.length) {
    reviews.push({
      label: "Building your baseline",
      detail: "Log a few sessions and daily metrics to unlock a personalized monthly review.",
      color: "#94a3b8",
    });
  }

  return reviews.slice(0, 4);
}



