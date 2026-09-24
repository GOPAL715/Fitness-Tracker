import { useMemo, useState } from "react";
import { Plus, Trash2, Search, Dumbbell, Save, X } from "lucide-react";
import { apiData, WORKOUT_TYPES } from "../lib/api/dataAdapter";
import type { Exercise } from "../lib/types";
import { Modal } from "../components/ui";
import { estimateOneRepMax } from "../lib/workoutMetrics";
import { round } from "../lib/utils";

type SetDraft = { key: string; reps: string; weight: string; rpe: string };
type ExerciseDraft = { key: string; exercise: Exercise; sets: SetDraft[] };

type Props = {
  exercises: Exercise[];
  onClose: () => void;
  onSaved: () => void;
  initialTitle?: string;
  initialType?: string;
};

let keyCounter = 0;
const nextKey = () => `k${++keyCounter}`;
const blankSet = (): SetDraft => ({ key: nextKey(), reps: "8", weight: "", rpe: "" });

export default function SessionLogger({ exercises, onClose, onSaved, initialTitle = "", initialType = "Strength" }: Props) {
  const [title, setTitle] = useState(initialTitle);
  const [workoutType, setWorkoutType] = useState<string>(initialType);
  const [effort, setEffort] = useState("7");
  const [notes, setNotes] = useState("");
  const [drafts, setDrafts] = useState<ExerciseDraft[]>([]);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const filtered = useMemo(() => {
    const needle = search.trim().toLowerCase();
    if (!needle) return exercises.slice(0, 40);
    return exercises
      .filter((e) => e.name.toLowerCase().includes(needle) || e.muscle_group.toLowerCase().includes(needle))
      .slice(0, 40);
  }, [exercises, search]);

  const totals = useMemo(() => {
    let sets = 0, reps = 0, volume = 0, bestOrm = 0;
    for (const d of drafts) {
      for (const s of d.sets) {
        const r = Number(s.reps);
        const w = Number(s.weight);
        if (r > 0 || w > 0) sets += 1;
        if (r > 0) reps += r;
        if (r > 0 && w > 0) volume += r * w;
        const orm = estimateOneRepMax(w || null, r || null);
        if (orm && orm > bestOrm) bestOrm = orm;
      }
    }
    return { sets, reps, volume: round(volume, 1), bestOrm: round(bestOrm, 1) };
  }, [drafts]);

  function addExercise(exercise: Exercise) {
    setDrafts((prev) => [...prev, { key: nextKey(), exercise, sets: [blankSet()] }]);
    setPickerOpen(false);
    setSearch("");
    if (!title.trim()) setTitle(`${exercise.muscle_group} Session`);
  }

  function updateSet(exKey: string, setKey: string, patch: Partial<SetDraft>) {
    setDrafts((prev) =>
      prev.map((d) => (d.key !== exKey ? d : { ...d, sets: d.sets.map((s) => (s.key === setKey ? { ...s, ...patch } : s)) }))
    );
  }

  function addSet(exKey: string) {
    setDrafts((prev) =>
      prev.map((d) =>
        d.key === exKey
          ? { ...d, sets: [...d.sets, { ...blankSet(), reps: d.sets[d.sets.length - 1]?.reps ?? "8", weight: d.sets[d.sets.length - 1]?.weight ?? "" }] }
          : d
      )
    );
  }

  function removeSet(exKey: string, setKey: string) {
    setDrafts((prev) => prev.map((d) => (d.key === exKey ? { ...d, sets: d.sets.filter((s) => s.key !== setKey) } : d)));
  }

  function removeExercise(exKey: string) {
    setDrafts((prev) => prev.filter((d) => d.key !== exKey));
  }

  async function save() {
    if (!title.trim()) {
      setError("Give the session a name.");
      return;
    }
    const usable = drafts.filter((d) => d.sets.some((s) => Number(s.reps) > 0 || Number(s.weight) > 0));
    if (usable.length === 0) {
      setError("Add at least one set with reps or weight.");
      return;
    }
    for (const d of usable) {
      for (const s of d.sets) {
        if (Number(s.reps) < 0 || Number(s.weight) < 0 || Number(s.rpe) < 0) {
          setError("Sets cannot contain negative values.");
          return;
        }
      }
    }

    setSaving(true);
    setError(null);

    const { data: session, error: sessionError } = await apiData
      .from("workout_sessions")
      .insert({
        title: title.trim(),
        workout_type: workoutType,
        duration_minutes: Math.max(10, totals.sets * 3),
        perceived_effort: Number(effort) || 6,
        notes: notes.trim() || null,
        completed: true,
        completed_at: new Date().toISOString(),
      })
      .select("id")
      .maybeSingle();

    if (sessionError || !session) {
      setSaving(false);
      setError("That session could not be saved. Please try again.");
      return;
    }

    for (let i = 0; i < usable.length; i++) {
      const d = usable[i];
      const { data: we, error: weError } = await apiData
        .from("workout_exercises")
        .insert({ workout_session_id: session.id, exercise_id: d.exercise.id, order_index: i })
        .select("id")
        .maybeSingle();
      if (weError || !we) continue;

      const rows = d.sets
        .filter((s) => Number(s.reps) > 0 || Number(s.weight) > 0)
        .map((s, idx) => ({
          workout_exercise_id: we.id,
          set_number: idx + 1,
          reps: Number(s.reps) > 0 ? Number(s.reps) : null,
          weight: Number(s.weight) > 0 ? Number(s.weight) : null,
          rpe: Number(s.rpe) > 0 ? Number(s.rpe) : null,
          completed: true,
        }));

      if (rows.length) await apiData.from("exercise_sets").insert(rows);
    }

    setSaving(false);
    onSaved();
    onClose();
  }

  return (
    <Modal title="Log a workout" onClose={onClose}>
      <div className="flex-col" style={{ gap: 16 }}>
        {error && <div className="form-error" role="alert"><span>{error}</span></div>}

        <div className="form-row">
          <div className="form-group">
            <label className="form-label">Session name</label>
            <input className="form-input" placeholder="e.g. Push Day" value={title} onChange={(e) => setTitle(e.target.value)} />
          </div>
          <div className="form-group">
            <label className="form-label">Type</label>
            <select className="form-select" value={workoutType} onChange={(e) => setWorkoutType(e.target.value)}>
              {WORKOUT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>
        </div>

        {drafts.length === 0 && (
          <div className="set-empty">
            <Dumbbell size={22} color="#64748b" />
            <span>No exercises yet. Add one to start logging sets.</span>
          </div>
        )}

        {drafts.map((d) => (
          <div key={d.key} className="set-block">
            <div className="set-block-head">
              <div>
                <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>{d.exercise.name}</span>
                <span className="badge badge-pending" style={{ marginLeft: 8 }}>{d.exercise.muscle_group}</span>
              </div>
              <button className="icon-btn" onClick={() => removeExercise(d.key)} aria-label={`Remove ${d.exercise.name}`}>
                <Trash2 size={15} color="#f87171" />
              </button>
            </div>

            <div className="set-grid-head">
              <span>Set</span><span>Reps</span><span>Weight</span><span>RPE</span><span />
            </div>

            {d.sets.map((s, idx) => {
              const orm = estimateOneRepMax(Number(s.weight) || null, Number(s.reps) || null);
              return (
                <div key={s.key} className="set-grid-row">
                  <span className="set-index">{idx + 1}</span>
                  <input className="form-input set-input" type="number" min={0} inputMode="numeric" aria-label="Reps" value={s.reps} onChange={(e) => updateSet(d.key, s.key, { reps: e.target.value })} />
                  <input className="form-input set-input" type="number" min={0} step="0.5" inputMode="decimal" aria-label="Weight" placeholder="lb" value={s.weight} onChange={(e) => updateSet(d.key, s.key, { weight: e.target.value })} />
                  <input className="form-input set-input" type="number" min={0} max={10} step="0.5" aria-label="Effort" placeholder="1-10" value={s.rpe} onChange={(e) => updateSet(d.key, s.key, { rpe: e.target.value })} />
                  <div className="set-row-actions">
                    {orm !== null && <span className="set-orm" title="Estimated one rep max">≈{orm}</span>}
                    <button className="icon-btn" onClick={() => removeSet(d.key, s.key)} aria-label="Remove set">
                      <X size={14} color="#64748b" />
                    </button>
                  </div>
                </div>
              );
            })}

            <button className="btn btn-secondary btn-sm" onClick={() => addSet(d.key)}>
              <Plus size={14} /> Add set
            </button>
          </div>
        ))}

        <button className="btn btn-secondary" onClick={() => setPickerOpen(true)}>
          <Plus size={16} /> Add exercise
        </button>

        {totals.sets > 0 && (
          <div className="set-totals">
            <div><span className="stat-meta">Sets</span><strong>{totals.sets}</strong></div>
            <div><span className="stat-meta">Reps</span><strong>{totals.reps}</strong></div>
            <div><span className="stat-meta">Volume</span><strong>{totals.volume} lb</strong></div>
            <div><span className="stat-meta">Best est. 1RM</span><strong>{totals.bestOrm || "—"}</strong></div>
          </div>
        )}

        <div className="form-row">
          <div className="form-group">
            <label className="form-label">Overall effort (RPE 1 to 10)</label>
            <input className="form-input" type="number" min={1} max={10} value={effort} onChange={(e) => setEffort(e.target.value)} />
          </div>
          <div className="form-group">
            <label className="form-label">Notes (optional)</label>
            <input className="form-input" placeholder="How did it feel?" value={notes} onChange={(e) => setNotes(e.target.value)} />
          </div>
        </div>

        <div style={{ display: "flex", gap: 10, justifyContent: "flex-end" }}>
          <button className="btn btn-secondary" onClick={onClose}>Cancel</button>
          <button className="btn" onClick={save} disabled={saving}>
            <Save size={16} /> {saving ? "Saving…" : "Save session"}
          </button>
        </div>

        {pickerOpen && (
          <div className="picker-overlay">
            <div className="picker">
              <div className="picker-head">
                <span style={{ fontWeight: 700, color: "#f0f6fc" }}>Choose an exercise</span>
                <button className="icon-btn" onClick={() => setPickerOpen(false)} aria-label="Close">
                  <X size={16} color="#94a3b8" />
                </button>
              </div>
              <div style={{ position: "relative", marginBottom: 12 }}>
                <Search size={16} color="#64748b" style={{ position: "absolute", left: 12, top: 12 }} />
                <input className="form-input" style={{ paddingLeft: 36 }} placeholder="Search exercises" value={search} onChange={(e) => setSearch(e.target.value)} autoFocus />
              </div>
              <div className="picker-list">
                {filtered.length === 0 ? (
                  <p style={{ color: "#64748b", fontSize: 14, textAlign: "center", padding: 20 }}>No exercises found.</p>
                ) : (
                  filtered.map((e) => (
                    <button key={e.id} className="picker-item" onClick={() => addExercise(e)}>
                      <span style={{ fontWeight: 600, color: "#f0f6fc" }}>{e.name}</span>
                      <span className="stat-meta">{e.muscle_group} · {e.equipment}</span>
                    </button>
                  ))
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </Modal>
  );
}



