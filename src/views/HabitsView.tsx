import { useMemo, useState } from "react";
import {
  Plus, Check, Trash2, Flame, Droplet, Footprints, Dumbbell, Moon, Beef,
  Activity, Sparkles, Utensils, Bell, Repeat,
} from "lucide-react";
import { apiData } from "../lib/api/dataAdapter";
import { HABIT_PRESETS, REMINDER_TYPES, type Habit, type HabitLog, type Reminder } from "../lib/types";
import { EmptyState, Modal, ProgressRing, SectionHeader } from "../components/ui";
import { habitStreak, habitWeeklyRate } from "../lib/workoutMetrics";
import { todayISO, dateOffset, DAY_LABELS_MON } from "../lib/utils";

type Props = {
  habits: Habit[];
  logs: HabitLog[];
  reminders: Reminder[];
  onRefresh: () => void;
};

const iconMap: Record<string, typeof Flame> = {
  droplet: Droplet,
  footprints: Footprints,
  dumbbell: Dumbbell,
  moon: Moon,
  beef: Beef,
  activity: Activity,
  sparkles: Sparkles,
  utensils: Utensils,
  check: Check,
};

function HabitIcon({ name, color }: { name: string; color: string }) {
  const Icon = iconMap[name] ?? Check;
  return <Icon size={17} color={color} />;
}

export default function HabitsView({ habits, logs, reminders, onRefresh }: Props) {
  const [openNew, setOpenNew] = useState(false);
  const [openReminder, setOpenReminder] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<Habit | null>(null);
  const [form, setForm] = useState({ name: "", icon: "check", color: "#38bdf8", target_per_week: 7 });
  const [reminderForm, setReminderForm] = useState({
    type: "WORKOUT",
    title: "",
    message: "",
    scheduled_time: "18:00",
    days: [0, 1, 2, 3, 4, 5, 6] as number[],
    quiet_hours_start: "22:00",
    quiet_hours_end: "07:00",
  });

  const today = todayISO();
  const last7 = useMemo(() => Array.from({ length: 7 }, (_, i) => dateOffset(6 - i)), []);

  const completedToday = useMemo(() => {
    const set = new Set(logs.filter((l) => l.log_date === today && l.completed).map((l) => l.habit_id));
    return habits.filter((h) => set.has(h.id)).length;
  }, [logs, habits, today]);

  function isDone(habitId: string, date: string) {
    return logs.some((l) => l.habit_id === habitId && l.log_date === date && l.completed);
  }

  async function toggle(habit: Habit, date: string) {
    const existing = logs.find((l) => l.habit_id === habit.id && l.log_date === date);
    if (existing) {
      await apiData.from("habit_logs").delete().eq("id", existing.id);
    } else {
      await apiData.from("habit_logs").insert({ habit_id: habit.id, log_date: date, completed: true });
    }
    onRefresh();
  }

  async function createHabit() {
    if (!form.name.trim()) {
      setError("Give the habit a name.");
      return;
    }
    setSaving(true);
    setError(null);
    const { error: insertError } = await apiData.from("habits").insert({
      name: form.name.trim(),
      icon: form.icon,
      color: form.color,
      target_per_week: Number(form.target_per_week) || 7,
    });
    setSaving(false);
    if (insertError) {
      setError("That habit could not be saved. Please try again.");
      return;
    }
    setForm({ name: "", icon: "check", color: "#38bdf8", target_per_week: 7 });
    setOpenNew(false);
    onRefresh();
  }

  async function createReminder() {
    if (!reminderForm.title.trim()) {
      setError("Give the reminder a title.");
      return;
    }
    if (reminderForm.days.length === 0) {
      setError("Choose at least one day for the reminder.");
      return;
    }
    setSaving(true);
    setError(null);
    const { error: insertError } = await apiData.from("reminders").insert({
      type: reminderForm.type,
      title: reminderForm.title.trim(),
      message: reminderForm.message.trim(),
      scheduled_time: reminderForm.scheduled_time,
      days_of_week: reminderForm.days,
      quiet_hours_start: reminderForm.quiet_hours_start,
      quiet_hours_end: reminderForm.quiet_hours_end,
      enabled: true,
    });
    setSaving(false);
    if (insertError) {
      setError("That reminder could not be saved. Please try again.");
      return;
    }
    setReminderForm({ ...reminderForm, title: "", message: "" });
    setOpenReminder(false);
    onRefresh();
  }

  async function toggleReminder(r: Reminder) {
    await apiData.from("reminders").update({ enabled: !r.enabled }).eq("id", r.id);
    onRefresh();
  }

  async function deleteReminder(id: string) {
    await apiData.from("reminders").delete().eq("id", id);
    onRefresh();
  }

  async function deleteHabit(h: Habit) {
    await apiData.from("habits").delete().eq("id", h.id);
    setConfirmDelete(null);
    onRefresh();
  }

  return (
    <div className="flex-col" style={{ animation: "fadeInUp 0.4s ease both" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: 12 }}>
        <SectionHeader title="Habits" subtitle="Small daily actions that compound into results" />
        <button className="btn" onClick={() => { setError(null); setOpenNew(true); }}>
          <Plus size={16} /> New habit
        </button>
      </div>

      {habits.length === 0 ? (
        <div className="card">
          <EmptyState
            icon={<Repeat size={28} color="#64748b" />}
            title="No habits yet"
            message="Add habits like drinking water, hitting your step goal or sleeping 7+ hours."
            action={
              <button className="btn btn-sm" onClick={() => setOpenNew(true)}>
                <Plus size={14} /> Add your first habit
              </button>
            }
          />
        </div>
      ) : (
        <>
          <div className="card">
            <div className="ring-wrap" style={{ justifyContent: "space-between", flexWrap: "wrap", gap: 20 }}>
              <div style={{ display: "flex", alignItems: "center", gap: 20 }}>
                <ProgressRing
                  value={completedToday}
                  max={habits.length}
                  size={110}
                  stroke={11}
                  color="#4ade80"
                  label={`${completedToday}/${habits.length}`}
                  sublabel="TODAY"
                />
                <div>
                  <div style={{ fontSize: 16, fontWeight: 700, color: "#f0f6fc" }}>
                    {completedToday === habits.length ? "Every habit done today" : `${habits.length - completedToday} habit${habits.length - completedToday === 1 ? "" : "s"} left today`}
                  </div>
                  <span className="stat-meta">Consistency beats intensity</span>
                </div>
              </div>
              <div style={{ display: "flex", gap: 24, flexWrap: "wrap" }}>
                <div>
                  <div style={{ fontSize: 26, fontWeight: 800, color: "#f0f6fc" }}>
                    {Math.max(0, ...habits.map((h) => habitStreak(h.id, logs)))}
                  </div>
                  <span className="stat-meta">longest active streak</span>
                </div>
                <div>
                  <div style={{ fontSize: 26, fontWeight: 800, color: "#f0f6fc" }}>{habits.length}</div>
                  <span className="stat-meta">habits tracked</span>
                </div>
              </div>
            </div>
          </div>

          <div className="flex-col" style={{ gap: 10 }}>
            {habits.map((h) => {
              const thisWeek = logs.filter((l) => l.habit_id === h.id && l.completed && l.log_date >= dateOffset(6));
              const doneCount = new Set(thisWeek.map((l) => l.log_date)).size;
              const streak = habitStreak(h.id, logs);
              const rate = habitWeeklyRate(h, logs);

              return (
                <div className="card habit-row" key={h.id}>
                  <div className="habit-head">
                    <div className="habit-icon" style={{ background: `${h.color}22` }}>
                      <HabitIcon name={h.icon} color={h.color} />
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>{h.name}</div>
                      <div className="workout-meta">
                        <span>{doneCount}/{h.target_per_week} this week</span>
                        {streak > 0 && (
                          <span style={{ display: "inline-flex", alignItems: "center", gap: 4, color: "#fb923c", fontWeight: 600 }}>
                            <Flame size={13} /> {streak} day streak
                          </span>
                        )}
                        <span>{rate}% of target</span>
                      </div>
                    </div>
                    <button className="icon-btn" onClick={() => setConfirmDelete(h)} aria-label={`Delete ${h.name}`}>
                      <Trash2 size={15} color="#f87171" />
                    </button>
                  </div>

                  <div className="habit-days">
                    {last7.map((d) => {
                      const done = isDone(h.id, d);
                      const isToday = d === today;
                      return (
                        <button
                          key={d}
                          className={`habit-day ${done ? "habit-day-done" : ""} ${isToday ? "habit-day-today" : ""}`}
                          style={done ? { background: `${h.color}2e`, borderColor: h.color } : undefined}
                          onClick={() => toggle(h, d)}
                          aria-label={`${h.name} on ${d}`}
                          aria-pressed={done}
                        >
                          <span>{DAY_LABELS_MON[(new Date(d + "T00:00:00").getDay() + 6) % 7].slice(0, 1)}</span>
                          {done && <Check size={13} color={h.color} />}
                        </button>
                      );
                    })}
                  </div>
                </div>
              );
            })}
          </div>
        </>
      )}

      {/* Reminders */}
      <div>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 12, marginBottom: 12 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <Bell size={18} color="#38bdf8" />
            <h3 style={{ fontSize: 16, fontWeight: 700, color: "#cbd5e1", margin: 0 }}>Reminders</h3>
          </div>
          <button className="btn btn-secondary btn-sm" onClick={() => { setError(null); setOpenReminder(true); }}>
            <Plus size={14} /> Add reminder
          </button>
        </div>

        {reminders.length === 0 ? (
          <div className="card">
            <EmptyState
              icon={<Bell size={28} color="#64748b" />}
              title="No reminders set"
              message="Schedule nudges for workouts, water, meals and sleep with quiet hours respected."
            />
          </div>
        ) : (
          <div className="flex-col" style={{ gap: 10 }}>
            {reminders.map((r) => (
              <div className="workout-item" key={r.id}>
                <div className="workout-icon" style={{ background: "rgba(56,189,248,0.14)", color: "#38bdf8" }}>
                  <Bell size={18} />
                </div>
                <div className="workout-info">
                  <div className="workout-title">{r.title}</div>
                  <div className="workout-meta">
                    <span>{r.type.replace("_", " ")}</span>
                    <span>{r.scheduled_time.slice(0, 5)}</span>
                    <span>{r.days_of_week.length === 7 ? "Every day" : r.days_of_week.map((d) => DAY_LABELS_MON[d]).join(", ")}</span>
                    {r.quiet_hours_start && <span>Quiet {r.quiet_hours_start.slice(0, 5)}–{r.quiet_hours_end?.slice(0, 5)}</span>}
                    <span className={`badge ${r.enabled ? "badge-done" : "badge-pending"}`}>{r.enabled ? "On" : "Off"}</span>
                  </div>
                </div>
                <div style={{ display: "flex", gap: 8 }}>
                  <button className="btn btn-secondary btn-sm" onClick={() => toggleReminder(r)}>
                    {r.enabled ? "Disable" : "Enable"}
                  </button>
                  <button className="btn btn-secondary btn-sm" onClick={() => deleteReminder(r.id)} aria-label="Delete reminder">
                    <Trash2 size={14} color="#f87171" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {openNew && (
        <Modal title="New habit" onClose={() => setOpenNew(false)}>
          <div className="flex-col" style={{ gap: 16 }}>
            {error && <div className="form-error" role="alert"><span>{error}</span></div>}

            <div>
              <div className="stat-label" style={{ marginBottom: 8 }}>Quick presets</div>
              <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                {HABIT_PRESETS.map((p) => (
                  <button
                    key={p.name}
                    className="chip"
                    onClick={() => setForm({ ...form, name: p.name, icon: p.icon, color: p.color })}
                  >
                    {p.name}
                  </button>
                ))}
              </div>
            </div>

            <div className="form-group">
              <label className="form-label">Habit name</label>
              <input className="form-input" placeholder="e.g. Walk after dinner" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </div>

            <div className="form-row">
              <div className="form-group">
                <label className="form-label">Target days per week</label>
                <input className="form-input" type="number" min={1} max={7} value={form.target_per_week} onChange={(e) => setForm({ ...form, target_per_week: Number(e.target.value) })} />
              </div>
              <div className="form-group">
                <label className="form-label">Colour</label>
                <input className="form-input" type="color" value={form.color} onChange={(e) => setForm({ ...form, color: e.target.value })} style={{ height: 42, padding: 4 }} />
              </div>
            </div>

            <div style={{ display: "flex", gap: 10, justifyContent: "flex-end" }}>
              <button className="btn btn-secondary" onClick={() => setOpenNew(false)}>Cancel</button>
              <button className="btn" onClick={createHabit} disabled={saving}>{saving ? "Saving…" : "Save habit"}</button>
            </div>
          </div>
        </Modal>
      )}

      {openReminder && (
        <Modal title="New reminder" onClose={() => setOpenReminder(false)}>
          <div className="flex-col" style={{ gap: 16 }}>
            {error && <div className="form-error" role="alert"><span>{error}</span></div>}

            <div className="form-row">
              <div className="form-group">
                <label className="form-label">Type</label>
                <select className="form-select" value={reminderForm.type} onChange={(e) => setReminderForm({ ...reminderForm, type: e.target.value })}>
                  {REMINDER_TYPES.map((t) => <option key={t} value={t}>{t.replace("_", " ")}</option>)}
                </select>
              </div>
              <div className="form-group">
                <label className="form-label">Time</label>
                <input className="form-input" type="time" value={reminderForm.scheduled_time} onChange={(e) => setReminderForm({ ...reminderForm, scheduled_time: e.target.value })} />
              </div>
            </div>

            <div className="form-group">
              <label className="form-label">Title</label>
              <input className="form-input" placeholder="e.g. Time to train" value={reminderForm.title} onChange={(e) => setReminderForm({ ...reminderForm, title: e.target.value })} />
            </div>

            <div>
              <div className="stat-label" style={{ marginBottom: 8 }}>Days</div>
              <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
                {DAY_LABELS_MON.map((d, i) => {
                  const on = reminderForm.days.includes(i);
                  return (
                    <button
                      key={d}
                      className={`chip ${on ? "chip-on" : ""}`}
                      onClick={() =>
                        setReminderForm({
                          ...reminderForm,
                          days: on ? reminderForm.days.filter((x) => x !== i) : [...reminderForm.days, i].sort(),
                        })
                      }
                    >
                      {d}
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="form-row">
              <div className="form-group">
                <label className="form-label">Quiet hours start</label>
                <input className="form-input" type="time" value={reminderForm.quiet_hours_start} onChange={(e) => setReminderForm({ ...reminderForm, quiet_hours_start: e.target.value })} />
              </div>
              <div className="form-group">
                <label className="form-label">Quiet hours end</label>
                <input className="form-input" type="time" value={reminderForm.quiet_hours_end} onChange={(e) => setReminderForm({ ...reminderForm, quiet_hours_end: e.target.value })} />
              </div>
            </div>

            <p className="estimate-note">
              Reminders are stored with your preferences now. Browser and push notifications can be layered on top later.
            </p>

            <div style={{ display: "flex", gap: 10, justifyContent: "flex-end" }}>
              <button className="btn btn-secondary" onClick={() => setOpenReminder(false)}>Cancel</button>
              <button className="btn" onClick={createReminder} disabled={saving}>{saving ? "Saving…" : "Save reminder"}</button>
            </div>
          </div>
        </Modal>
      )}

      {confirmDelete && (
        <Modal title="Delete habit?" onClose={() => setConfirmDelete(null)}>
          <p style={{ fontSize: 14, color: "#cbd5e1", lineHeight: 1.6, marginTop: 0 }}>
            “{confirmDelete.name}” and its check-in history will be removed.
          </p>
          <div style={{ display: "flex", gap: 10, justifyContent: "flex-end", marginTop: 20 }}>
            <button className="btn btn-secondary" onClick={() => setConfirmDelete(null)}>Keep it</button>
            <button className="btn btn-danger" onClick={() => deleteHabit(confirmDelete)}>Delete habit</button>
          </div>
        </Modal>
      )}
    </div>
  );
}



