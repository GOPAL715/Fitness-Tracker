import { useEffect, useState } from "react";
import { useAuth } from "./lib/auth";
import { parseReminderPath } from "./lib/paths";
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
import CoachView from "./views/CoachView";
import CalendarView from "./views/CalendarView";
import ProfileView from "./views/ProfileView";
import ReminderDetailView from "./views/ReminderDetailView";

/**
 * The reminder id encoded in the current URL, or null.
 *
 * Read through {@link parseReminderPath} so the application and the service worker agree on exactly
 * one definition of a valid reminder path.
 */
function reminderIdFromLocation(): string | null {
  if (typeof window === "undefined") return null;
  return parseReminderPath(window.location.pathname);
}

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

  // Phase 15: the one URL-driven view in the app. A reminder deep link replaces the tab content
  // rather than becoming a tenth tab, so the existing nine-tab navigation is untouched.
  //
  // Re-read on focus as well as on load, because the service worker navigates a focused window with
  // client.navigate(), which is a real page load that can land here with a path the SPA has not yet
  // seen. Anything that is not a reminder path leaves the tab state alone.
  const [reminderId, setReminderId] = useState<string | null>(() => reminderIdFromLocation());

  useEffect(() => {
    const sync = () => setReminderId(reminderIdFromLocation());
    window.addEventListener("focus", sync);
    document.addEventListener("visibilitychange", sync);
    window.addEventListener("popstate", sync);
    return () => {
      window.removeEventListener("focus", sync);
      document.removeEventListener("visibilitychange", sync);
      window.removeEventListener("popstate", sync);
    };
  }, []);

  /** Returns to the ordinary application, clearing the deep link from the address bar too. */
  function leaveReminder() {
    if (typeof window !== "undefined" && window.history?.replaceState) {
      window.history.replaceState(null, "", "/");
    }
    setReminderId(null);
    setTab(DEFAULT_TAB);
  }

  function selectTab(next: Tab) {
    if (reminderId) leaveReminder();
    setTab(next);
    setMenuOpen(false);
  }

  if (authLoading) return <LoadingScreen message="Starting FitTrack…" />;
  if (!user) return <AuthScreen />;

  // A reminder deep link takes precedence over the tab content, and is rendered inside the same
  // shell so the user keeps the header and sign-out. It is placed after the auth gate deliberately:
  // an unauthenticated deep link must land on the sign-in screen, never on reminder data.
  if (reminderId) {
    return (
      <AppShell
        activeTab={DEFAULT_TAB}
        onSelectTab={selectTab}
        menuOpen={menuOpen}
        onToggleMenu={() => setMenuOpen((v) => !v)}
        email={user.email}
        onSignOut={signOut}
        syncStatus={sync.status}
      >
        <ReminderDetailView id={reminderId} onBack={leaveReminder} />
      </AppShell>
    );
  }

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

      {tab === "coach" && <CoachView fallbackInput={{
        sessions: app.workouts?.filter((w) => w.completed).length ?? 0,
        totalMinutes: (app.workouts ?? []).reduce((sum, w) => sum + (w.duration_minutes ?? 0), 0),
        mealsLogged: app.meals?.length ?? 0,
        habitsTracked: app.habits?.length ?? 0,
      }} />}

      {tab === "calendar" && <CalendarView />}

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


