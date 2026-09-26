import { useState } from "react";
import { useAuth } from "./lib/auth";
import { useSyncStatus } from "./lib/offline/useSyncStatus";
import { useAppData } from "./features/appData/useAppData";
import { DEFAULT_TAB, type Tab } from "./features/navigation/tabs";
import { AppShell, ErrorScreen, LoadingScreen } from "./components/AppShell";
import AuthScreen from "./views/AuthScreen";
import OnboardingScreen from "./views/OnboardingScreen";
import TodayView from "./views/TodayView";
import WorkoutsView from "./views/WorkoutsView";
import ProgressView from "./views/ProgressView";
import NutritionView from "./views/NutritionView";
import HabitsView from "./views/HabitsView";
import GoalsView from "./views/GoalsView";
import CalendarView from "./views/CalendarView";
import ProfileView from "./views/ProfileView";

/**
 * Application composition root.
 *
 * Responsibilities are deliberately limited to: deciding which screen to show,
 * holding the current tab, and wiring loaded data into feature views. Session
 * state comes from `useAuth`, data comes from `useAppData`, navigation comes
 * from the tab configuration, and presentation comes from `AppShell`.
 */
export default function App() {
  const { user, loading: authLoading, signOut } = useAuth();
  const app = useAppData();
  const sync = useSyncStatus();

  const [tab, setTab] = useState<Tab>(DEFAULT_TAB);
  const [menuOpen, setMenuOpen] = useState(false);

  function selectTab(next: Tab) {
    setTab(next);
    setMenuOpen(false);
  }

  if (authLoading) return <LoadingScreen message="Starting FitTrack…" />;
  if (!user) return <AuthScreen />;
  if (app.loading) return <LoadingScreen message="Loading your fitness data…" />;
  if (app.error) return <ErrorScreen message={app.error} onRetry={app.reload} />;
  if (app.needsOnboarding && app.profile) {
    return <OnboardingScreen profile={app.profile} onComplete={app.completeOnboarding} />;
  }

  return (
    <AppShell
      activeTab={tab}
      onSelectTab={selectTab}
      menuOpen={menuOpen}
      onToggleMenu={() => setMenuOpen((v) => !v)}
      email={user.email}
      onSignOut={signOut}
      syncStatus={sync.status}
    >
      {tab === "today" && (
        <TodayView
          profile={app.profile}
          todayMetric={app.todayMetric}
          recent={app.recent}
          workouts={app.workouts}
          meals={app.meals}
          habitLogs={app.habitLogs}
          habits={app.habits}
          onRefresh={app.reload}
          onLogWater={app.logWater}
          onOpenWorkouts={() => selectTab("workouts")}
          onOpenHabits={() => selectTab("habits")}
          onOpenGoals={() => selectTab("goals")}
        />
      )}

      {tab === "workouts" && (
        <WorkoutsView
          workouts={app.workouts}
          plan={app.plan}
          sessions={app.sessions}
          templates={app.templates}
          exercises={app.exercises}
          onRefresh={app.reload}
        />
      )}

      {tab === "progress" && (
        <ProgressView
          metrics={app.metrics}
          workouts={app.workouts}
          records={app.records}
          body={app.body}
          sessions={app.sessions}
          goals={app.goals}
          onRefresh={app.reload}
        />
      )}

      {tab === "nutrition" && (
        <NutritionView
          meals={app.meals}
          mealItems={app.mealItems}
          foods={app.foods}
          profile={app.profile}
          todayMetric={app.todayMetric}
          onRefresh={app.reload}
        />
      )}

      {tab === "habits" && (
        <HabitsView habits={app.habits} logs={app.habitLogs} reminders={app.reminders} onRefresh={app.reload} />
      )}

      {tab === "goals" && <GoalsView goals={app.goals} profile={app.profile} onRefresh={app.reload} />}

      {tab === "calendar" && (
        <CalendarView
          metrics={app.metrics}
          workouts={app.workouts}
          sessions={app.sessions}
          meals={app.meals}
          body={app.body}
          records={app.records}
          habits={app.habits}
          habitLogs={app.habitLogs}
        />
      )}

      {tab === "profile" && (
        <ProfileView
          profile={app.profile}
          devices={app.devices}
          notifications={app.notifications}
          onRefresh={app.reload}
        />
      )}
    </AppShell>
  );
}


