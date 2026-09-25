import { useMemo, useState } from "react";
import { Plus, Play, Trash2, Copy, Star, Search, X, ClipboardList } from "lucide-react";
import { apiData, WORKOUT_TYPES } from "../lib/api/dataAdapter";
import { completeWorkoutTemplate } from "../lib/api/workoutApi";
import type { Exercise, WorkoutTemplate, TemplateExercise } from "../lib/types";
import { EmptyState, Modal, SectionHeader } from "../components/ui";
import { templateSummary } from "../lib/workoutMetrics";

type TemplateWithItems = WorkoutTemplate & { items: (TemplateExercise & { exercise: Exercise | null })[] };

type Props = {
  templates: TemplateWithItems[];
  exercises: Exercise[];
  onRefresh: () => void;
  onStartFromTemplate: (template: TemplateWithItems) => void;
};

export default function TemplatesView({ templates, exercises, onRefresh, onStartFromTemplate }: Props) {
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<TemplateWithItems | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<TemplateWithItems | null>(null);

  const [form, setForm] = useState({ name: "", description: "", workout_type: "Strength", estimated_minutes: 45 });
  const [picked, setPicked] = useState<{ exercise: Exercise; target_sets: number; target_reps: string }[]>([]);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [search, setSearch] = useState("");

  const filtered = useMemo(() => {
    const needle = search.trim().toLowerCase();
    if (!needle) return exercises.slice(0, 40);
    return exercises.filter((e) => e.name.toLowerCase().includes(needle) || e.muscle_group.toLowerCase().includes(needle)).slice(0, 40);
  }, [exercises, search]);

  function openNew() {
    setEditing(null);
    setForm({ name: "", description: "", workout_type: "Strength", estimated_minutes: 45 });
    setPicked([]);
    setError(null);
    setEditorOpen(true);
  }

  function openEdit(t: TemplateWithItems) {
    setEditing(t);
    setForm({ name: t.name, description: t.description, workout_type: t.workout_type, estimated_minutes: t.estimated_minutes });
    setPicked(
      t.items.filter((i) => i.exercise).map((i) => ({ exercise: i.exercise as Exercise, target_sets: i.target_sets, target_reps: i.target_reps }))
    );
    setError(null);
    setEditorOpen(true);
  }

  async function insertItems(templateId: string) {
    const rows = picked.map((p, i) => ({
      template_id: templateId,
      exercise_id: p.exercise.id,
      order_index: i,
      target_sets: Number(p.target_sets) || 3,
      target_reps: p.target_reps || "8-12",
    }));
    if (rows.length) await apiData.from("workout_template_exercises").insert(rows);
  }

  async function saveTemplate() {
    if (!form.name.trim()) {
      setError("Give the template a name.");
      return;
    }
    if (picked.length === 0) {
      setError("Add at least one exercise to the template.");
      return;
    }
    setSaving(true);
    setError(null);

    try {
      if (editing) {
        const { error: updateError } = await apiData
          .from("workout_templates")
          .update({
            name: form.name.trim(),
            description: form.description.trim(),
            workout_type: form.workout_type,
            estimated_minutes: Number(form.estimated_minutes),
          })
          .eq("id", editing.id);
        if (updateError) {
          setSaving(false);
          setError("That template could not be updated.");
          return;
        }
        await apiData.from("workout_template_exercises").delete().eq("template_id", editing.id);
        await insertItems(editing.id);
      } else {
        const payload = { template: { name: form.name.trim(), description: form.description.trim(), workout_type: form.workout_type, estimated_minutes: Number(form.estimated_minutes), favorite: false }, exercises: picked.map((p, i) => ({ exercise_id: p.exercise.id, order_index: i, target_sets: Number(p.target_sets) || 3, target_reps: p.target_reps || '8-12', target_weight: null })) };
        await completeWorkoutTemplate(payload);
      }
    } catch { setSaving(false); setError('That template could not be saved.'); return; }
    setSaving(false);
    setEditorOpen(false);
    onRefresh();
  }

  async function duplicate(t: TemplateWithItems) {
    const { data: created } = await apiData
      .from("workout_templates")
      .insert({
        name: `${t.name} (copy)`,
        description: t.description,
        workout_type: t.workout_type,
        estimated_minutes: t.estimated_minutes,
      })
      .select("id")
      .maybeSingle();
    if (!created) return;
    const rows = t.items.map((i, idx) => ({
      template_id: created.id,
      exercise_id: i.exercise_id,
      order_index: idx,
      target_sets: i.target_sets,
      target_reps: i.target_reps,
      target_weight: i.target_weight,
    }));
    if (rows.length) await apiData.from("workout_template_exercises").insert(rows);
    onRefresh();
  }

  async function toggleFavorite(t: TemplateWithItems) {
    await apiData.from("workout_templates").update({ is_favorite: !t.is_favorite }).eq("id", t.id);
    onRefresh();
  }

  async function doDelete(t: TemplateWithItems) {
    await apiData.from("workout_templates").delete().eq("id", t.id);
    setConfirmDelete(null);
    onRefresh();
  }

  return (
    <div className="flex-col">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: 12 }}>
        <SectionHeader title="Workout templates" subtitle="Reusable plans you can start with one tap" />
        <button className="btn" onClick={openNew}>
          <Plus size={16} /> New template
        </button>
      </div>

      {templates.length === 0 ? (
        <div className="card">
          <EmptyState
            icon={<ClipboardList size={28} color="#64748b" />}
            title="No templates yet"
            message="Build a template once, then start it whenever you train."
            action={
              <button className="btn btn-sm" onClick={openNew}>
                <Plus size={14} /> Create a template
              </button>
            }
          />
        </div>
      ) : (
        <div className="grid-2">
          {templates.map((t) => (
            <div className="card" key={t.id}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 8 }}>
                <div>
                  <div style={{ fontSize: 16, fontWeight: 700, color: "#f0f6fc" }}>
                    {t.name}
                    {t.is_favorite && <Star size={13} color="#fbbf24" style={{ marginLeft: 6 }} fill="#fbbf24" />}
                  </div>
                  <div className="stat-meta" style={{ marginTop: 4 }}>{templateSummary(t, t.items)}</div>
                </div>
                <span className="badge" style={{ background: "rgba(56,189,248,0.14)", color: "#38bdf8" }}>{t.workout_type}</span>
              </div>

              {t.description && (
                <p style={{ fontSize: 13, color: "#94a3b8", margin: "10px 0 0", lineHeight: 1.5 }}>{t.description}</p>
              )}

              <div style={{ display: "flex", flexDirection: "column", gap: 6, margin: "14px 0" }}>
                {t.items.slice(0, 4).map((i) => (
                  <div key={i.id} className="template-row">
                    <span style={{ color: "#cbd5e1", fontSize: 13 }}>{i.exercise?.name ?? "Unknown exercise"}</span>
                    <span className="stat-meta">{i.target_sets} × {i.target_reps}</span>
                  </div>
                ))}
                {t.items.length > 4 && <span className="stat-meta">+ {t.items.length - 4} more exercises</span>}
              </div>

              <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                <button className="btn btn-sm" onClick={() => onStartFromTemplate(t)}>
                  <Play size={14} /> Start workout
                </button>
                <button className="btn btn-secondary btn-sm" onClick={() => openEdit(t)}>Edit</button>
                <button className="btn btn-secondary btn-sm" onClick={() => duplicate(t)} aria-label="Duplicate template">
                  <Copy size={14} />
                </button>
                <button className="btn btn-secondary btn-sm" onClick={() => toggleFavorite(t)} aria-label="Favorite template">
                  <Star size={14} color={t.is_favorite ? "#fbbf24" : "#64748b"} />
                </button>
                <button className="btn btn-secondary btn-sm" onClick={() => setConfirmDelete(t)} aria-label="Delete template">
                  <Trash2 size={14} color="#f87171" />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {editorOpen && (
        <Modal title={editing ? "Edit template" : "New template"} onClose={() => setEditorOpen(false)}>
          <div className="flex-col" style={{ gap: 16 }}>
            {error && <div className="form-error" role="alert"><span>{error}</span></div>}

            <div className="form-group">
              <label className="form-label">Template name</label>
              <input className="form-input" placeholder="e.g. Push Day" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </div>

            <div className="form-row">
              <div className="form-group">
                <label className="form-label">Type</label>
                <select className="form-select" value={form.workout_type} onChange={(e) => setForm({ ...form, workout_type: e.target.value })}>
                  {WORKOUT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
              </div>
              <div className="form-group">
                <label className="form-label">Estimated minutes</label>
                <input className="form-input" type="number" min={5} step={5} value={form.estimated_minutes} onChange={(e) => setForm({ ...form, estimated_minutes: Number(e.target.value) })} />
              </div>
            </div>

            <div className="form-group">
              <label className="form-label">Description (optional)</label>
              <input className="form-input" placeholder="What is this session for?" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
            </div>

            <div>
              <div className="stat-label" style={{ marginBottom: 8 }}>Exercises</div>
              {picked.length === 0 && <p style={{ fontSize: 13, color: "#64748b", margin: "0 0 10px" }}>No exercises added yet.</p>}
              <div className="flex-col" style={{ gap: 8 }}>
                {picked.map((p, idx) => (
                  <div key={`${p.exercise.id}-${idx}`} className="picked-row">
                    <span style={{ flex: 1, fontSize: 13, color: "#f0f6fc", fontWeight: 600 }}>{p.exercise.name}</span>
                    <input
                      className="form-input set-input"
                      type="number"
                      min={1}
                      max={10}
                      aria-label="Target sets"
                      value={p.target_sets}
                      onChange={(e) => {
                        const v = Number(e.target.value);
                        setPicked((prev) => prev.map((x, i) => (i === idx ? { ...x, target_sets: v } : x)));
                      }}
                    />
                    <input
                      className="form-input set-input"
                      style={{ width: 78 }}
                      aria-label="Target reps"
                      value={p.target_reps}
                      onChange={(e) => {
                        const v = e.target.value;
                        setPicked((prev) => prev.map((x, i) => (i === idx ? { ...x, target_reps: v } : x)));
                      }}
                    />
                    <button className="icon-btn" onClick={() => setPicked((prev) => prev.filter((_, i) => i !== idx))} aria-label="Remove exercise">
                      <X size={14} color="#f87171" />
                    </button>
                  </div>
                ))}
              </div>
              <button className="btn btn-secondary btn-sm" style={{ marginTop: 10 }} onClick={() => setPickerOpen(true)}>
                <Plus size={14} /> Add exercise
              </button>
            </div>

            <div style={{ display: "flex", gap: 10, justifyContent: "flex-end" }}>
              <button className="btn btn-secondary" onClick={() => setEditorOpen(false)}>Cancel</button>
              <button className="btn" onClick={saveTemplate} disabled={saving}>{saving ? "Saving…" : "Save template"}</button>
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
                    {filtered.map((e) => (
                      <button
                        key={e.id}
                        className="picker-item"
                        onClick={() => {
                          setPicked((prev) => [...prev, { exercise: e, target_sets: 3, target_reps: "8-12" }]);
                          setPickerOpen(false);
                          setSearch("");
                        }}
                      >
                        <span style={{ fontWeight: 600, color: "#f0f6fc" }}>{e.name}</span>
                        <span className="stat-meta">{e.muscle_group}</span>
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            )}
          </div>
        </Modal>
      )}

      {confirmDelete && (
        <Modal title="Delete template?" onClose={() => setConfirmDelete(null)}>
          <p style={{ fontSize: 14, color: "#cbd5e1", lineHeight: 1.6, marginTop: 0 }}>
            “{confirmDelete.name}” will be removed. Your logged workouts are not affected.
          </p>
          <div style={{ display: "flex", gap: 10, justifyContent: "flex-end", marginTop: 20 }}>
            <button className="btn btn-secondary" onClick={() => setConfirmDelete(null)}>Keep it</button>
            <button className="btn btn-danger" onClick={() => doDelete(confirmDelete)}>Delete template</button>
          </div>
        </Modal>
      )}
    </div>
  );
}
