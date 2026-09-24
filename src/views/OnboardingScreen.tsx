import { useState } from "react";
import { UserCog, ArrowRight } from "lucide-react";
import { apiData, GOALS, FITNESS_LEVELS, EQUIPMENT_OPTIONS, type Profile } from "../lib/api/dataAdapter";

type Props = { profile: Profile; onComplete: () => void };

export default function OnboardingScreen({ profile, onComplete }: Props) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState({
    display_name: profile.display_name === "Alex Morgan" ? "" : profile.display_name,
    goal: profile.goal || "Build strength",
    fitness_level: profile.fitness_level || "Intermediate",
    equipment: profile.equipment || "Full gym",
    activity_target: profile.activity_target || 4,
    weekly_minutes: profile.weekly_minutes || 180,
  });

  async function save() {
    if (!form.display_name.trim()) {
      setError("Please tell us what to call you.");
      return;
    }
    if (form.activity_target < 1 || form.activity_target > 14) {
      setError("Choose between 1 and 14 workouts per week.");
      return;
    }
    setSaving(true);
    setError(null);

    const { error: updateError } = await apiData
      .from("fitness_profile")
      .update({
        display_name: form.display_name.trim(),
        goal: form.goal,
        fitness_level: form.fitness_level,
        equipment: form.equipment,
        activity_target: Number(form.activity_target),
        weekly_minutes: Number(form.weekly_minutes),
      })
      .eq("id", profile.id);

    setSaving(false);
    if (updateError) {
      setError("We could not save your details. Please try again.");
      return;
    }
    onComplete();
  }

  return (
    <div className="auth-page">
      <div className="auth-card auth-card-wide">
        <div className="auth-brand">
          <div className="logo">
            <UserCog size={22} color="#38bdf8" />
          </div>
          <span className="brand-name">Set up your profile</span>
        </div>
        <p className="auth-sub">A few details so FitTrack can tailor your plan, targets and recommendations.</p>

        <div className="auth-form">
          {error && <div className="form-error" role="alert"><span>{error}</span></div>}

          <div className="form-group">
            <label className="form-label">What should we call you?</label>
            <input
              className="form-input"
              placeholder="Your name"
              value={form.display_name}
              onChange={(e) => setForm({ ...form, display_name: e.target.value })}
            />
          </div>

          <div className="form-row">
            <div className="form-group">
              <label className="form-label">Primary goal</label>
              <select className="form-select" value={form.goal} onChange={(e) => setForm({ ...form, goal: e.target.value })}>
                {GOALS.map((g) => <option key={g} value={g}>{g}</option>)}
              </select>
            </div>
            <div className="form-group">
              <label className="form-label">Fitness level</label>
              <select className="form-select" value={form.fitness_level} onChange={(e) => setForm({ ...form, fitness_level: e.target.value })}>
                {FITNESS_LEVELS.map((l) => <option key={l} value={l}>{l}</option>)}
              </select>
            </div>
          </div>

          <div className="form-group">
            <label className="form-label">Available equipment</label>
            <select className="form-select" value={form.equipment} onChange={(e) => setForm({ ...form, equipment: e.target.value })}>
              {EQUIPMENT_OPTIONS.map((e) => <option key={e} value={e}>{e}</option>)}
            </select>
          </div>

          <div className="form-row">
            <div className="form-group">
              <label className="form-label">Workouts per week</label>
              <input
                className="form-input"
                type="number"
                min={1}
                max={14}
                value={form.activity_target}
                onChange={(e) => setForm({ ...form, activity_target: Number(e.target.value) })}
              />
            </div>
            <div className="form-group">
              <label className="form-label">Training minutes per week</label>
              <input
                className="form-input"
                type="number"
                min={30}
                step={15}
                value={form.weekly_minutes}
                onChange={(e) => setForm({ ...form, weekly_minutes: Number(e.target.value) })}
              />
            </div>
          </div>

          <button className="btn auth-submit" onClick={save} disabled={saving}>
            {saving ? "Saving…" : "Start tracking"}
            {!saving && <ArrowRight size={16} />}
          </button>
        </div>
      </div>
    </div>
  );
}



