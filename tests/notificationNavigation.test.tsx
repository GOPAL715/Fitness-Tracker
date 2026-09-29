// @vitest-environment jsdom
/**
 * Phase 18: routing between the profile summary and the notification settings centre.
 *
 * These are the composition-level cases the view-level tests cannot reach. The notification settings
 * screen is a sub-view of Profile rather than a new tab, so the two properties worth pinning are that
 * the link actually swaps the screen, and that the Phase 15 reminder deep link still wins over both -
 * a push notification tap must never land a user on a notification settings page.
 *
 * Only the composition root's collaborators are stubbed. The real ProfileView and
 * NotificationSettingsView are rendered, so a change to either one's markup cannot leave these passing
 * against a stale copy of the UI.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

vi.mock("../src/lib/auth", () => ({
  useAuth: () => ({
    user: { id: "11111111-1111-1111-1111-111111111111", email: "sam@example.test" },
    loading: false,
    signOut: vi.fn(),
  }),
}));

// The app data hook is not what is under test; only the shell and the two screens are.
vi.mock("../src/features/appData/useAppData", () => ({
  useAppData: () => ({
    loading: false,
    error: null,
    needsOnboarding: false,
    completeOnboarding: vi.fn(),
    todayMetric: null,
    recent: [],
    reload: vi.fn(async () => {}),
    logWater: vi.fn(async () => {}),
    profile: {
      id: "11111111-1111-1111-1111-111111111111",
      display_name: "Alex Morgan",
      goal: "Build strength",
      activity_target: 4,
      weekly_minutes: 180,
      fitness_level: "Intermediate",
      equipment: "Full gym",
      limitations: "None",
      sleep_target_hours: 8,
      step_target: 10000,
      calorie_target: 2400,
      protein_target_g: 150,
      water_target_oz: 100,
      target_weight_lb: 175,
    },
    metrics: [], workouts: [], plan: [], sessions: [], templates: [], exercises: [], sets: [],
    meals: [], mealItems: [], foods: [], habits: [], habitLogs: [], reminders: [], goals: [],
    records: [], body: [], devices: [], notifications: [],
  }),
}));

vi.mock("../src/lib/offline/useSyncStatus", () => ({
  useSyncStatus: () => ({
    status: { state: "online", pending: 0, failed: 0, conflicts: 0 },
    refresh: vi.fn(async () => {}),
  }),
}));

/** Read-only, so the settings screen can describe state without stubbing the push module. */
vi.mock("../src/lib/push/pushSubscription", () => ({
  pushSupport: vi.fn(async () => "unsubscribed"),
  pushExplainText: vi.fn(() => "Enable notifications to get reminders on this device."),
  readPushSnapshot: vi.fn(async () => ({
    browserSupported: true, secureContext: true, serverConfigured: true,
    permission: "granted", thisDeviceSubscribed: false, otherDeviceCount: 0, error: null,
  })),
  enablePush: vi.fn(async () => ({ ok: true, message: "Notifications are on for this device." })),
  disablePush: vi.fn(async () => ({
    ok: true, serverRemoved: true, browserCleaned: true, message: "Notifications are off for this device.",
  })),
}));

import App from "../src/App";

const REMINDER_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

/** Sets the address bar, the way a service-worker client.navigate() or a shared link would. */
function goTo(path: string) {
  window.history.replaceState(null, "", path);
}

const profileTab = () => screen.getByRole("button", { name: /profile/i });

beforeEach(() => {
  goTo("/");
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("profile notification summary", () => {
  it("summarises notifications and links to the full settings screen", async () => {
    render(<App />);

    fireEvent.click(profileTab());

    // The summary stays on the profile, rather than being replaced by the settings screen.
    await waitFor(() => expect(screen.getByText("Reminder notifications")).toBeTruthy());
    expect(screen.queryByText("Device status")).toBeNull();
    expect(screen.getByRole("button", { name: /notification settings/i })).toBeTruthy();
  });

  it("opens the settings centre and returns to the profile on back", async () => {
    render(<App />);
    fireEvent.click(profileTab());
    await waitFor(() => expect(screen.getByText("Reminder notifications")).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: /notification settings/i }));

    // The settings screen replaces the profile entirely rather than stacking on it.
    await waitFor(() => expect(screen.getByText("Device status")).toBeTruthy());
    expect(screen.queryByText("Reminder notifications")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /back to profile/i }));

    await waitFor(() => expect(screen.getByText("Reminder notifications")).toBeTruthy());
    expect(screen.queryByText("Device status")).toBeNull();
  });

  it("discards the settings sub-view when the user navigates to another tab", async () => {
    render(<App />);
    fireEvent.click(profileTab());
    await waitFor(() => expect(screen.getByText("Reminder notifications")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: /notification settings/i }));
    await waitFor(() => expect(screen.getByText("Device status")).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: /^goals$/i }));
    fireEvent.click(profileTab());

    // Coming back to Profile lands on the profile, not on a screen the user left behind.
    await waitFor(() => expect(screen.getByText("Reminder notifications")).toBeTruthy());
    expect(screen.queryByText("Device status")).toBeNull();
  });
});

describe("reminder deep link is unaffected by the notification sub-view", () => {
  it("opens the reminder rather than the notification settings screen", async () => {
    goTo(`/reminders/${REMINDER_ID}`);

    render(<App />);

    // A push tap must land on the reminder itself, never on a notification settings page.
    expect(screen.queryByText("Device status")).toBeNull();
    expect(screen.queryByText("Reminder notifications")).toBeNull();
  });

  it("closes a deep link and returns to the reminders tab, not to notification settings", async () => {
    goTo(`/reminders/${REMINDER_ID}`);

    render(<App />);
    // The detail view fetches; backing out works whatever the fetch resolved to.
    const back = await screen.findByRole("button", { name: /back/i });
    fireEvent.click(back);

    // Leaving a deep link clears it from the address bar and activates the Reminders tab. What
    // matters here is that it does not land on the notification settings screen.
    await waitFor(() => expect(screen.queryByText("Device status")).toBeNull());
    expect(window.location.pathname).toBe("/");
    expect(screen.getByRole("button", { name: /^reminders$/i }).getAttribute("aria-current")).toBe("page");
  });
});
