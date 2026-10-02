import { useCallback, useEffect, useRef, useState } from "react";
import { disablePush, enablePush, pushSupport, pushExplainText } from "../lib/push/pushSubscription";
import type { SupportState } from "../lib/push/pushSubscription";
import {
  Target,
  Bell,
  ShieldCheck,
  Save,
  CheckCircle2,
  AlertTriangle,
  Info,
  Lightbulb,
  Trophy,
  UserCog,
  Sparkles,
  Settings,
} from "lucide-react";
import {
 GOALS,
  FITNESS_LEVELS,
  EQUIPMENT_OPTIONS,
  type Profile,
  type HealthDevice,
  type CoachNotification,
} from "../lib/domain";
import { SectionHeader, EmptyState } from "../components/ui";
import { HealthIntegrationsPanel } from "../components/HealthIntegrationsPanel";
import { useAuth } from "../lib/auth";
import { apiData } from "../lib/api/dataAdapter";

type Props = {
  profile: Profile | null;
  devices: HealthDevice[];
  notifications: CoachNotification[];
  onRefresh: () => void;
  /**
   * Phase 18: routes to the full notification settings centre.
   *
   * <p>Optional so existing consumers keep rendering unchanged; when absent the summary card omits
   * the link rather than offering a dead button.
   */
  onOpenNotificationSettings?: () => void;
};

const kindIcon = {
  win: <Trophy size={16} color="#fbbf24" />,
  warning: <AlertTriangle size={16} color="#fb923c" />,
  tip: <Lightbulb size={16} color="#38bdf8" />,
  info: <Info size={16} color="#94a3b8" />,
};

/** Every editable profile field, as the form holds it. */
type ProfileForm = {
  display_name: string;
  goal: string;
  fitness_level: string;
  equipment: string;
  limitations: string;
  activity_target: number;
  weekly_minutes: number;
  sleep_target_hours: number;
  step_target: number;
  calorie_target: number;
  protein_target_g: number;
  water_target_oz: number;
  target_weight_lb: number;
};

/**
 * The server row, falling back to the application defaults.
 *
 * <p>Exported so the component and its tests agree on one definition of "no value yet" rather than
 * scattering default literals through the view.
 */
export function profileToForm(profile: Profile | null): ProfileForm {
  return {
    display_name: profile?.display_name ?? "",
    goal: profile?.goal ?? "Build strength",
    fitness_level: profile?.fitness_level ?? "Intermediate",
    equipment: profile?.equipment ?? "Full gym",
    limitations: profile?.limitations ?? "None",
    activity_target: profile?.activity_target ?? 4,
    weekly_minutes: profile?.weekly_minutes ?? 180,
    sleep_target_hours: profile?.sleep_target_hours ?? 8,
    step_target: profile?.step_target ?? 10000,
    calorie_target: profile?.calorie_target ?? 2400,
    protein_target_g: profile?.protein_target_g ?? 150,
    water_target_oz: profile?.water_target_oz ?? 100,
    target_weight_lb: profile?.target_weight_lb ?? 175,
  };
}

/** The numeric fields the form owns, always sent as numbers rather than raw input strings. */
const NUMERIC_FIELDS = [
  "activity_target",
  "weekly_minutes",
  "sleep_target_hours",
  "step_target",
  "calorie_target",
  "protein_target_g",
  "water_target_oz",
  "target_weight_lb",
] as const;

/** The exact PUT body, matching the backend's writable column set. */
export function formToPayload(form: ProfileForm): Record<string, string | number> {
  const payload: Record<string, string | number> = { ...form };
  for (const field of NUMERIC_FIELDS) payload[field] = Number(form[field]);
  return payload;
}

export default function ProfileView({ profile, devices, notifications, onRefresh, onOpenNotificationSettings }: Props) {
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [pushState, setPushState] = useState<SupportState>("default");
const [pushBusy, setPushBusy] = useState(false);
/** Phase 18: the outcome of the last push action, in plain words, success or failure. */
const [pushNotice, setPushNotice] = useState<string | null>(null);
  const { session } = useAuth();
  const [coachError, setCoachError] = useState<string | null>(null);
  /** Phase 20: a failed coach-message read, reported beside the messages rather than beside devices. */
  const [coachReadError, setCoachReadError] = useState<string | null>(null);

  const [form, setForm] = useState<ProfileForm>(() => profileToForm(profile));
  /** True once the user edits a field, so a background refresh cannot discard their typing. */
  const dirty = useRef(false);
  /** The profile the form was last seeded from, so an unrelated parent render is not a refresh. */
  const seededFrom = useRef(profile);

  // Re-seed only when a genuinely different profile arrives. While the user has unsaved edits the
  // form is left alone; a save clears the flag first, so the post-save refresh does take effect.
  useEffect(() => {
    if (dirty.current || seededFrom.current === profile) return;
    seededFrom.current = profile;
    setForm(profileToForm(profile));
  }, [profile]);

  /** Applies one field edit and marks the form dirty. */
  const edit = useCallback(<K extends keyof ProfileForm>(key: K, value: ProfileForm[K]) => {
    dirty.current = true;
    setSaved(false);
    setSaveError(null);
    setForm((current) => ({ ...current, [key]: value }));
  }, []);

  async function saveProfile() {
    if (!profile) {
      setSaveError("Your profile is still loading. Please try again in a moment.");
      return;
    }
    setSaving(true);
    setSaveError(null);
    setSaved(false);
    try {
      // The adapter reports failure through `error`; the catch is the safety net for anything else,
      // so the button can never be left stuck on "Saving…" either way.
      const { error } = await apiData.from("fitness_profile").update(formToPayload(form)).eq("id", profile.id);
      if (error) {
        setSaveError("Your changes could not be saved. Please try again.");
        return;
      }
      dirty.current = false;
      setSaved(true);
      onRefresh();
      setTimeout(() => setSaved(false), 2500);
    } catch {
      setSaveError("Your changes could not be saved. Please try again.");
    } finally {
      setSaving(false);
    }
  }

  /**
   * Marks a coach message read.
   *
   * Phase 20: this used to share one `deviceError` slot with the health device cards, which meant a
   * failure to mark a message read could be reported under a heading about connected devices. The
   * two concerns now have separate state, and the health actions live in HealthIntegrationsPanel.
   */
  async function markRead(n: CoachNotification) {
    if (n.is_read) return;
    setCoachReadError(null);
    const { error } = await apiData.from("coach_notifications").update({ is_read: true }).eq("id", n.id);
    if (error) {
      setCoachReadError("That message could not be marked as read. Please try again.");
      return;
    }
    onRefresh();
  }

  const unread = notifications.filter((n) => !n.is_read).length;

  // Read the current push state on mount. This only inspects: it never prompts, because a permission
  // prompt raised without the user asking is treated as untrustworthy and usually just gets blocked.
  useEffect(() => {
    let active = true;
    pushSupport()
      .then((state) => { if (active) setPushState(state); })
      .catch(() => { if (active) setPushState("unsupported"); });
    return () => { active = false; };
  }, []);

  /** The only place permission is ever requested, and only because the user pressed the button. */
  async function togglePush() {
    setPushBusy(true);
    setPushNotice(null);
    try {
      // Phase 18: both flows return a result object rather than a bare state, so a rejected request
      // is reported as rejected instead of being written into the UI as if it had worked.
      const outcome = pushState === "subscribed" ? await disablePush() : await enablePush();
      setPushNotice(outcome.message);
      // Re-read rather than assume: success is only believed once the server and browser confirm it.
      setPushState(await pushSupport());
    } catch {
      setPushNotice("Could not reach AI FitTrack, so nothing was changed.");
    } finally {
      setPushBusy(false);
    }
  }

  return (
    <div className="flex-col" style={{ animation: "fadeInUp 0.4s ease both" }}>
      <SectionHeader title="Profile" subtitle="Goals, preferences, connected devices, and coaching" />

      {/* Goals */}
      <div className="card">
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 18 }}>
          <Target size={18} color="#38bdf8" />
          <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>Goals and targets</span>
        </div>

        <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
          <div className="form-row">
            <div className="form-group">
              <label className="form-label" htmlFor="profile-display_name">Name</label>
              <input id="profile-display_name" className="form-input" value={form.display_name} onChange={(e) => edit("display_name", e.target.value)} />
            </div>
            <div className="form-group">
              <label className="form-label" htmlFor="profile-goal">Primary goal</label>
              <select id="profile-goal" className="form-select" value={form.goal} onChange={(e) => edit("goal", e.target.value)}>
                {GOALS.map((g) => <option key={g} value={g}>{g}</option>)}
              </select>
            </div>
          </div>

          <div className="form-row">
            <div className="form-group">
              <label className="form-label" htmlFor="profile-fitness_level">Fitness level</label>
              <select id="profile-fitness_level" className="form-select" value={form.fitness_level} onChange={(e) => edit("fitness_level", e.target.value)}>
                {FITNESS_LEVELS.map((l) => <option key={l} value={l}>{l}</option>)}
              </select>
            </div>
            <div className="form-group">
              <label className="form-label" htmlFor="profile-equipment">Available equipment</label>
              <select id="profile-equipment" className="form-select" value={form.equipment} onChange={(e) => edit("equipment", e.target.value)}>
                {EQUIPMENT_OPTIONS.map((l) => <option key={l} value={l}>{l}</option>)}
              </select>
            </div>
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="profile-limitations">Injuries or limitations</label>
            <input id="profile-limitations" className="form-input" placeholder="e.g. Lower back sensitivity" value={form.limitations} onChange={(e) => edit("limitations", e.target.value)} />
          </div>

          <div className="form-row">
            <div className="form-group">
              <label className="form-label" htmlFor="profile-activity_target">Workouts per week</label>
              <input id="profile-activity_target" className="form-input" type="number" min={1} max={14} value={form.activity_target} onChange={(e) => edit("activity_target", Number(e.target.value))} />
            </div>
            <div className="form-group">
              <label className="form-label" htmlFor="profile-weekly_minutes">Training minutes per week</label>
              <input id="profile-weekly_minutes" className="form-input" type="number" min={30} value={form.weekly_minutes} onChange={(e) => edit("weekly_minutes", Number(e.target.value))} />
            </div>
          </div>

          <div className="form-row">
            <div className="form-group">
              <label className="form-label" htmlFor="profile-sleep_target_hours">Sleep target (hours)</label>
              <input id="profile-sleep_target_hours" className="form-input" type="number" step="0.5" value={form.sleep_target_hours} onChange={(e) => edit("sleep_target_hours", Number(e.target.value))} />
            </div>
            <div className="form-group">
              <label className="form-label" htmlFor="profile-step_target">Daily step target</label>
              <input id="profile-step_target" className="form-input" type="number" step="500" value={form.step_target} onChange={(e) => edit("step_target", Number(e.target.value))} />
            </div>
          </div>

          <div className="form-row">
            <div className="form-group">
              <label className="form-label" htmlFor="profile-calorie_target">Daily calorie target</label>
              <input id="profile-calorie_target" className="form-input" type="number" step="50" value={form.calorie_target} onChange={(e) => edit("calorie_target", Number(e.target.value))} />
            </div>
            <div className="form-group">
              <label className="form-label" htmlFor="profile-protein_target_g">Daily protein target (g)</label>
              <input id="profile-protein_target_g" className="form-input" type="number" step="5" value={form.protein_target_g} onChange={(e) => edit("protein_target_g", Number(e.target.value))} />
            </div>
          </div>

          <div className="form-row">
            <div className="form-group">
              <label className="form-label" htmlFor="profile-water_target_oz">Daily water target (oz)</label>
              <input id="profile-water_target_oz" className="form-input" type="number" step="5" value={form.water_target_oz} onChange={(e) => edit("water_target_oz", Number(e.target.value))} />
            </div>
            <div className="form-group">
              <label className="form-label" htmlFor="profile-target_weight_lb">Target weight (lb)</label>
              <input id="profile-target_weight_lb" className="form-input" type="number" step="1" value={form.target_weight_lb} onChange={(e) => edit("target_weight_lb", Number(e.target.value))} />
            </div>
          </div>

          {saveError && (
            <div style={{ background: "rgba(239,68,68,0.12)", border: "1px solid rgba(239,68,68,0.3)", color: "#fca5a5", padding: "10px 14px", borderRadius: 10, fontSize: 13 }}>
              {saveError}
            </div>
          )}

          <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
            <button className="btn" onClick={saveProfile} disabled={saving}>
              <Save size={16} /> {saving ? "Saving…" : "Save changes"}
            </button>
            {saved && (
              <span style={{ display: "inline-flex", alignItems: "center", gap: 6, color: "#4ade80", fontSize: 13, fontWeight: 600 }}>
                <CheckCircle2 size={15} /> Saved
              </span>
            )}
          </div>
        </div>
      </div>

      {/* Reminder notifications: a summary plus a route to the full settings centre. Permission is
          requested only when a button is pressed: a prompt the user did not ask for is the fastest
          way to have notifications blocked permanently. The detailed view owns the diagnosis, so
          this card deliberately does not restate server or permission state and the two screens
          cannot disagree about what is wrong. */}
      <div className="card">
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 6 }}>
          <Bell size={18} color="#4ade80" />
          <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>Reminder notifications</span>
        </div>
        <p style={{ fontSize: 13, color: "#94a3b8", margin: "0 0 14px", lineHeight: 1.55 }}>
          {pushExplainText(pushState)}
        </p>
        {pushNotice && (
          <p role="status" style={{ fontSize: 13, color: "#94a3b8", margin: "0 0 12px" }}>
            {pushNotice}
          </p>
        )}
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
          {pushState !== "unsupported" && pushState !== "insecure" && pushState !== "disabled" && (
            <button
              className="btn btn-secondary"
              onClick={togglePush}
              disabled={pushBusy}
              style={{ display: "inline-flex", alignItems: "center", gap: 8 }}
            >
              <Bell size={15} />
              {pushState === "subscribed" ? "Turn off on this device" : "Enable reminders"}
            </button>
          )}
          {onOpenNotificationSettings && (
            <button
              className="btn btn-secondary"
              onClick={onOpenNotificationSettings}
              style={{ display: "inline-flex", alignItems: "center", gap: 8 }}
            >
              <Settings size={15} />
              Notification settings
            </button>
          )}
        </div>
      </div>


      {/* Phase 20: the health integrations surface replaces the two cards that used to sit here.

          The old "Connected devices" card listed devices and offered sync and disconnect, and the old
          "Automatic device sync" card listed providers from a hardcoded array in healthProviders.ts.
          Both are now served by GET /api/v1/health/integrations and merged into one panel, because a
          provider and the connections to it are one fact about the user rather than two separate lists
          a client has to correlate - and a client-side correlation is exactly where a provider the
          server would reject could slip through.

          The two actions stay on the same endpoints as before - POST /health/devices,
          POST /health/devices/{id}/sync and DELETE /health/devices/{id} - so no route, no validation
          rule and no ownership check changed. The panel re-reads from the server after each action
          rather than optimistically editing a local list. */}
      <HealthIntegrationsPanel onRefresh={onRefresh} />

      {/* Coaching */}
      <div className="card">
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 6 }}>
          <Bell size={18} color="#fbbf24" />
          <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>Coach messages</span>
          {unread > 0 && (
            <span className="badge" style={{ background: "rgba(56,189,248,0.16)", color: "#38bdf8" }}>{unread} new</span>
          )}
        </div>
        <p style={{ fontSize: 13, color: "#94a3b8", margin: "0 0 18px", lineHeight: 1.55 }}>
          Personalized notes generated from your recent activity, recovery, and nutrition patterns.
          Ask a question and get tailored guidance from the Coach tab.
        </p>
        {coachReadError && (
          <div className="form-error" role="alert" style={{ marginBottom: 16 }}>
            <AlertTriangle size={16} />
            <span>{coachReadError}</span>
          </div>
        )}

        {coachError && (
          <div className="form-error" role="alert" style={{ marginBottom: 16 }}>
            <AlertTriangle size={16} />
            <span>{coachError}</span>
          </div>
        )}

        {notifications.length === 0 ? (
          <EmptyState
            icon={<Bell size={28} color="#64748b" />}
            title="No messages yet"
            message="Your coach messages will appear here as your training history builds up."
          />
        ) : (
          <div className="flex-col" style={{ gap: 10 }}>
            {notifications.map((n) => (
              <button
                key={n.id}
                onClick={() => markRead(n)}
                style={{
                  display: "flex",
                  gap: 14,
                  textAlign: "left",
                  width: "100%",
                  padding: 16,
                  borderRadius: 14,
                  background: n.is_read ? "rgba(30,41,59,0.3)" : "rgba(56,189,248,0.07)",
                  border: `1px solid ${n.is_read ? "#1e293b" : "rgba(56,189,248,0.25)"}`,
                  cursor: n.is_read ? "default" : "pointer",
                  transition: "all 0.2s ease",
                }}
              >
                <div
                  style={{
                    width: 36,
                    height: 36,
                    borderRadius: 10,
                    background: "rgba(148,163,184,0.1)",
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    flexShrink: 0,
                  }}
                >
                  {kindIcon[n.kind as keyof typeof kindIcon] ?? kindIcon.info}
                </div>
                <div style={{ flex: 1 }}>
                  <div style={{ fontSize: 14, fontWeight: 700, color: "#f0f6fc", marginBottom: 4 }}>{n.title}</div>
                  <p style={{ fontSize: 13, color: "#94a3b8", margin: 0, lineHeight: 1.55 }}>{n.message}</p>
                </div>
              </button>
            ))}
          </div>
        )}
      </div>

      {/* Privacy */}
      <div className="card">
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 12 }}>
          <ShieldCheck size={18} color="#4ade80" />
          <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>Privacy and your data</span>
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          <PrivacyRow
            icon={<UserCog size={16} color="#38bdf8" />}
            title="Private health records"
            detail="Your workouts, body measurements, and daily metrics are stored securely and never shared."
          />
          <PrivacyRow
            icon={<ShieldCheck size={16} color="#4ade80" />}
            title="Access controls enforced at the data layer"
            detail="Every record is tied to your account and enforced by the database itself, so no other account can read or change your data."
          />
          <PrivacyRow
            icon={<Info size={16} color="#94a3b8" />}
            title="Clear data origin labels"
            detail="Imported device data and manually entered data are kept distinct so you always know the source."
          />
          <PrivacyRow
            icon={<Info size={16} color="#38bdf8" />}
            title="AI features run on the server"
            detail="Photo scanning and weekly coaching are processed by secure server functions. Your image is only ever read through a short-lived private link, and no AI credentials are ever present in the app you are using."
          />
        </div>
      </div>
    </div>
  );
}

function PrivacyRow({ icon, title, detail }: { icon: React.ReactNode; title: string; detail: string }) {
  return (
    <div style={{ display: "flex", gap: 12 }}>
      <div
        style={{
          width: 32,
          height: 32,
          borderRadius: 9,
          background: "rgba(148,163,184,0.1)",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          flexShrink: 0,
        }}
      >
        {icon}
      </div>
      <div>
        <div style={{ fontSize: 14, fontWeight: 700, color: "#f0f6fc", marginBottom: 2 }}>{title}</div>
        <p style={{ fontSize: 13, color: "#94a3b8", margin: 0, lineHeight: 1.5 }}>{detail}</p>
      </div>
    </div>
  );
}

