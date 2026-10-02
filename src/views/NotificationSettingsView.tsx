import { useCallback, useEffect, useState } from "react";
import { Bell, BellOff, Check, Info, ShieldAlert, Smartphone } from "lucide-react";

import { SectionHeader } from "../components/ui";
import {
  disablePush,
  enablePush,
  readPushSnapshot,
  type DisableResult,
  type EnableResult,
  type PushSnapshot,
} from "../lib/push/pushSubscription";
import { NotificationPreferencesPanel } from "../components/NotificationPreferencesPanel";

/**
 * The notification settings centre.
 *
 * <p>Purely diagnostic plus two actions. It describes the three independent facts a user actually
 * needs - does this browser support notifications, has the browser granted permission, and has this
 * device registered a subscription - and never merges them into a single "on/off" claim the evidence
 * does not support.
 *
 * <p>No push endpoint, hostname, or fragment of one is rendered anywhere on this screen. The endpoint
 * is a per-installation secret the server needs for delivery and a person has no use for it, so
 * devices appear as a plain count with no invented names.
 */

type Props = { onBack?: () => void };

type ViewState =
  | { kind: "loading" }
  | { kind: "ready"; snapshot: PushSnapshot }
  | { kind: "error"; message: string };

/** Plain wording for a permission value, never the raw browser string alone. */
function permissionLabel(permission: PushSnapshot["permission"]): string {
  switch (permission) {
    case "granted": return "Allowed";
    case "denied": return "Blocked";
    case "default": return "Not yet asked";
    default: return "Not available in this browser";
  }
}

function permissionDetail(permission: PushSnapshot["permission"]): string {
  switch (permission) {
    case "granted":
      return "This browser allows AI FitTrack to show notifications.";
    case "denied":
      return "Your browser is blocking notifications for this site. Only your browser's site settings can undo this - AI FitTrack cannot prompt for it again.";
    case "default":
      return "AI FitTrack has not asked this browser for permission yet.";
    default:
      return "This browser does not provide the notification API.";
  }
}

function otherDeviceLabel(count: number): string {
  if (count === 0) return "No other devices";
  return count === 1 ? "1 other device" : `${count} other devices`;
}

/**
 * Whether offering the enable button would be honest.
 *
 * <p>Only offered where the preconditions genuinely hold. In particular never when permission is
 * blocked: a button that cannot succeed is a dead end, and re-prompting a browser that has already
 * refused is treated as untrustworthy.
 */
function canEnable(snapshot: PushSnapshot): boolean {
  if (!snapshot.browserSupported) return false;
  if (!snapshot.secureContext) return false;
  if (!snapshot.serverConfigured) return false;
  if (snapshot.permission !== "granted" && snapshot.permission !== "default") return false;
  return !snapshot.thisDeviceSubscribed;
}

function blockedReason(snapshot: PushSnapshot): string {
  if (!snapshot.browserSupported) return "This browser cannot receive notifications.";
  if (!snapshot.secureContext) return "Open AI FitTrack over HTTPS to enable notifications.";
  if (snapshot.permission === "denied") return "Notifications are blocked for this site. Re-allow them in your browser's site settings.";
  if (!snapshot.serverConfigured) return "Notifications are not available on this server right now.";
  return "This device is already subscribed.";
}

export default function NotificationSettingsView({ onBack }: Props) {
  const [state, setState] = useState<ViewState>({ kind: "loading" });
  const [busy, setBusy] = useState<"enable" | "disable" | null>(null);
  const [result, setResult] = useState<EnableResult | DisableResult | null>(null);

  /**
   * Re-reads the snapshot after any action.
   *
   * <p>Every state change is followed by a fresh read, so the screen only ever shows what the server
   * and the browser currently report. It never assumes an action succeeded because a button was
   * pressed - that is what stops a rejected subscription being shown as enabled.
   */
  const reload = useCallback(async () => {
    try {
      setState({ kind: "ready", snapshot: await readPushSnapshot() });
    } catch {
      setState({ kind: "error", message: "Could not load notification settings. Please try again." });
    }
  }, []);

  // Read-only on mount: never prompts, never subscribes.
  useEffect(() => {
    let active = true;
    setState({ kind: "loading" });
    setResult(null);
    readPushSnapshot()
      .then((snapshot) => { if (active) setState({ kind: "ready", snapshot }); })
      .catch(() => { if (active) setState({ kind: "error", message: "Could not load notification settings. Please try again." }); });
    return () => { active = false; };
  }, []);

  async function onEnable() {
    setBusy("enable");
    setResult(null);
    try {
      setResult(await enablePush());
    } catch {
      setResult({ ok: false, reason: "network-error", message: "Could not reach AI FitTrack. Nothing was changed." });
    } finally {
      setBusy(null);
      await reload();
    }
  }

  async function onDisable() {
    setBusy("disable");
    setResult(null);
    try {
      setResult(await disablePush());
    } catch {
      setResult({ ok: false, serverRemoved: false, browserCleaned: false,
        message: "Could not reach AI FitTrack, so this subscription may still be active." });
    } finally {
      setBusy(null);
      await reload();
    }
  }

  return (
    <div className="flex-col" style={{ animation: "fadeInUp 0.4s ease both" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: 12 }}>
        <SectionHeader
          title="Notification settings"
          subtitle="What this browser can do, and what AI FitTrack has recorded for it"
        />
        {onBack && (
          <button className="btn btn-secondary" onClick={onBack}>Back to profile</button>
        )}
      </div>

      {state.kind === "loading" && (
        <p className="stat-meta">Loading notification settings…</p>
      )}

      {state.kind === "error" && (
        <div className="form-error" role="alert"><span>{state.message}</span></div>
      )}

      {state.kind === "ready" && <SnapshotBody
        snapshot={state.snapshot}
        busy={busy}
        result={result}
        onEnable={() => void onEnable()}
        onDisable={() => void onDisable()}
      />}
    </div>
  );
}

/**
 * The three fact groups, kept in separate cards on purpose.
 *
 * <p>Grouping them is what stops "the server has push configured" from reading as "this device will
 * get notifications". Each card answers exactly one question.
 */
function SnapshotBody({ snapshot, busy, result, onEnable, onDisable }: {
  snapshot: PushSnapshot;
  busy: "enable" | "disable" | null;
  result: EnableResult | DisableResult | null;
  onEnable: () => void;
  onDisable: () => void;
}) {
  return (
    <>
      {snapshot.error && (
        <div className="form-error" role="status" style={{ marginBottom: 12 }}>
          <span>{snapshot.error}</span>
        </div>
      )}

      {/* Browser support and permission: facts about this browser alone. */}
      <div className="card" style={{ marginBottom: 12 }}>
        <CardTitle icon={<Smartphone size={18} color="#4ade80" />} title="Browser notifications" />
        <dl style={{ display: "grid", gap: 10, margin: "10px 0 0" }}>
          <Fact
            label="Browser support"
            value={snapshot.browserSupported ? "Supported" : "Not supported"}
            good={snapshot.browserSupported}
          />
          {snapshot.browserSupported && !snapshot.secureContext && (
            <Fact
              label="Secure connection"
              value="Required"
              good={false}
              detail="The Push API is unavailable outside a secure context. localhost counts as secure during development."
            />
          )}
          <Fact
            label="Permission"
            value={permissionLabel(snapshot.permission)}
            good={snapshot.permission === "granted"}
            detail={permissionDetail(snapshot.permission)}
          />
        </dl>
      </div>

      {/* Server configuration: a fact about the deployment, kept apart from any device. */}
      <div className="card" style={{ marginBottom: 12 }}>
        <CardTitle
          icon={<Info size={18} color={snapshot.serverConfigured ? "#4ade80" : "#94a3b8"} />}
          title="Push notifications"
        />
        <dl style={{ display: "grid", gap: 10, margin: "10px 0 0" }}>
          <Fact
            label="Server configuration"
            value={snapshot.serverConfigured ? "Configured" : "Not configured"}
            good={snapshot.serverConfigured}
            detail={snapshot.serverConfigured
              ? "This AI FitTrack deployment is set up to send push notifications."
              : "This AI FitTrack deployment has push notifications switched off. This is a server setting, not something you can change."}
          />
        </dl>
      </div>

      {/* This device, plus a count of the user's other registrations. No names, no endpoints. */}
      <div className="card" style={{ marginBottom: 12 }}>
        <CardTitle
          icon={<Bell size={18} color={snapshot.thisDeviceSubscribed ? "#4ade80" : "#94a3b8"} />}
          title="Device status"
        />
        <dl style={{ display: "grid", gap: 10, margin: "10px 0 0" }}>
          <Fact
            label="This device"
            value={snapshot.thisDeviceSubscribed ? "Subscribed" : "Not subscribed"}
            good={snapshot.thisDeviceSubscribed}
          />
          <Fact
            label="Other devices"
            value={otherDeviceLabel(snapshot.otherDeviceCount)}
            good={undefined}
            detail="Turning notifications off below affects this device only. Devices you registered separately keep receiving reminders."
          />
        </dl>
      </div>

      {result && (
        <p
          role="status"
          style={{ fontSize: 13, margin: "0 0 12px", color: result.ok ? "#4ade80" : "#f87171" }}
        >
          {result.message}
        </p>
      )}

      {/* Phase 19: the user's own decisions, kept apart from every fact observed above. Turning a
          preference off here never unsubscribes this browser, and never disables a reminder - those
          are separate settings with separate controls, one row lower down. */}
      <NotificationPreferencesPanel />

      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center" }}>
        {canEnable(snapshot) && (
          <button
            className="btn"
            onClick={onEnable}
            disabled={busy !== null}
            style={{ display: "inline-flex", alignItems: "center", gap: 8 }}
          >
            <Bell size={15} />
            {busy === "enable" ? "Enabling…" : "Enable notifications on this device"}
          </button>
        )}
        {snapshot.thisDeviceSubscribed && (
          <button
            className="btn btn-secondary"
            onClick={onDisable}
            disabled={busy !== null}
            style={{ display: "inline-flex", alignItems: "center", gap: 8 }}
          >
            <BellOff size={15} />
            {busy === "disable" ? "Turning off…" : "Turn off on this device"}
          </button>
        )}
        {!canEnable(snapshot) && !snapshot.thisDeviceSubscribed && (
          <p className="stat-meta" style={{ display: "flex", alignItems: "center", gap: 6, margin: 0 }}>
            <ShieldAlert size={14} />
            {blockedReason(snapshot)}
          </p>
        )}
      </div>
    </>
  );
}

function CardTitle({ icon, title }: { icon: React.ReactNode; title: string }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
      {icon}
      <h3 style={{ margin: 0, fontSize: 15, color: "#f0f6fc" }}>{title}</h3>
    </div>
  );
}

function Fact({ label, value, good, detail }: {
  label: string;
  value: string;
  good?: boolean;
  detail?: string;
}) {
  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span style={{ color: "#94a3b8", fontSize: 13, minWidth: 150 }}>{label}</span>
        <span style={{ color: "#e2e8f0", fontSize: 13, display: "inline-flex", alignItems: "center", gap: 6 }}>
          {good === true && <Check size={13} color="#4ade80" />}
          {good === false && <ShieldAlert size={13} color="#f87171" />}
          {value}
        </span>
      </div>
      {detail && (
        <p style={{ color: "#64748b", fontSize: 12, margin: "4px 0 0 150px", lineHeight: 1.5 }}>{detail}</p>
      )}
    </div>
  );
}
