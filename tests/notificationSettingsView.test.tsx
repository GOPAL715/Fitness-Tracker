// @vitest-environment jsdom
/**
 * Phase 18: what the notification settings centre shows, and what it refuses to show.
 *
 * The push module is mocked, so these cases are purely about presentation: that the three independent
 * facts are displayed as three separate things, that a blocked permission produces browser guidance
 * instead of a button that cannot work, and that no push endpoint - in whole or in part - is ever
 * rendered into the DOM.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";

import NotificationSettingsView from "../src/views/NotificationSettingsView";
import {
  disablePush,
  enablePush,
  readPushSnapshot,
  type PushSnapshot,
} from "../src/lib/push/pushSubscription";

vi.mock("../src/lib/push/pushSubscription", () => ({
  readPushSnapshot: vi.fn(),
  enablePush: vi.fn(),
  disablePush: vi.fn(),
}));

// Phase 19: the settings screen now hosts the preferences panel, which reads its own API. Stubbed so
// these cases stay about the push facts rather than about preference persistence.
vi.mock("../src/lib/api/notificationPreferencesApi", () => ({
  getNotificationPreferences: vi.fn(async () => ({
    pushEnabled: true,
    reminderNotificationsEnabled: true,
    quietHoursEnabled: false,
    quietHoursStart: "22:00",
    quietHoursEnd: "07:00",
    timezone: null,
  })),
  saveNotificationPreferences: vi.fn(),
}));

const mockSnapshot = vi.mocked(readPushSnapshot);
const mockEnable = vi.mocked(enablePush);
const mockDisable = vi.mocked(disablePush);

function snapshot(overrides: Partial<PushSnapshot> = {}): PushSnapshot {
  return {
    browserSupported: true,
    secureContext: true,
    serverConfigured: true,
    permission: "granted",
    thisDeviceSubscribed: false,
    otherDeviceCount: 0,
    error: null,
    ...overrides,
  };
}

/** Clicks a control once the snapshot has rendered, so a test never races the mount effect. */
async function click(name: RegExp) {
  await waitFor(() => expect(screen.getByRole("button", { name })).toBeTruthy());
  screen.getByRole("button", { name }).click();
}

beforeEach(() => {
  mockSnapshot.mockReset();
  mockEnable.mockReset();
  mockDisable.mockReset();
  mockSnapshot.mockResolvedValue(snapshot());
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("notification settings screen", () => {
  it("shows a loading state before the snapshot arrives", async () => {
    let release: (value: PushSnapshot) => void = () => {};
    mockSnapshot.mockReturnValue(new Promise((resolve) => { release = resolve; }));

    render(<NotificationSettingsView />);

    expect(screen.getByText(/Loading notification settings/i)).toBeTruthy();
    release(snapshot());
    await waitFor(() => expect(screen.getByText("Device status")).toBeTruthy());
  });

  it("reports an unsupported browser instead of offering to enable anything", async () => {
    mockSnapshot.mockResolvedValue(snapshot({ browserSupported: false, permission: "unsupported" }));

    render(<NotificationSettingsView />);

    await waitFor(() => expect(screen.getByText("Not supported")).toBeTruthy());
    expect(screen.queryByRole("button", { name: /Enable notifications/i })).toBeNull();
    expect(screen.getByText(/cannot receive notifications/i)).toBeTruthy();
  });

  it("reports a server with push switched off as a server setting, not a user error", async () => {
    mockSnapshot.mockResolvedValue(snapshot({ serverConfigured: false }));

    render(<NotificationSettingsView />);

    await waitFor(() => expect(screen.getByText("Not configured")).toBeTruthy());
    expect(screen.getByText(/server setting, not something you can change/i)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Enable notifications/i })).toBeNull();
  });

  it("shows permission as its own fact, separate from the device state", async () => {
    mockSnapshot.mockResolvedValue(snapshot({ permission: "default", thisDeviceSubscribed: false }));

    render(<NotificationSettingsView />);

    await waitFor(() => expect(screen.getByText("Not yet asked")).toBeTruthy());
    expect(screen.getByText("Not subscribed")).toBeTruthy();
  });

  it("keeps the enable button available when permission has not been asked for yet", async () => {
    mockSnapshot.mockResolvedValue(snapshot({ permission: "default" }));

    render(<NotificationSettingsView />);

    await waitFor(() => expect(screen.getByRole("button", { name: /Enable notifications/i })).toBeTruthy());
  });

  it("explains a blocked permission and offers no button that cannot succeed", async () => {
    mockSnapshot.mockResolvedValue(snapshot({ permission: "denied", thisDeviceSubscribed: false }));

    render(<NotificationSettingsView />);

    await waitFor(() => expect(screen.getByText("Blocked")).toBeTruthy());
    // Re-prompting a browser that already refused is untrustworthy and cannot succeed.
    expect(screen.queryByRole("button", { name: /Enable notifications/i })).toBeNull();
    expect(screen.getByText(/only your browser's site settings can undo this/i)).toBeTruthy();
  });

  it("offers a disable action only when this device is subscribed", async () => {
    mockSnapshot.mockResolvedValue(snapshot({ thisDeviceSubscribed: true }));

    render(<NotificationSettingsView />);

    await waitFor(() => expect(screen.getByRole("button", { name: /Turn off on this device/i })).toBeTruthy());
    expect(screen.getByText("Subscribed")).toBeTruthy();
  });

  it("describes the other-device count in words rather than naming devices", async () => {
    mockSnapshot.mockResolvedValue(snapshot({ thisDeviceSubscribed: true, otherDeviceCount: 2 }));

    render(<NotificationSettingsView />);

    await waitFor(() => expect(screen.getByText("2 other devices")).toBeTruthy());
    expect(screen.getByText(/affects this device only/i)).toBeTruthy();
  });

  it("says when there are no other devices", async () => {
    mockSnapshot.mockResolvedValue(snapshot({ thisDeviceSubscribed: true, otherDeviceCount: 0 }));

    render(<NotificationSettingsView />);

    await waitFor(() => expect(screen.getByText("No other devices")).toBeTruthy());
  });

  it("surfaces a read failure rather than an empty, confident screen", async () => {
    mockSnapshot.mockResolvedValue(snapshot({
      error: "Could not reach FitTrack to read the notification settings.",
    }));

    render(<NotificationSettingsView />);

    await waitFor(() => expect(screen.getByRole("status")).toBeTruthy());
    expect(screen.getByText(/Could not reach FitTrack/i)).toBeTruthy();
  });

  it("reports a successful enable", async () => {
    mockEnable.mockResolvedValue({ ok: true, message: "Notifications are on for this device." });

    render(<NotificationSettingsView />);
    await click(/Enable notifications/i);

    await waitFor(() => expect(screen.getByText("Notifications are on for this device.")).toBeTruthy());
  });

  it("reports an enable failure and does not claim notifications are on", async () => {
    mockEnable.mockResolvedValue({
      ok: false,
      reason: "backend-rejected",
      message: "FitTrack could not save this subscription, so nothing was changed. Please try again.",
    });

    render(<NotificationSettingsView />);
    await click(/Enable notifications/i);

    await waitFor(() => expect(screen.getByText(/could not save this subscription/i)).toBeTruthy());
    expect(screen.queryByText("Notifications are on for this device.")).toBeNull();
  });

  it("reports a partial disable honestly rather than claiming a clean removal", async () => {
    mockSnapshot.mockResolvedValue(snapshot({ thisDeviceSubscribed: true }));
    mockDisable.mockResolvedValue({
      ok: true,
      serverRemoved: true,
      browserCleaned: false,
      message: "Removed from FitTrack. Browser subscription cleanup could not be completed.",
    });

    render(<NotificationSettingsView />);
    await click(/Turn off on this device/i);

    await waitFor(() => expect(screen.getByText(/cleanup could not be completed/i)).toBeTruthy());
  });

  it("does not claim a disable succeeded when the server still holds the row", async () => {
    mockSnapshot.mockResolvedValue(snapshot({ thisDeviceSubscribed: true }));
    mockDisable.mockResolvedValue({
      ok: false,
      serverRemoved: false,
      browserCleaned: false,
      message: "FitTrack could not remove this subscription, so it is still active. Please try again.",
    });

    render(<NotificationSettingsView />);
    await click(/Turn off on this device/i);

    await waitFor(() => expect(screen.getByText(/still active/i)).toBeTruthy());
  });

  it("re-reads the snapshot after an action instead of assuming it worked", async () => {
    mockEnable.mockResolvedValue({ ok: true, message: "Notifications are on for this device." });

    render(<NotificationSettingsView />);
    await click(/Enable notifications/i);

    // Two reads: the mount read and the post-action confirmation.
    await waitFor(() => expect(mockSnapshot).toHaveBeenCalledTimes(2));
  });

  it("never renders a push endpoint, in whole or in part", async () => {
    mockSnapshot.mockResolvedValue(snapshot({ thisDeviceSubscribed: true, otherDeviceCount: 1 }));

    const { container } = render(<NotificationSettingsView />);
    await waitFor(() => expect(screen.getByText("Subscribed")).toBeTruthy());

    const rendered = container.textContent ?? "";
    expect(rendered).not.toContain("fcm.googleapis.com");
    expect(rendered).not.toContain("SUPERSECRETPATH");
    expect(rendered).not.toMatch(/https:\/\//);
  });

  it("returns to the caller when a back handler is supplied", async () => {
    let back = 0;

    render(<NotificationSettingsView onBack={() => { back += 1; }} />);
    await waitFor(() => expect(screen.getByRole("button", { name: /Back to profile/i })).toBeTruthy());
    screen.getByRole("button", { name: /Back to profile/i }).click();

    expect(back).toBe(1);
  });
});
