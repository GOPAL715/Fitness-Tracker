import type { ReactNode } from "react";

type RingProps = {
  value: number;
  max: number;
  size?: number;
  stroke?: number;
  color?: string;
  label?: string;
  sublabel?: string;
};

export function ProgressRing({
  value,
  max,
  size = 140,
  stroke = 12,
  color = "#38bdf8",
  label,
  sublabel,
}: RingProps) {
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const pct = max > 0 ? Math.min(1, Math.max(0, value / max)) : 0;
  const offset = circumference * (1 - pct);
  const center = size / 2;

  return (
    <div style={{ position: "relative", width: size, height: size, flexShrink: 0 }}>
      <svg width={size} height={size} className="ring-svg">
        <circle
          cx={center}
          cy={center}
          r={radius}
          fill="none"
          strokeWidth={stroke}
          className="ring-track"
        />
        <circle
          cx={center}
          cy={center}
          r={radius}
          fill="none"
          strokeWidth={stroke}
          stroke={color}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={offset}
          className="ring-fill"
        />
      </svg>
      <div
        style={{
          position: "absolute",
          inset: 0,
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        <span style={{ fontSize: size * 0.22, fontWeight: 800, color: "#f0f6fc", letterSpacing: -1 }}>
          {label ?? Math.round(value)}
        </span>
        {sublabel && (
          <span style={{ fontSize: 11, color: "#94a3b8", fontWeight: 600, marginTop: 2 }}>{sublabel}</span>
        )}
      </div>
    </div>
  );
}

type MiniBarProps = {
  value: number;
  max: number;
  color?: string;
};

export function MiniBar({ value, max, color = "#38bdf8" }: MiniBarProps) {
  const pct = max > 0 ? Math.min(100, (value / max) * 100) : 0;
  return (
    <div className="stat-bar">
      <div className="stat-bar-fill" style={{ width: `${pct}%`, background: color }} />
    </div>
  );
}

type ChartProps = {
  data: { label: string; value: number }[];
  color?: string;
  unit?: string;
  height?: number;
};

export function BarChart({ data, color = "#38bdf8", unit = "", height = 150 }: ChartProps) {
  const max = Math.max(...data.map((d) => d.value), 1);
  return (
    <div className="chart-wrap" style={{ height }}>
      {data.map((d, i) => (
        <div className="chart-bar-col" key={`${d.label}-${i}`}>
          <span className="chart-value">{d.value}{unit}</span>
          <div
            className="chart-bar"
            style={{
              height: `${Math.max(4, (d.value / max) * (height - 50))}px`,
              background: `linear-gradient(180deg, ${color}, ${color}66)`,
            }}
          />
          <span className="chart-label">{d.label}</span>
        </div>
      ))}
    </div>
  );
}

type ModalProps = {
  title: string;
  onClose: () => void;
  children: ReactNode;
};

export function Modal({ title, onClose, children }: ModalProps) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2 className="modal-title">{title}</h2>
        {children}
      </div>
    </div>
  );
}

type EmptyStateProps = {
  icon: ReactNode;
  title: string;
  message: string;
  action?: ReactNode;
};

export function EmptyState({ icon, title, message, action }: EmptyStateProps) {
  return (
    <div className="empty-state">
      <div className="empty-icon">{icon}</div>
      <h3 style={{ color: "#cbd5e1", fontSize: 16, fontWeight: 700, margin: "0 0 6px" }}>{title}</h3>
      <p style={{ fontSize: 14, margin: "0 0 16px", maxWidth: 320, marginLeft: "auto", marginRight: "auto", lineHeight: 1.5 }}>
        {message}
      </p>
      {action}
    </div>
  );
}

export function SectionHeader({ title, subtitle }: { title: string; subtitle?: string }) {
  return (
    <div style={{ marginBottom: 20 }}>
      <h2 className="section-title">{title}</h2>
      {subtitle && <p className="section-sub">{subtitle}</p>}
    </div>
  );
}

export function StatTile({
  icon,
  label,
  value,
  unit,
  color,
  barValue,
  barMax,
  meta,
}: {
  icon: ReactNode;
  label: string;
  value: string | number;
  unit?: string;
  color: string;
  barValue?: number;
  barMax?: number;
  meta?: string;
}) {
  return (
    <div className="stat-tile">
      <div className="stat-tile-top">
        <span className="stat-label">{label}</span>
        <div className="stat-icon" style={{ background: `${color}22`, color }}>
          {icon}
        </div>
      </div>
      <div>
        <span className="stat-value">{value}</span>
        {unit && <span className="stat-unit"> {unit}</span>}
      </div>
      {barValue !== undefined && barMax !== undefined && <MiniBar value={barValue} max={barMax} color={color} />}
      {meta && <span className="stat-meta">{meta}</span>}
    </div>
  );
}


