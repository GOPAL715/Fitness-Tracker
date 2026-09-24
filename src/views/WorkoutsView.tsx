import { useMemo, useState } from "react";
import {
  Dumbbell, Plus, Check, Trash2, Timer, Flame, Calendar, Library,
  ClipboardList, Play, TrendingUp, LayoutTemplate,
} from "lucide-react";
import { apiData, WORKOUT_TYPES, INTENSITY_LEVELS, DAY_LABELS, type Workout, type PlanSession } from "../lib/api/dataAdapter";
import type { Exercise, WorkoutTemplate, TemplateExercise } from "../lib/types";
import type { SessionWithDetail } from "../lib/workoutMetrics";
import { computeSessionMetrics, templateSummary } from "../lib/workoutMetrics";
import { Modal, EmptyState, SectionHeader } from "../components/ui";
import { formatDate, todayISO, dayIndex, round } from "../lib/utils";
import { ExerciseLibraryView } from "./ExerciseLibraryView";
import SessionLogger from "./SessionLogger";
import TemplatesView from "./TemplatesView";

type TemplateWithItems = WorkoutTemplate & { items: (TemplateExercise & { exercise: Exercise | null })[] };

type Props = {
  workouts: Workout[];
  plan: PlanSession[];
  sessions: SessionWithDetail[];
  templates: TemplateWithItems[];
  exercises: Exercise[];
  onRefresh: () => void;
};

type SubTab = "plan" | "sessions" | "templates" | "library" | "history";

export default function WorkoutsView({ workouts, plan, sessions, templates, exercises, onRefresh }: Props) {
  const [subTab, setSubTab] = useState<SubTab>("plan");
  const [loggerOpen, setLoggerOpen] = useState(false);
  const [loggerPreset, setLoggerPreset] = useState<{ title: string; type: string } | null>(null);
  const [quickLogOpen, setQuickLogOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [quickForm, setQuickForm] = useState({
    title: "",
    workout_type: "Strength" as string,
    duration_minutes: 45,
    calories_burned: 350,
    intensity: "Moderate" as string,
    perceived_effort: 6,
    distance_miles: "",
    notes: "",
  });

  const todayDow = dayIndex(todayISO());

  const stats = useMemo(() => {
    const completedSessions = sessions.filter((s) => s.completed);
    let volume = 0;
    let sets = 0;
    for (const s of completedSessions) {
      const m = computeSessionMetrics(s);
      volume += m.totalVolume;
      sets += m.totalSets;
    }
    const minutes = completedSessions.reduce((sum, s) => sum + (s.duration_minutes ?? 0), 0);
    return { sessions: completedSessions.length, volume: Math.round(volume), sets, minutes };
  }, [sessions]);

  function openStructuredLogger(preset?: { title: string; type: string }) {
    setLoggerPreset(preset ?? null);
    setLoggerOpen(true);
  }

  async function togglePlan(session: PlanSession) {
    await apiData.from("plan_sessions").update({ completed: !session.completed }).eq("id", session.id);
    onRefresh();
  }

  async function startPlanSession(session: PlanSession) {
    openStructuredLogger({ title: session.title, type: session.workout_type });
  }

  async function saveQuickWorkout() {
    if (!quickForm.title.trim()) {
      setError("Give the session a name.");
      return;
    }
    setSaving(true);
    setError(null);
    const { error: insertError } = await apiData.from("workouts").insert({
      title: quickForm.title.trim(),
      workout_type: quickForm.workout_type,
      duration_minutes: Number(quickForm.duration_minutes) || 0,
      calories_burned: Number(quickForm.calories_burned) || 0,
      intensity: quickForm.intensity,
      perceived_effort: Number(quickForm.perceived_effort) || 6,
      distance_miles: quickForm.distance_miles ? Number(quickForm.distance_miles) : null,
      notes: quickForm.notes.trim() || null,
      workout_date: todayISO(),
      completed: true,
    });
    setSaving(false);
    if (insertError) {
      setError("That session could not be saved. Please try again.");
      return;
    }
    setQuickForm({ ...quickForm, title: "", distance_miles: "", notes: "" });
    setQuickLogOpen(false);
    onRefresh();
  }

  async function toggleWorkout(w: Workout) {
    await apiData.from("workouts").update({ completed: !w.completed }).eq("id", w.id);
    onRefresh();
  }

  async function deleteWorkout(id: string) {
    await apiData.from("workouts").delete().eq("id", id);
    onRefresh();
  }

  async function deleteSession(id: string) {
    await apiData.from("workout_sessions").delete().eq("id", id);
    onRefresh();
  }

  return (
    <div className="flex-col" style={{ animation: "fadeInUp 0.4s ease both" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: 12 }}>
        <SectionHeader title="Workouts" subtitle="Plan, log and review every training session" />
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
          <button className="btn" onClick={() => openStructuredLogger()}>
            <Plus size={16} /> Log workout
          </button>
          <button className="btn btn-secondary" onClick={() => { setError(null); setQuickLogOpen(true); }}>
            Quick log
          </button>
        </div>
      </div>

      <div className="grid-4">
        <div className="card">
          <div className="stat-tile-top" style={{ marginBottom: 8 }}>
            <span className="stat-label">Sessions</span>
            <div className="stat-icon" style={{ background: "rgba(56,189,248,0.14)", color: "#38bdf8" }}><Dumbbell size={18} /></div>
          </div>
          <div className="stat-value">{stats.sessions}</div>
          <span className="stat-meta">with set detail</span>
        </div>
        <div className="card">
          <div className="stat-tile-top" style={{ marginBottom: 8 }}>
            <span className="stat-label">Total volume</span>
            <div className="stat-icon" style={{ background: "rgba(74,222,128,0.14)", color: "#4ade80" }}><TrendingUp size={18} /></div>
          </div>
          <div className="stat-value">{stats.volume.toLocaleString()}</div>
          <span className="stat-meta">lb lifted · weight × reps</span>
        </div>
        <div className="card">
          <div className="stat-tile-top" style={{ marginBottom: 8 }}>
            <span className="stat-label">Working sets</span>
            <div className="stat-icon" style={{ background: "rgba(251,191,36,0.14)", color: "#fbbf24" }}><ClipboardList size={18} /></div>
          </div>
          <div className="stat-value">{stats.sets}</div>
          <span className="stat-meta">logged across sessions</span>
        </div>
        <div className="card">
          <div className="stat-tile-top" style={{ marginBottom: 8 }}>
            <span className="stat-label">Training time</span>
            <div className="stat-icon" style={{ background: "rgba(251,146,60,0.14)", color: "#fb923c" }}><Timer size={18} /></div>
          </div>
          <div className="stat-value">{stats.minutes}</div>
          <span className="stat-meta">minutes recorded</span>
        </div>
      </div>

      <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
        {([
          { id: "plan", label: "Weekly plan", icon: Calendar },
          { id: "sessions", label: "Set tracking", icon: Dumbbell },
          { id: "templates", label: "Templates", icon: LayoutTemplate },
          { id: "library", label: "Exercise library", icon: Library },
          { id: "history", label: "History", icon: ClipboardList },
        ] as const).map((t) => (
          <button
            key={t.id}
            onClick={() => setSubTab(t.id)}
            className={`nav-btn ${subTab === t.id ? "nav-btn-active" : ""}`}
          >
            <t.icon size={16} />
            <span>{t.label}</span>
          </button>
        ))}
      </div>

      {subTab === "plan" && (
        <div className="flex-col" style={{ gap: 10 }}>
          {plan.length === 0 ? (
            <div className="card">
              <EmptyState
                icon={<Calendar size={28} color="#64748b" />}
                title="No plan yet"
                message="Your weekly plan will appear here once it is created."
              />
            </div>
          ) : (
            plan.map((s) => {
              const isToday = s.day_index === todayDow;
              return (
                <div
                  className="workout-item"
                  key={s.id}
                  style={isToday ? { borderColor: "#38bdf8", background: "rgba(56,189,248,0.06)" } : undefined}
                >
                  <div
                    className="workout-icon"
                    style={{
                      background: isToday ? "rgba(56,189,248,0.18)" : "rgba(148,163,184,0.1)",
                      color: isToday ? "#38bdf8" : "#94a3b8",
                    }}
                  >
                    <span style={{ fontSize: 12, fontWeight: 800 }}>{DAY_LABELS[s.day_index]}</span>
                  </div>
                  <div className="workout-info">
                    <div className="workout-title">
                      {s.title} {isToday && <span style={{ color: "#38bdf8", fontSize: 12 }}>· Today</span>}
                    </div>
                    <div className="workout-meta">
                      <span>{s.workout_type}</span>
                      <span>{s.duration_minutes} min</span>
                      <span className={`badge badge-${s.intensity.toLowerCase()}`}>{s.intensity}</span>
                    </div>
                  </div>
                  <div style={{ display: "flex", gap: 8 }}>
                    {s.completed && (
                      <button className="btn btn-secondary btn-sm" onClick={() => togglePlan(s)}>
                        <Check size={14} /> Done
                      </button>
                    )}
                    <button className="btn btn-sm" onClick={() => startPlanSession(s)}>
                      <Play size={14} /> Start
                    </button>
                  </div>
                </div>
              );
            })
          )}
        </div>
      )}

      {subTab === "sessions" && (
        <div className="flex-col" style={{ gap: 10 }}>
          {sessions.length === 0 ? (
            <div className="card">
              <EmptyState
                icon={<Dumbbell size={28} color="#64748b" />}
                title="No detailed sessions yet"
                message="Use Log workout to record exercises with sets, reps and weight. Your volume and estimated one-rep max are calculated automatically."
                action={
                  <button className="btn btn-sm" onClick={() => openStructuredLogger()}>
                    <Plus size={14} /> Log a session
                  </button>
                }
              />
            </div>
          ) : (
            sessions.map((s) => {
              const m = computeSessionMetrics(s);
              return (
                <div className="card" key={s.id}>
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 10, flexWrap: "wrap" }}>
                    <div>
                      <div style={{ fontSize: 16, fontWeight: 700, color: "#f0f6fc" }}>{s.title}</div>
                      <div className="workout-meta" style={{ marginTop: 4 }}>
                        <span>{formatDate(s.started_at.slice(0, 10))}</span>
                        <span>{s.workout_type}</span>
                        <span>{s.duration_minutes} min</span>
                        <span>RPE {s.perceived_effort}/10</span>
                      </div>
                    </div>
                    <button className="btn btn-secondary btn-sm" onClick={() => deleteSession(s.id)} aria-label="Delete session">
                      <Trash2 size={14} color="#f87171" />
                    </button>
                  </div>

                  <div className="set-totals" style={{ marginTop: 14, gridTemplateColumns: "repeat(4, 1fr)" }}>
                    <div><span className="stat-meta">Sets</span><strong>{m.totalSets}</strong></div>
                    <div><span className="stat-meta">Reps</span><strong>{m.totalReps}</strong></div>
                    <div><span className="stat-meta">Volume</span><strong>{m.totalVolume.toLocaleString()} lb</strong></div>
                    <div>
                      <span className="stat-meta">Top lift</span>
                      <strong>{m.heaviestLift ? `${m.heaviestLift.weight} × ${m.heaviestLift.reps}` : "—"}</strong>
                    </div>
                  </div>

                  <div style={{ marginTop: 14, display: "flex", flexDirection: "column", gap: 10 }}>
                    {s.exercises.map((we) => {
                      const exSets = we.sets.filter((x) => x.completed);
                      if (exSets.length === 0) return null;
                      return (
                        <div key={we.id}>
                          <div style={{ display: "flex", justifyContent: "space-between", gap: 8, alignItems: "baseline" }}>
                            <span style={{ fontSize: 14, fontWeight: 700, color: "#f0f6fc" }}>
                              {we.exercise?.name ?? "Exercise"}
                            </span>
                            <span className="stat-meta">{we.exercise?.muscle_group}</span>
                          </div>
                          <div className="workout-meta" style={{ marginTop: 4 }}>
                            {exSets.map((x) => (
                              <span key={x.id} className="set-pill">
                                {x.reps ?? 0} reps{x.weight ? ` × ${x.weight} lb` : ""}
                              </span>
                            ))}
                          </div>
                        </div>
                      );
                    })}
                  </div>

                  {s.notes && (
                    <p style={{ fontSize: 13, color: "#94a3b8", margin: "12px 0 0", lineHeight: 1.5 }}>{s.notes}</p>
                  )}
                </div>
              );
            })
          )}
        </div>
      )}

      {subTab === "templates" && (
        <TemplatesView
          templates={templates}
          exercises={exercises}
          onRefresh={onRefresh}
          onStartFromTemplate={(t) => {
            setSubTab("plan");
            openStructuredLogger({ title: t.name, type: t.workout_type });
          }}
        />
      )}

      {subTab === "library" && <ExerciseLibraryView exercises={exercises} />}

      {subTab === "history" && (
        <div className="flex-col" style={{ gap: 10 }}>
          {workouts.length === 0 ? (
            <div className="card">
              <EmptyState
                icon={<ClipboardList size={28} color="#64748b" />}
                title="No sessions logged"
                message="Quick-logged sessions appear here with duration, calories and intensity."
                action={
                  <button className="btn btn-sm" onClick={() => setQuickLogOpen(true)}>
                    <Plus size={14} /> Quick log a workout
                  </button>
                }
              />
            </div>
          ) : (
            workouts.map((w) => (
              <div className="workout-item" key={w.id}>
                <div
                  className="workout-icon"
                  style={{
                    background: w.completed ? "rgba(74,222,128,0.14)" : "rgba(148,163,184,0.1)",
                    color: w.completed ? "#4ade80" : "#94a3b8",
                  }}
                >
                  <Dumbbell size={20} />
                </div>
                <div className="workout-info">
                  <div className="workout-title">{w.title}</div>
                  <div className="workout-meta">
                    <span>{formatDate(w.workout_date)}</span>
                    <span>{w.workout_type}</span>
                    <span>{w.duration_minutes} min</span>
                    <span>{w.calories_burned} cal</span>
                    {w.distance_miles ? <span>{round(w.distance_miles, 2)} mi</span> : null}
                    <span className={`badge badge-${w.intensity.toLowerCase()}`}>{w.intensity}</span>
                  </div>
                  {w.notes && (
                    <p style={{ fontSize: 12, color: "#64748b", margin: "6px 0 0", lineHeight: 1.5 }}>{w.notes}</p>
                  )}
                </div>
                <div style={{ display: "flex", gap: 8 }}>
                  <button className="btn btn-secondary btn-sm" onClick={() => toggleWorkout(w)} aria-label="Toggle completion">
                    {w.completed ? <Check size={14} /> : <Play size={14} />}
                  </button>
                  <button className="btn btn-secondary btn-sm" onClick={() => deleteWorkout(w.id)} aria-label="Delete workout">
                    <Trash2 size={14} color="#f87171" />
                  </button>
                </div>
              </div>
            ))
          )}
        </div>
      )}

      {loggerOpen && (
        <SessionLogger
          exercises={exercises}
          initialTitle={loggerPreset?.title ?? ""}
          initialType={loggerPreset?.type ?? "Strength"}
          onClose={() => setLoggerOpen(false)}
          onSaved={onRefresh}
        />
      )}

      {quickLogOpen && (
        <Modal title="Quick log a workout" onClose={() => setQuickLogOpen(false)}>
          <div className="flex-col" style={{ gap: 16 }}>
            {error && <div className="form-error" role="alert"><span>{error}</span></div>}

            <div className="form-group">
              <label className="form-label">Session name</label>
              <input className="form-input" placeholder="e.g. Morning run" value={quickForm.title} onChange={(e) => setQuickForm({ ...quickForm, title: e.target.value })} />
            </div>

            <div className="form-row">
              <div className="form-group">
                <label className="form-label">Type</label>
                <select className="form-select" value={quickForm.workout_type} onChange={(e) => setQuickForm({ ...quickForm, workout_type: e.target.value })}>
                  {WORKOUT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
              </div>
              <div className="form-group">
                <label className="form-label">Intensity</label>
                <select className="form-select" value={quickForm.intensity} onChange={(e) => setQuickForm({ ...quickForm, intensity: e.target.value })}>
                  {INTENSITY_LEVELS.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
              </div>
            </div>

            <div className="form-row">
              <div className="form-group">
                <label className="form-label">Duration (minutes)</label>
                <input className="form-input" type="number" min={1} value={quickForm.duration_minutes} onChange={(e) => setQuickForm({ ...quickForm, duration_minutes: Number(e.target.value) })} />
              </div>
              <div className="form-group">
                <label className="form-label">Calories burned</label>
                <input className="form-input" type="number" min={0} value={quickForm.calories_burned} onChange={(e) => setQuickForm({ ...quickForm, calories_burned: Number(e.target.value) })} />
              </div>
            </div>

            <div className="form-row">
              <div className="form-group">
                <label className="form-label">Effort (RPE 1 to 10)</label>
                <input className="form-input" type="number" min={1} max={10} value={quickForm.perceived_effort} onChange={(e) => setQuickForm({ ...quickForm, perceived_effort: Number(e.target.value) })} />
              </div>
              <div className="form-group">
                <label className="form-label">Distance (miles, optional)</label>
                <input className="form-input" type="number" step="0.01" min={0} placeholder="e.g. 3.5" value={quickForm.distance_miles} onChange={(e) => setQuickForm({ ...quickForm, distance_miles: e.target.value })} />
              </div>
            </div>

            <div className="form-group">
              <label className="form-label">Notes (optional)</label>
              <textarea className="form-input" rows={3} placeholder="How did it feel?" value={quickForm.notes} onChange={(e) => setQuickForm({ ...quickForm, notes: e.target.value })} />
            </div>

            <div style={{ display: "flex", gap: 10, justifyContent: "flex-end" }}>
              <button className="btn btn-secondary" onClick={() => setQuickLogOpen(false)}>Cancel</button>
              <button className="btn" onClick={saveQuickWorkout} disabled={saving}>{saving ? "Saving…" : "Save session"}</button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}



