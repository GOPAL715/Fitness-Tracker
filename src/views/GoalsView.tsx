import { useMemo, useState } from "react";
import { Target, Plus, Check, Trash2, AlertTriangle } from "lucide-react";
import { apiData, type Profile } from "../lib/api/dataAdapter";
import { GOAL_TYPES, type Goal } from "../lib/types";
import { EmptyState, Modal, ProgressRing, SectionHeader } from "../components/ui";
import { todayISO, round } from "../lib/utils";

type Props = {
  goals: Goal[];
  profile: Profile | null;
  onRefresh: () => void;
};

export type GoalProgress = {
  pct: number;
  remaining: number;
  direction: "up" | "down" | "none";
  estimatedDate: string | null;
  trend: string;
};

/**
 * Progress toward a goal. The estimated date is deliberately phrased as a
 * projection based on the pace so far, never as a promise.
 */
export function computeGoalProgress(goal: Goal): GoalProgress {
  const span = goal.target_value - goal.start_value;
  const done = goal.current_value - goal.start_value;
  const pct = span === 0 ? (goal.current_value >= goal.target_value ? 100 : 0) : Math.round((done / span) * 100);
  const remaining = round(Math.max(0, goal.target_value - goal.current_value), 1);
  const direction: GoalProgress["direction"] = goal.target_value > goal.start_value ? "up" : goal.target_value < goal.start_value ? "down" : "none";

  const elapsedDays = Math.max(1, Math.round((Date.now() - new Date(goal.start_date + "T00:00:00").getTime()) / 86400000));
  const ratePerDay = done / elapsedDays;

  let estimatedDate: string | null = null;
  if (direction !== "none" && pct > 0 && pct < 100 && ratePerDay !== 0) {
    const daysLeft = remaining / Math.abs(ratePerDay);
    if (Number.isFinite(daysLeft) && daysLeft > 0 && daysLeft < 730) {
      const d = new Date();
      d.setDate(d.getDate() + Math.ceil(daysLeft));
      estimatedDate = d.toLocaleDateString(undefined, { month: "long", day: "numeric", year: "numeric" });
    }
  }

  const trend =
    pct >= 100 ? "Goal reached" : pct >= 60 ? "On track" : pct > 0 ? "Progress started" : "Not started yet";

  return { pct: Math.max(0, Math.min(100, pct)), remaining, direction, estimatedDate, trend };
}

export default function GoalsView({ goals, onRefresh }: Props) {
  const [openNew, setOpenNew] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<Goal | null>(null);

  const [form, setForm] = useState({
    goal_type: "Build Strength",
    title: "",
    description: "",
    start_value: 0,
    target_value: 100,
    current_value: 0,
    unit: "lbs",
    target_date: "",
  });

  const active = goals.filter((g) => g.status === "active");
  const achieved = goals.filter((g) => g.status === "achieved");

  const summary = useMemo(() => {
    if (active.length === 0) return null;
    const avg = Math.round(active.reduce((s, g) => s + computeGoalProgress(g).pct, 0) / active.length);
    return { avg, onTrack: active.filter((g) => computeGoalProgress(g).pct >= 50).length };
  }, [active]);

  async function saveGoal() {
    if (!form.title.trim()) {
      setError("Give the goal a title.");
      return;
    }
    if (Number(form.target_value) === Number(form.start_value)) {
      setError("The target must be different from the starting value.");
      return;
    }
    if (form.target_date && form.target_date < todayISO()) {
      setError("Choose a target date in the future.");
      return;
    }
    setSaving(true);
    setError(null);

    const { error: insertError } = await apiData.from("goals").insert({
      goal_type: form.goal_type,
      title: form.title.trim(),
      description: form.description.trim(),
      start_value: Number(form.start_value),
      target_value: Number(form.target_value),
      current_value: Number(form.current_value),
      unit: form.unit.trim(),
      target_date: form.target_date || null,
      status: "active",
    });

    setSaving(false);
    if (insertError) {
      setError("That goal could not be saved. Please try again.");
      return;
    }
    setForm({ goal_type: "Build Strength", title: "", description: "", start_value: 0, target_value: 100, current_value: 0, unit: "lbs", target_date: "" });
    setOpenNew(false);
    onRefresh();
  }

  async function updateProgress(goal: Goal, value: number) {
    if (!Number.isFinite(value)) return;
    const reached =
      goal.target_value >= goal.start_value ? value >= goal.target_value : value <= goal.target_value;
    await apiData
      .from("goals")
      .update({ current_value: value, status: reached ? "achieved" : "active" })
      .eq("id", goal.id);
    onRefresh();
  }

  async function deleteGoal(goal: Goal) {
    await apiData.from("goals").delete().eq("id", goal.id);
    setConfirmDelete(null);
    onRefresh();
  }

  return (
    <div className="flex-col" style={{ animation: "fadeInUp 0.4s ease both" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: 12 }}>
        <SectionHeader title="Goals" subtitle="Track progress against the outcomes that matter to you" />
        <button className="btn" onClick={() => { setError(null); setOpenNew(true); }}>
          <Plus size={16} /> New goal
        </button>
      </div>

      {summary && (
        <div className="card">
          <div className="ring-wrap" style={{ gap: 24, flexWrap: "wrap" }}>
            <ProgressRing value={summary.avg} max={100} size={110} stroke={11} color="#38bdf8" sublabel="AVG PROGRESS" />
            <div>
              <div style={{ fontSize: 16, fontWeight: 700, color: "#f0f6fc" }}>
                {summary.onTrack} of {active.length} goal{active.length === 1 ? "" : "s"} at or past halfway
              </div>
              <span className="stat-meta">Keep updating each goal as you make progress</span>
            </div>
          </div>
        </div>
      )}

      {goals.length === 0 ? (
        <div className="card">
          <EmptyState
            icon={<Target size={28} color="#64748b" />}
            title="No goals yet"
            message="Set a goal like reaching a target weight, a strength number or a weekly step target."
            action={
              <button className="btn btn-sm" onClick={() => setOpenNew(true)}>
                <Plus size={14} /> Create a goal
              </button>
            }
          />
        </div>
      ) : (
        <>
          <div className="grid-2">
            {active.map((g) => {
              const p = computeGoalProgress(g);
              return (
                <div className="card" key={g.id}>
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 8 }}>
                    <div style={{ minWidth: 0 }}>
                      <div style={{ fontSize: 16, fontWeight: 700, color: "#f0f6fc" }}>{g.title}</div>
                      <div className="stat-meta" style={{ marginTop: 4 }}>{g.goal_type}</div>
                    </div>
                    <span className="badge" style={{ background: "rgba(56,189,248,0.14)", color: "#38bdf8" }}>{p.trend}</span>
                  </div>

                  {g.description && (
                    <p style={{ fontSize: 13, color: "#94a3b8", margin: "10px 0 0", lineHeight: 1.5 }}>{g.description}</p>
                  )}

                  <div style={{ display: "flex", alignItems: "center", gap: 18, margin: "16px 0" }}>
                    <ProgressRing value={p.pct} max={100} size={92} stroke={9} color="#38bdf8" label={`${p.pct}%`} />
                    <div style={{ flex: 1 }}>
                      <div style={{ fontSize: 22, fontWeight: 800, color: "#f0f6fc" }}>
                        {g.current_value} <span className="stat-unit">/ {g.target_value} {g.unit}</span>
                      </div>
                      <span className="stat-meta">{p.remaining} {g.unit} to go</span>
                    </div>
                  </div>

                  <div className="form-group" style={{ marginBottom: 12 }}>
                    <label className="form-label">Update your current value</label>
                    <input
                      className="form-input"
                      type="number"
                      step="0.1"
                      defaultValue={g.current_value}
                      onBlur={(e) => updateProgress(g, Number(e.target.value))}
                    />
                  </div>

                  <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
                    <button className="btn btn-secondary btn-sm" onClick={() => updateProgress(g, g.target_value)}>
                      <Check size={14} /> Mark achieved
                    </button>
                    <button className="btn btn-secondary btn-sm" onClick={() => setConfirmDelete(g)} aria-label="Delete goal">
                      <Trash2 size={14} color="#f87171" />
                    </button>
                    {g.target_date && <span className="stat-meta">Target date {g.target_date}</span>}
                  </div>

                  {p.estimatedDate && (
                    <p className="estimate-note" style={{ marginTop: 12 }}>
                      At your current pace you could reach this around {p.estimatedDate}. This is a projection from your
                      logged data, not a guarantee.
                    </p>
                  )}
                </div>
              );
            })}
          </div>

          {achieved.length > 0 && (
            <div>
              <h3 style={{ fontSize: 15, fontWeight: 700, color: "#cbd5e1", margin: "0 0 12px" }}>Achieved</h3>
              <div className="grid-3">
                {achieved.map((g) => (
                  <div className="card" key={g.id}>
                    <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 8 }}>
                      <Check size={16} color="#4ade80" />
                      <span style={{ fontSize: 14, fontWeight: 700, color: "#f0f6fc" }}>{g.title}</span>
                    </div>
                    <div style={{ fontSize: 20, fontWeight: 800, color: "#f0f6fc" }}>
                      {g.current_value} <span className="stat-unit">{g.unit}</span>
                    </div>
                    <button className="btn btn-secondary btn-sm" style={{ marginTop: 12 }} onClick={() => setConfirmDelete(g)}>
                      <Trash2 size={14} color="#f87171" /> Remove
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      )}

      {openNew && (
        <Modal title="New goal" onClose={() => setOpenNew(false)}>
          <div className="flex-col" style={{ gap: 16 }}>
            {error && <div className="form-error" role="alert"><span>{error}</span></div>}

            <div className="form-group">
              <label className="form-label">Goal type</label>
              <select
                className="form-select"
                value={form.goal_type}
                onChange={(e) => {
                  const t = e.target.value;
                  const unit = t === "Lose Weight" || t === "Build Muscle" || t === "Maintain Weight" ? "lbs"
                    : t === "Increase Steps" ? "steps"
                    : t === "Improve Sleep" ? "hours"
                    : t === "Improve Nutrition" ? "g protein"
                    : "lbs";
                  setForm({ ...form, goal_type: t, unit });
                }}
              >
                {GOAL_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>

            <div className="form-group">
              <label className="form-label">Title</label>
              <input className="form-input" placeholder="e.g. Reach 175 lbs" value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
            </div>

            <div className="form-group">
              <label className="form-label">Description (optional)</label>
              <input className="form-input" placeholder="Why does this matter?" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
            </div>

            <div className="form-row">
              <div className="form-group">
                <label className="form-label">Starting value</label>
                <input className="form-input" type="number" step="0.1" value={form.start_value} onChange={(e) => setForm({ ...form, start_value: Number(e.target.value) })} />
              </div>
              <div className="form-group">
                <label className="form-label">Current value</label>
                <input className="form-input" type="number" step="0.1" value={form.current_value} onChange={(e) => setForm({ ...form, current_value: Number(e.target.value) })} />
              </div>
            </div>

            <div className="form-row">
              <div className="form-group">
                <label className="form-label">Target value</label>
                <input className="form-input" type="number" step="0.1" value={form.target_value} onChange={(e) => setForm({ ...form, target_value: Number(e.target.value) })} />
              </div>
              <div className="form-group">
                <label className="form-label">Unit</label>
                <input className="form-input" value={form.unit} onChange={(e) => setForm({ ...form, unit: e.target.value })} />
              </div>
            </div>

            <div className="form-group">
              <label className="form-label">Target date (optional)</label>
              <input className="form-input" type="date" min={todayISO()} value={form.target_date} onChange={(e) => setForm({ ...form, target_date: e.target.value })} />
            </div>

            <div style={{ display: "flex", gap: 10, justifyContent: "flex-end" }}>
              <button className="btn btn-secondary" onClick={() => setOpenNew(false)}>Cancel</button>
              <button className="btn" onClick={saveGoal} disabled={saving}>{saving ? "Saving…" : "Save goal"}</button>
            </div>
          </div>
        </Modal>
      )}

      {confirmDelete && (
        <Modal title="Delete goal?" onClose={() => setConfirmDelete(null)}>
          <div style={{ display: "flex", gap: 10, alignItems: "flex-start", marginBottom: 16 }}>
            <AlertTriangle size={18} color="#fb923c" style={{ flexShrink: 0, marginTop: 2 }} />
            <p style={{ fontSize: 14, color: "#cbd5e1", lineHeight: 1.6, margin: 0 }}>
              “{confirmDelete.title}” will be removed along with its progress history.
            </p>
          </div>
          <div style={{ display: "flex", gap: 10, justifyContent: "flex-end" }}>
            <button className="btn btn-secondary" onClick={() => setConfirmDelete(null)}>Keep it</button>
            <button className="btn btn-danger" onClick={() => deleteGoal(confirmDelete)}>Delete goal</button>
          </div>
        </Modal>
      )}
    </div>
  );
}





