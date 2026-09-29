import { useCallback, useEffect, useState } from "react";
import { Check, Moon, Save, ShieldAlert } from "lucide-react";

import {
  getNotificationPreferences,
  saveNotificationPreferences,
  type NotificationPreferences,
} from "../lib/api/notificationPreferencesApi";

/**
 * The user's own notification preferences (Phase 19).
 *
 * <p>Deliberately a separate card from the browser, server and device facts beside it. Those are
 * things the app observed; these are things the user decided, and merging them is exactly the
 * confusion this screen exists to prevent. A user can turn reminders off here while leaving this
 * browser subscribed, and neither setting implies the other.
 *
 * <p>Nothing here touches a push subscription or a reminder. Turning a preference off suppresses
 * future delivery only; it never unsubscribes a device the user may want back, and it never pauses or
 * deletes a reminder they created.
 */

type Status =
  | { kind: "loading" }
  | { kind: "ready"; message: string | null; error: string | null };

/** The state the server reports when a user has never set anything. */
function defaults(): NotificationPreferences {
  return {
    pushEnabled: true,
    reminderNotificationsEnabled: true,
    quietHoursEnabled: false,
    quietHoursStart: "22:00",
    quietHoursEnd: "07:00",
    // Deliberately empty rather than guessed. The server treats a null zone as "quiet hours cannot be
    // active", and a fabricated zone would silently place the window in the wrong hours.
    timezone: null,
  };
}

const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/;

/** Why the current quiet-hours values cannot be saved, if they cannot. */
function validate(form: NotificationPreferences): string | null {
  if (!form.quietHoursEnabled) return null;
  if (!form.timezone) return "Choose a timezone before turning on quiet hours.";
  if (!HHMM.test(form.quietHoursStart ?? "")) return "Quiet hours start must be a time like 22:00.";
  if (!HHMM.test(form.quietHoursEnd ?? "")) return "Quiet hours end must be a time like 07:00.";
  if (form.quietHoursStart === form.quietHoursEnd) {
    return "Quiet hours start and end must differ - otherwise it is unclear whether you want all day or none.";
  }
  return null;
}

export function NotificationPreferencesPanel() {
  const [status, setStatus] = useState<Status>({ kind: "loading" });
  const [form, setForm] = useState<NotificationPreferences>(defaults);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  const load = useCallback(async () => {
    setStatus({ kind: "loading" });
    try {
      const loaded = await getNotificationPreferences();
      setForm(loaded);
      setDirty(false);
      setStatus({ kind: "ready", message: null, error: null });
    } catch {
      setStatus({ kind: "ready", message: null,
        error: "Could not load your notification preferences. Nothing was changed." });
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  function update<K extends keyof NotificationPreferences>(key: K, value: NotificationPreferences[K]) {
    setForm((current) => ({ ...current, [key]: value }));
    setDirty(true);
    setStatus((current) =>
      current.kind === "ready" ? { ...current, message: null, error: null } : current);
  }

  const problem = validate(form);

  async function onSave() {
    // Never send a combination the client already knows is invalid, so a rejection always means a
    // server-side disagreement rather than something the form could have caught.
    if (problem) {
      setStatus({ kind: "ready", message: null, error: problem });
      return;
    }
    setSaving(true);
    try {
      // Re-read what was stored rather than assuming the save worked, so the form always shows the
      // server's canonical values, including a normalised timezone id.
      const saved = await saveNotificationPreferences(form);
      setForm(saved);
      setDirty(false);
      setStatus({ kind: "ready", message: "Your notification preferences were saved.", error: null });
    } catch {
      setStatus({ kind: "ready", message: null,
        error: "FitTrack could not save your preferences, so nothing was changed. Please try again." });
    } finally {
      setSaving(false);
    }
  }

  if (status.kind === "loading") {
    return (
      <div className="card" style={{ marginBottom: 12 }}>
        <p className="stat-meta">Loading your notification preferences…</p>
      </div>
    );
  }

  return (
    <div className="card" style={{ marginBottom: 12 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 6 }}>
        <Moon size={18} color="#4ade80" />
        <h3 style={{ margin: 0, fontSize: 15, color: "#f0f6fc" }}>Your preferences</h3>
      </div>
      <p className="stat-meta" style={{ margin: "0 0 12px", lineHeight: 1.55 }}>
        These are your choices, kept separate from your browser and your devices. Turning them off
        stops future reminders arriving; it does not unsubscribe this browser or pause any reminder.
      </p>

      <div style={{ display: "grid", gap: 10 }}>
        <Toggle
          label="Reminder notifications"
          hint="Whether FitTrack should send you reminders at all."
          checked={form.reminderNotificationsEnabled}
          disabled={saving}
          onChange={(value) => update("reminderNotificationsEnabled", value)}
        />
        <Toggle
          label="Push notifications"
          hint="Applies to every device signed in to your account, not just this browser."
          checked={form.pushEnabled}
          disabled={saving}
          onChange={(value) => update("pushEnabled", value)}
        />
        <Toggle
          label="Quiet hours"
          hint="Reminders that come due in this window wait until it ends instead of waking you."
          checked={form.quietHoursEnabled}
          disabled={saving}
          onChange={(value) => update("quietHoursEnabled", value)}
        />

        {form.quietHoursEnabled && <QuietHoursFields form={form} saving={saving} update={update} />}
      </div>

      {status.error && (
        <p role="status" style={{ fontSize: 13, color: "#f87171", margin: "12px 0 0" }}>{status.error}</p>
      )}
      {status.message && !status.error && (
        <p role="status" style={{ fontSize: 13, color: "#4ade80", margin: "12px 0 0" }}>{status.message}</p>
      )}

      <div style={{ display: "flex", gap: 8, marginTop: 12, alignItems: "center" }}>
        <button className="btn" onClick={onSave} disabled={saving || !dirty}>
          <Save size={15} /> {saving ? "Saving…" : "Save preferences"}
        </button>
        {problem && (
          <span style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 12, color: "#fbbf24" }}>
            <ShieldAlert size={14} /> {problem}
          </span>
        )}
        {status.message && !status.error && !problem && (
          <span style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 12, color: "#4ade80" }}>
            <Check size={14} /> Saved
          </span>
        )}
      </div>
    </div>
  );
}

function QuietHoursFields({ form, saving, update }: {
  form: NotificationPreferences;
  saving: boolean;
  update: <K extends keyof NotificationPreferences>(key: K, value: NotificationPreferences[K]) => void;
}) {
  return (
    <div style={{ display: "grid", gap: 10, paddingLeft: 12, borderLeft: "2px solid #1e293b" }}>
      <label style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 13 }}>
        <span style={{ color: "#94a3b8" }}>From</span>
        <input className="form-input" type="time" disabled={saving}
          value={form.quietHoursStart ?? ""}
          onChange={(e) => update("quietHoursStart", e.target.value)} />
      </label>
      <label style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 13 }}>
        <span style={{ color: "#94a3b8" }}>Until</span>
        <input className="form-input" type="time" disabled={saving}
          value={form.quietHoursEnd ?? ""}
          onChange={(e) => update("quietHoursEnd", e.target.value)} />
      </label>
      <label style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 13 }}>
        <span style={{ color: "#94a3b8" }}>Timezone</span>
        {/* An explicit field rather than a hidden browser default: the window is a wall-clock range,
            and quietly adopting the browser's zone would move it without telling anyone. */}
        <input className="form-input" type="text" disabled={saving} placeholder="Asia/Kolkata"
          value={form.timezone ?? ""}
          onChange={(e) => update("timezone", e.target.value)} />
      </label>
      {form.quietHoursStart && form.quietHoursEnd && form.quietHoursStart !== form.quietHoursEnd && (
        <p className="stat-meta" style={{ margin: 0 }}>
          Quiet from {form.quietHoursStart} to {form.quietHoursEnd}
          {form.timezone ? ` (${form.timezone})` : ""}
          {form.quietHoursStart > form.quietHoursEnd
            ? " - spans midnight, so this covers the small hours too."
            : "."}
        </p>
      )}
    </div>
  );
}

function Toggle({ label, hint, checked, disabled, onChange }: {
  label: string;
  hint: string;
  checked: boolean;
  disabled: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <label style={{ display: "flex", gap: 10, alignItems: "flex-start", cursor: disabled ? "default" : "pointer" }}>
      <input type="checkbox" checked={checked} disabled={disabled}
        onChange={(e) => onChange(e.target.checked)} style={{ marginTop: 3 }} />
      <span>
        <span style={{ display: "block", fontSize: 13, color: "#e2e8f0" }}>{label}</span>
        <span style={{ display: "block", fontSize: 12, color: "#64748b" }}>{hint}</span>
      </span>
    </label>
  );
}
