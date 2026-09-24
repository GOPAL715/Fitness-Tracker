import { useState } from "react";
import {
  Footprints,
  Moon,
  Flame,
  Droplets,
  HeartPulse,
  Check,
  ChevronRight,
  Sparkles,
  Zap,
  Info,
  Lightbulb,
  TrendingUp,
  AlertTriangle,
  Plus,
  Timer,
  Award,
  Utensils,
} from "lucide-react";
import type { DailyMetric, Workout, Profile, Meal } from "../lib/domain";
import type { Habit, HabitLog } from "../lib/types";
import {
  buildInsights,
  buildRecommendation,
  buildScoreCards,
  computeReadiness,
  computeStreak,
  computeWeekProgress,
  computeTrainingLoad,
} from "../lib/insights";
import { ProgressRing, StatTile, EmptyState } from "../components/ui";
import { todayNutrition, habitsCompletedToday } from "../lib/dailySummary";
import { formatLongDate, todayISO, round } from "../lib/utils";

type Props = {
  profile: Profile | null;
  todayMetric: DailyMetric | null;
  recent: DailyMetric[];
  workouts: Workout[];
  meals: Meal[];
  habits: Habit[];
  habitLogs: HabitLog[];
  onRefresh: () => void;
  onLogWater: (oz: number) => Promise<void>;
  onOpenWorkouts: () => void;
  onOpenHabits: () => void;
  onOpenGoals: () => void;
  onQuickLog?: () => void;
};

const toneIcon = {
  positive: <TrendingUp size={16} color="#4ade80" />,
  caution: <AlertTriangle size={16} color="#fb923c" />,
  neutral: <Info size={16} color="#94a3b8" />,
};

const recoIcon = {
  train: <Zap size={20} color="#38bdf8" />,
  recover: <Moon size={20} color="#a78bfa" />,
  move: <Footprints size={20} color="#4ade80" />,
  eat: <Droplets size={20} color="#22d3ee" />,
};

export default function TodayView({
  profile,
  todayMetric,
  recent,
  meals,
  habits,
  habitLogs,
  onOpenHabits,
  onOpenGoals,
  workouts,
  onLogWater,
  onQuickLog,
  onOpenWorkouts,
}: Props) {
  const [loggingWater, setLoggingWater] = useState(false);
  const readiness = computeReadiness(todayMetric, recent);
  const scores = buildScoreCards(todayMetric, recent, workouts, readiness, profile);
  const insights = buildInsights(todayMetric, recent, workouts, profile);
  const reco = buildRecommendation(todayMetric, workouts, profile, readiness);
  const week = computeWeekProgress(workouts, profile);
  const streak = computeStreak(workouts);
  const load = computeTrainingLoad(workouts);
  const todayWorkouts = workouts.filter((w) => w.workout_date === todayISO());
  const firstName = profile?.display_name.split(" ")[0] ?? "there";
  const nutrition = todayNutrition(meals);
  const habitsDone = habitsCompletedToday(habits, habitLogs);

  async function handleWater() {
    setLoggingWater(true);
    await onLogWater(8);
    setLoggingWater(false);
  }

  const readinessColor = readiness >= 75 ? "#4ade80" : readiness >= 55 ? "#38bdf8" : "#fb923c";

  return (
    <div className="flex-col" style={{ animation: "fadeInUp 0.4s ease both" }}>
      <div>
        <h1 className="section-title" style={{ fontSize: 28 }}>
          Good to see you, {firstName}
        </h1>
        <p className="section-sub">{formatLongDate(todayISO())} · Your daily readiness and plan</p>
      </div>

      {/* Hero readiness */}
      <div className="card" style={{ padding: 28, background: "linear-gradient(135deg, rgba(14,165,233,0.10), rgba(15,23,42,0.4))" }}>
        <div className="ring-wrap" style={{ flexWrap: "wrap" }}>
          <ProgressRing
            value={readiness}
            max={100}
            size={150}
            stroke={13}
            color={readinessColor}
            sublabel="READINESS"
          />
          <div style={{ flex: 1, minWidth: 260 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 8 }}>
              <Sparkles size={18} color="#38bdf8" />
              <span style={{ fontSize: 13, fontWeight: 700, color: "#38bdf8", textTransform: "uppercase", letterSpacing: 1 }}>
                Daily briefing
              </span>
            </div>
            <p style={{ fontSize: 15, lineHeight: 1.6, color: "#cbd5e1", margin: "0 0 16px" }}>
              {scores[0].explanation}
            </p>
            <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
              <button className="btn" onClick={onQuickLog}>
                <Plus size={16} /> Log today's session
              </button>
              <button
                className="btn btn-secondary"
                onClick={handleWater}
                disabled={loggingWater}
              >
                <Droplets size={16} /> {loggingWater ? "Logging…" : "Log 8 oz water"}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Recommendation */}
      <div className="reco">
        <div className="reco-icon">{recoIcon[reco.kind]}</div>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 16, fontWeight: 800, color: "#f0f6fc", marginBottom: 4 }}>{reco.headline}</div>
          <p className="reco-text" style={{ margin: 0 }}>{reco.body}</p>
        </div>
        <button
          className="btn btn-secondary btn-sm"
          style={{ alignSelf: "center" }}
          onClick={reco.kind === "train" ? onOpenWorkouts : onQuickLog}
        >
          {reco.action} <ChevronRight size={14} />
        </button>
      </div>

      {/* Today's vital stats */}
      <div>
        <h3 style={{ fontSize: 16, fontWeight: 700, color: "#cbd5e1", margin: "0 0 12px" }}>Today's numbers</h3>
        <div className="grid-4">
          <StatTile
            icon={<Footprints size={18} />}
            label="Steps"
            value={(todayMetric?.steps ?? 0).toLocaleString()}
            color="#4ade80"
            barValue={todayMetric?.steps ?? 0}
            barMax={profile?.step_target ?? 10000}
            meta={`${Math.round(((todayMetric?.steps ?? 0) / (profile?.step_target ?? 10000)) * 100)}% of daily target`}
          />
          <StatTile
            icon={<Moon size={18} />}
            label="Sleep"
            value={todayMetric?.sleep_hours ?? 0}
            unit="hrs"
            color="#a78bfa"
            barValue={todayMetric?.sleep_hours ?? 0}
            barMax={profile?.sleep_target_hours ?? 8}
            meta={`Target ${profile?.sleep_target_hours ?? 8} hrs`}
          />
          <StatTile
            icon={<Flame size={18} />}
            label="Calories burned"
            value={(todayMetric?.calories_burned ?? 0).toLocaleString()}
            color="#fb923c"
            meta={`${todayMetric?.active_minutes ?? 0} active minutes`}
          />
          <StatTile
            icon={<Droplets size={18} />}
            label="Water"
            value={todayMetric?.water_oz ?? 0}
            unit={`/ ${profile?.water_target_oz ?? 100} oz`}
            color="#22d3ee"
            barValue={todayMetric?.water_oz ?? 0}
            barMax={profile?.water_target_oz ?? 100}
          />
        </div>
      </div>

      {/* Health scores */}
      <div>
        <h3 style={{ fontSize: 16, fontWeight: 700, color: "#cbd5e1", margin: "0 0 12px" }}>Health scores</h3>
        <div className="grid-3">
          {scores.slice(1).map((s) => (
            <div className="card" key={s.label}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", marginBottom: 10 }}>
                <span className="stat-label">{s.label}</span>
                <span style={{ fontSize: 24, fontWeight: 800, color: "#f0f6fc" }}>
                  {s.value}
                  <span style={{ fontSize: 12, color: "#64748b", fontWeight: 600 }}>/{s.max}</span>
                </span>
              </div>
              <div className="stat-bar" style={{ marginBottom: 10 }}>
                <div
                  className="stat-bar-fill"
                  style={{
                    width: `${(s.value / s.max) * 100}%`,
                    background: s.value >= 70 ? "#4ade80" : s.value >= 45 ? "#38bdf8" : "#fb923c",
                  }}
                />
              </div>
              <p style={{ fontSize: 12, color: "#94a3b8", margin: 0, lineHeight: 1.5 }}>{s.explanation}</p>
            </div>
          ))}
        </div>
      </div>

      {/* Week + streak */}
      <div className="grid-2">
        <div className="card">
          <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 14 }}>
            <Award size={18} color="#fbbf24" />
            <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>Weekly goal</span>
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 20 }}>
            <ProgressRing
              value={week.count}
              max={week.target}
              size={110}
              stroke={10}
              color="#38bdf8"
              label={`${week.count}/${week.target}`}
              sublabel="SESSIONS"
            />
            <div style={{ flex: 1 }}>
              <p style={{ fontSize: 13, color: "#94a3b8", margin: "0 0 10px", lineHeight: 1.5 }}>
                {week.count >= week.target
                  ? "Weekly session target complete. Anything else this week is a bonus."
                  : `${week.target - week.count} more session${week.target - week.count === 1 ? "" : "s"} to hit this week's target.`}
              </p>
              <div className="stat-bar" style={{ marginBottom: 6 }}>
                <div
                  className="stat-bar-fill"
                  style={{ width: `${Math.min(100, (week.minutes / week.minuteTarget) * 100)}%`, background: "#4ade80" }}
                />
              </div>
              <span className="stat-meta">
                {week.minutes} of {week.minuteTarget} training minutes
              </span>
            </div>
          </div>
        </div>

        <div className="card">
          <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 14 }}>
            <Flame size={18} color="#fb923c" />
            <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>Momentum</span>
          </div>
          <div className="grid-2" style={{ gap: 12 }}>
            <div>
              <div style={{ fontSize: 32, fontWeight: 800, color: "#f0f6fc", letterSpacing: -1 }}>{streak}</div>
              <span className="stat-meta">day active streak</span>
            </div>
            <div>
              <div style={{ fontSize: 32, fontWeight: 800, color: "#f0f6fc", letterSpacing: -1 }}>{load.load}</div>
              <span className="stat-meta">load points this week · {load.label.toLowerCase()}</span>
            </div>
          </div>
        </div>
      </div>

      {/* Today's sessions */}
      <div>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
          <h3 style={{ fontSize: 16, fontWeight: 700, color: "#cbd5e1", margin: 0 }}>Today's sessions</h3>
          <button className="btn btn-secondary btn-sm" onClick={onOpenWorkouts}>
            View all <ChevronRight size={14} />
          </button>
        </div>
        {todayWorkouts.length === 0 ? (
          <div className="card">
            <EmptyState
              icon={<Timer size={28} color="#64748b" />}
              title="Nothing logged yet today"
              message="Log a session to keep your streak and weekly progress moving."
              action={
                <button className="btn btn-sm" onClick={onQuickLog}>
                  <Plus size={14} /> Log a workout
                </button>
              }
            />
          </div>
        ) : (
          <div className="flex-col" style={{ gap: 10 }}>
            {todayWorkouts.map((w) => (
              <div className="workout-item" key={w.id}>
                <div className="workout-icon" style={{ background: "rgba(56,189,248,0.14)", color: "#38bdf8" }}>
                  {w.completed ? <Check size={20} /> : <Timer size={20} />}
                </div>
                <div className="workout-info">
                  <div className="workout-title">{w.title}</div>
                  <div className="workout-meta">
                    <span>{w.workout_type}</span>
                    <span>{w.duration_minutes} min</span>
                    <span>{w.calories_burned} cal</span>
                    {w.distance_miles ? <span>{round(w.distance_miles, 2)} mi</span> : null}
                  </div>
                </div>
                <span className={`badge ${w.completed ? "badge-done" : "badge-pending"}`}>
                  {w.completed ? "Completed" : "Planned"}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Nutrition + habits snapshot */}
      <div className="grid-2">
        <div className="card">
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 14 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <Utensils size={18} color="#fb923c" />
              <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>Nutrition today</span>
            </div>
            <span className="stat-meta">{nutrition.calories} / {profile?.calorie_target ?? 2400} kcal</span>
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
            <NutritionBar label="Protein" value={nutrition.protein} target={profile?.protein_target_g ?? 150} color="#38bdf8" />
            <NutritionBar label="Carbs" value={nutrition.carbs} target={Math.round((profile?.calorie_target ?? 2400) * 0.45 / 4)} color="#4ade80" />
            <NutritionBar label="Fat" value={nutrition.fat} target={Math.round((profile?.calorie_target ?? 2400) * 0.28 / 9)} color="#a78bfa" />
          </div>
        </div>

        <div className="card">
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 14 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <Award size={18} color="#4ade80" />
              <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>Habits today</span>
            </div>
            <button className="btn btn-secondary btn-sm" onClick={onOpenHabits}>
              Open habits <ChevronRight size={12} />
            </button>
          </div>
          {habits.length === 0 ? (
            <p style={{ fontSize: 13, color: "#94a3b8", margin: 0, lineHeight: 1.55 }}>
              Add a few daily habits to build the small routines that drive results.
            </p>
          ) : (
            <>
              <div style={{ display: "flex", alignItems: "baseline", gap: 8, marginBottom: 10 }}>
                <span style={{ fontSize: 30, fontWeight: 800, color: "#f0f6fc", letterSpacing: -1 }}>{habitsDone}</span>
                <span className="stat-meta">of {habits.length} completed</span>
              </div>
              <div className="stat-bar" style={{ marginBottom: 12 }}>
                <div className="stat-bar-fill" style={{ width: `${(habitsDone / habits.length) * 100}%`, background: "#4ade80" }} />
              </div>
              <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
                {habits.map((h) => {
                  const done = habitLogs.some((l) => l.habit_id === h.id && l.log_date === todayISO() && l.completed);
                  return (
                    <span key={h.id} className={`chip ${done ? "chip-on" : ""}`} style={{ cursor: "default" }}>
                      {done ? "✓ " : ""}{h.name}
                    </span>
                  );
                })}
              </div>
            </>
          )}
          <button className="btn btn-secondary btn-sm" style={{ marginTop: 14 }} onClick={onOpenGoals}>
            View goals <ChevronRight size={12} />
          </button>
        </div>
      </div>

      {/* Insights */}
      <div>
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 12 }}>
          <Lightbulb size={18} color="#fbbf24" />
          <h3 style={{ fontSize: 16, fontWeight: 700, color: "#cbd5e1", margin: 0 }}>What your data says</h3>
        </div>
        {insights.length === 0 ? (
          <div className="card">
            <p style={{ fontSize: 14, color: "#94a3b8", margin: 0 }}>
              Keep logging for a few more days and FitTrack AI will start surfacing trends and explanations here.
            </p>
          </div>
        ) : (
          <div className="grid-2">
            {insights.map((ins) => (
              <div className="card" key={ins.title}>
                <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 8 }}>
                  {toneIcon[ins.tone]}
                  <span style={{ fontSize: 14, fontWeight: 700, color: "#f0f6fc" }}>{ins.title}</span>
                </div>
                <p style={{ fontSize: 13, color: "#94a3b8", margin: 0, lineHeight: 1.55 }}>{ins.detail}</p>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Heart metrics */}
      <div className="card">
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 16 }}>
          <HeartPulse size={18} color="#f87171" />
          <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>Heart and recovery markers</span>
        </div>
        <div className="grid-3">
          <div>
            <div style={{ fontSize: 24, fontWeight: 800, color: "#f0f6fc" }}>
              {todayMetric?.resting_heart_rate ?? 0} <span className="stat-unit">bpm</span>
            </div>
            <span className="stat-meta">Resting heart rate</span>
          </div>
          <div>
            <div style={{ fontSize: 24, fontWeight: 800, color: "#f0f6fc" }}>
              {todayMetric?.hrv ?? 0} <span className="stat-unit">ms</span>
            </div>
            <span className="stat-meta">Heart rate variability</span>
          </div>
          <div>
            <div style={{ fontSize: 24, fontWeight: 800, color: "#f0f6fc" }}>
              {todayMetric?.stress_level ?? 0} <span className="stat-unit">/ 10</span>
            </div>
            <span className="stat-meta">Stress level</span>
          </div>
        </div>
      </div>
    </div>
  );
}

function NutritionBar({ label, value, target, color }: { label: string; value: number; target: number; color: string }) {
  const pct = target > 0 ? Math.min(100, Math.round((value / target) * 100)) : 0;
  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 5 }}>
        <span style={{ fontSize: 13, color: "#cbd5e1", fontWeight: 600 }}>{label}</span>
        <span className="stat-meta">{Math.round(value)} / {target} g</span>
      </div>
      <div className="stat-bar">
        <div className="stat-bar-fill" style={{ width: `${pct}%`, background: color }} />
      </div>
    </div>
  );
}


