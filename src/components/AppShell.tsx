import { Activity, AlertTriangle, Check, CloudOff, LogOut, Menu, RefreshCw, X } from "lucide-react";
import { TABS, type Tab } from "../features/navigation/tabs";
import { label as syncLabel, type SyncStatus } from "../lib/offline/syncStatus";

/** Full-screen loading state used while starting up or fetching. */
export function LoadingScreen({ message }: { message: string }) {
  return (
    <div className="app">
      <div className="loading-wrap" style={{ minHeight: "100vh" }}>
        <div className="spinner" />
        <p className="loading-text">{message}</p>
      </div>
    </div>
  );
}

/** Full-screen failure state with a retry action. */
export function ErrorScreen({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="app">
      <div className="loading-wrap" style={{ minHeight: "100vh" }}>
        <div className="empty-icon" style={{ background: "rgba(239,68,68,0.12)" }}>
          <AlertTriangle size={28} color="#f87171" />
        </div>
        <p style={{ color: "#fca5a5", fontSize: 15, maxWidth: 340, textAlign: "center", lineHeight: 1.6 }}>
          {message}
        </p>
        <button className="btn" onClick={onRetry}>
          <RefreshCw size={16} /> Try again
        </button>
      </div>
    </div>
  );
}

type AppShellProps = {
  activeTab: Tab;
  onSelectTab: (tab: Tab) => void;
  menuOpen: boolean;
  onToggleMenu: () => void;
  email: string | undefined;
  onSignOut: () => void;
  /** Offline / sync indicator state, rendered in the header. */
  syncStatus?: SyncStatus;
  children: React.ReactNode;
};

/** Compact connectivity and pending-sync indicator shown in the header. */
function SyncIndicator({ status }: { status: SyncStatus }) {
  const offline = status.state === 'offline';
  return (
    <span
      className="sync-indicator"
      data-testid="sync-indicator"
      data-state={status.state}
      role="status"
      aria-live="polite"
      title={'Sync status: ' + status.state}
      style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12,
        color: offline ? '#fca5a5' : status.state === 'online' ? '#94a3b8' : '#fbbf24' }}
    >
      {offline && <CloudOff size={13} aria-hidden="true" />}
      <span>{syncLabel(status)}</span>
    </span>
  );
}

/** Header, navigation, footer and content frame. */
export function AppShell({
  activeTab,
  onSelectTab,
  menuOpen,
  onToggleMenu,
  email,
  onSignOut,
  syncStatus,
  children,
}: AppShellProps) {
  return (
    <div className="app">
      <header className="header">
        <div className="brand">
          <div className="logo">
            <Activity size={22} color="#38bdf8" />
          </div>
          <span className="brand-name">
            FitTrack <span style={{ color: "#38bdf8" }}>AI</span>
          </span>
        </div>

        <nav className="nav">
          {TABS.map((t) => (
            <button
              key={t.id}
              onClick={() => onSelectTab(t.id)}
              className={`nav-btn ${activeTab === t.id ? "nav-btn-active" : ""}`}
              aria-current={activeTab === t.id ? "page" : undefined}
            >
              <t.icon size={17} />
              <span>{t.label}</span>
            </button>
          ))}
        </nav>

        <div className="header-actions">
          {syncStatus && <SyncIndicator status={syncStatus} />}
          <button
            className="menu-btn"
            onClick={onToggleMenu}
            aria-label="Toggle menu"
            aria-expanded={menuOpen}
          >
            {menuOpen ? <X size={22} /> : <Menu size={22} />}
          </button>
          <button className="icon-btn" onClick={onSignOut} title="Sign out" aria-label="Sign out">
            <LogOut size={17} color="#94a3b8" />
          </button>
        </div>
      </header>

      {menuOpen && (
        <div className="mobile-nav">
          {TABS.map((t) => (
            <button
              key={t.id}
              onClick={() => onSelectTab(t.id)}
              className={`mobile-nav-btn ${activeTab === t.id ? "nav-btn-active" : ""}`}
            >
              <t.icon size={18} />
              <span>{t.label}</span>
            </button>
          ))}
        </div>
      )}

      <main className="main">{children}</main>

      <footer className="footer">
        <span className="footer-text">
          <Check size={12} style={{ verticalAlign: "middle", marginRight: 6 }} />
          Signed in as {email}
        </span>
      </footer>
    </div>
  );
}


