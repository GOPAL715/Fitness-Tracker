// @vitest-environment jsdom
/**
 * Phase 19: the user's own notification preferences.
 *
 * <p>The API is stubbed at the module boundary, so these are presentation and validation cases: that
 * the three switches are shown and saved independently, that the quiet-hours fields only appear once
 * the user asks for them, that an invalid combination is refused before it is sent, and - the
 * property that actually matters - that none of this is confused with browser permission, server
 * configuration or a device subscription.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

import { NotificationPreferencesPanel } from "../src/components/NotificationPreferencesPanel";
import {
  getNotificationPreferences,
  saveNotificationPreferences,
  type NotificationPreferences,
} from "../src/lib/api/notificationPreferencesApi";

vi.mock("../src/lib/api/notificationPreferencesApi", () => ({
  getNotificationPreferences: vi.fn(),
  saveNotificationPreferences: vi.fn(),
}));

const mockGet = vi.mocked(getNotificationPreferences);
const mockSave = vi.mocked(saveNotificationPreferences);

function prefs(overrides: Partial<NotificationPreferences> = {}): NotificationPreferences {
  return {
    pushEnabled: true,
    reminderNotificationsEnabled: true,
    quietHoursEnabled: false,
    quietHoursStart: "22:00",
    quietHoursEnd: "07:00",
    timezone: null,
    ...overrides,
  };
}

const toggle = (label: string) => screen.getByLabelText(new RegExp(label, "i")) as HTMLInputElement;
const saveButton = () => screen.getByRole("button", { name: /save preferences/i });

beforeEach(() => {
  mockGet.mockReset();
  mockSave.mockReset();
  mockGet.mockResolvedValue(prefs());
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("notification preferences panel", () => {
  it("shows a loading state before the preferences arrive", async () => {
    let release: (value: NotificationPreferences) => void = () => {};
    mockGet.mockReturnValue(new Promise((resolve) => { release = resolve; }));

    render(<NotificationPreferencesPanel />);

    expect(screen.getByText(/Loading your notification preferences/i)).toBeTruthy();
    release(prefs());
    await waitFor(() => expect(screen.getByText("Your preferences")).toBeTruthy());
  });

  it("shows the defaults the server reports", async () => {
    render(<NotificationPreferencesPanel />);

    await waitFor(() => expect(toggle("Reminder notifications").checked).toBe(true));
    expect(toggle("Push notifications").checked).toBe(true);
    expect(toggle("Quiet hours").checked).toBe(false);
  });

  it("reflects stored preferences rather than assuming the defaults", async () => {
    mockGet.mockResolvedValue(prefs({ pushEnabled: false, reminderNotificationsEnabled: false }));

    render(<NotificationPreferencesPanel />);

    await waitFor(() => expect(toggle("Push notifications").checked).toBe(false));
    expect(toggle("Reminder notifications").checked).toBe(false);
  });

  it("hides the quiet-hours fields until quiet hours are turned on", async () => {
    render(<NotificationPreferencesPanel />);
    await waitFor(() => expect(toggle("Quiet hours").checked).toBe(false));

    expect(screen.queryByLabelText("From")).toBeNull();
    expect(screen.queryByLabelText("Timezone")).toBeNull();
  });

  it("saves a change to a single switch without touching the others", async () => {
    mockSave.mockImplementation(async (value) => value);
    render(<NotificationPreferencesPanel />);
    await waitFor(() => expect(toggle("Push notifications").checked).toBe(true));

    fireEvent.click(toggle("Push notifications"));
    fireEvent.click(saveButton());

    await waitFor(() => expect(mockSave).toHaveBeenCalled());
    const sent = mockSave.mock.calls[0][0];
    expect(sent.pushEnabled).toBe(false);
    // Independent switches: turning push off must not imply turning reminders off.
    expect(sent.reminderNotificationsEnabled).toBe(true);
  });

  it("reports a successful save", async () => {
    mockSave.mockImplementation(async (value) => value);
    render(<NotificationPreferencesPanel />);
    await waitFor(() => expect(toggle("Push notifications").checked).toBe(true));

    fireEvent.click(toggle("Push notifications"));
    fireEvent.click(saveButton());

    await waitFor(() => expect(screen.getByText(/preferences were saved/i)).toBeTruthy());
  });

  it("reports a rejected save and keeps the user's choice on screen", async () => {
    mockSave.mockRejectedValue(new Error("nope"));
    render(<NotificationPreferencesPanel />);
    await waitFor(() => expect(toggle("Push notifications").checked).toBe(true));

    fireEvent.click(toggle("Push notifications"));
    fireEvent.click(saveButton());

    await waitFor(() => expect(screen.getByText(/could not save your preferences/i)).toBeTruthy());
    // The toggle still shows what the user chose, so a failed save does not silently revert it.
    expect(toggle("Push notifications").checked).toBe(false);
  });

  it("surfaces a load failure rather than an empty form that looks authoritative", async () => {
    mockGet.mockRejectedValue(new Error("offline"));

    render(<NotificationPreferencesPanel />);

    await waitFor(() => expect(screen.getByText(/could not load your notification preferences/i)).toBeTruthy());
  });

  it("displays the timezone and explains an overnight window", async () => {
    mockGet.mockResolvedValue(prefs({
      quietHoursEnabled: true, quietHoursStart: "22:00", quietHoursEnd: "07:00", timezone: "Asia/Kolkata",
    }));

    render(<NotificationPreferencesPanel />);

    await waitFor(() => expect(screen.getByText(/Asia\/Kolkata/)).toBeTruthy());
    // 22:00 -> 07:00 is overnight; saying so is the difference between a clear rule and a bug report.
    expect(screen.getByText(/spans midnight/i)).toBeTruthy();
  });

  it("refuses to send quiet hours without a timezone, and says why", async () => {
    mockGet.mockResolvedValue(prefs({ quietHoursEnabled: true, timezone: null }));
    render(<NotificationPreferencesPanel />);
    await waitFor(() => expect(toggle("Quiet hours").checked).toBe(true));

    fireEvent.click(saveButton());

    await waitFor(() => expect(screen.getByText(/choose a timezone/i)).toBeTruthy());
    expect(mockSave).not.toHaveBeenCalled();
  });

  it("refuses a window whose start equals its end", async () => {
    mockGet.mockResolvedValue(prefs({
      quietHoursEnabled: true, quietHoursStart: "22:00", quietHoursEnd: "22:00", timezone: "UTC",
    }));
    render(<NotificationPreferencesPanel />);
    await waitFor(() => expect(toggle("Quiet hours").checked).toBe(true));

    fireEvent.click(saveButton());

    await waitFor(() => expect(screen.getByText(/must differ/i)).toBeTruthy());
    expect(mockSave).not.toHaveBeenCalled();
  });

  it("saves a valid overnight window including its timezone", async () => {
    mockGet.mockResolvedValue(prefs({ quietHoursEnabled: false, timezone: null }));
    mockSave.mockImplementation(async (value) => value);
    render(<NotificationPreferencesPanel />);
    await waitFor(() => expect(toggle("Quiet hours").checked).toBe(false));

    // Turn quiet hours on and fill the window in, which is what makes the form dirty. The time fields
    // are addressed exactly: "Until" is also a word in a toggle's hint, so a loose match is ambiguous.
    fireEvent.click(toggle("Quiet hours"));
    fireEvent.change(screen.getByLabelText("Timezone"), { target: { value: "Asia/Kolkata" } });
    fireEvent.change(screen.getByLabelText("From"), { target: { value: "22:00" } });
    fireEvent.change(screen.getByLabelText("Until"), { target: { value: "07:00" } });
    fireEvent.click(saveButton());

    await waitFor(() => expect(mockSave).toHaveBeenCalled());
    const sent = mockSave.mock.calls[0][0];
    expect(sent.quietHoursEnabled).toBe(true);
    expect(sent.quietHoursStart).toBe("22:00");
    expect(sent.quietHoursEnd).toBe("07:00");
    expect(sent.timezone).toBe("Asia/Kolkata");
  });

  it("never sends a browser permission or a push endpoint it does not have", async () => {
    mockSave.mockImplementation(async (value) => value);
    const { container } = render(<NotificationPreferencesPanel />);
    await waitFor(() => expect(toggle("Quiet hours").checked).toBe(false));

    fireEvent.click(toggle("Push notifications"));
    fireEvent.click(saveButton());
    await waitFor(() => expect(mockSave).toHaveBeenCalled());

    // The request body is exactly the six preference fields and nothing else.
    expect(Object.keys(mockSave.mock.calls[0][0]).sort()).toEqual([
      "pushEnabled", "quietHoursEnabled", "quietHoursEnd", "quietHoursStart",
      "reminderNotificationsEnabled", "timezone",
    ]);
    const rendered = container.textContent ?? "";
    expect(rendered).not.toMatch(/https?:\/\//);
    expect(rendered).not.toMatch(/fcm\.googleapis/);
  });

  it("keeps the preferences card distinct from the browser, server and device facts", async () => {
    render(<NotificationPreferencesPanel />);

    await waitFor(() => expect(screen.getByText("Your preferences")).toBeTruthy());
    // None of the observed facts are restated here, so the two cannot disagree about what is wrong.
    expect(screen.queryByText("Browser support")).toBeNull();
    expect(screen.queryByText("Server configuration")).toBeNull();
    expect(screen.queryByText("This device")).toBeNull();
  });
});
