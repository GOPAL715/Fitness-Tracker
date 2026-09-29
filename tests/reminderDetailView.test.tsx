// @vitest-environment jsdom
/**
 * The reminder detail view reached by a push notification.
 *
 * The API boundary is stubbed rather than mocked at the module level, so these tests exercise the
 * real state handling: loading, success, not-found, expired session, and a network failure. The
 * security property that matters most is that a reminder is rendered as text - a title containing
 * markup must appear literally, never as HTML.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";

import ReminderDetailView from "../src/views/ReminderDetailView";
import { ApiError } from "../src/lib/api/apiClient";
import { getReminder, getReminderDeliveryHistory } from "../src/lib/api/reminderApi";

/**
 * The view reads the reminder and its delivery history independently, so both are stubbed. History
 * resolves empty here: these cases are about the reminder itself, and the empty history exercises the
 * view's own "nothing recorded yet" path.
 */
vi.mock("../src/lib/api/reminderApi", () => ({
  getReminder: vi.fn(),
  getReminderDeliveryHistory: vi.fn(() =>
    Promise.resolve({ attempts: [], limit: 20, offset: 0, hasMore: false })),
}));

const mockGet = vi.mocked(getReminder);
const ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

const reminder = {
  id: ID,
  type: "workout",
  title: "Time to train",
  message: "Session starts now",
  scheduled_time: "06:00",
  days_of_week: "1,2,3,4,5,6,0",
  timezone: "Asia/Kolkata",
  recurrence: "daily",
  enabled: true,
  next_occurrence_at: "2026-09-30T01:30:00Z",
  delivery_status: "delivered",
};

beforeEach(() => {
  mockGet.mockReset();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("reminder detail view", () => {
  it("shows a loading state before the reminder arrives", async () => {
    let release: (value: unknown) => void = () => {};
    mockGet.mockReturnValue(new Promise((resolve) => { release = resolve; }));

    render(<ReminderDetailView id={ID} onBack={() => {}} />);

    expect(screen.getByText(/Loading reminder/i)).toBeTruthy();
    release(reminder);
    await waitFor(() => expect(screen.getByText("Time to train")).toBeTruthy());
  });

  it("renders the reminder, including the server-owned scheduling fields", async () => {
    mockGet.mockResolvedValue(reminder);

    render(<ReminderDetailView id={ID} onBack={() => {}} />);

    await waitFor(() => expect(screen.getByText("Time to train")).toBeTruthy());
    expect(screen.getByText("Session starts now")).toBeTruthy();
    expect(screen.getByText(/Active/)).toBeTruthy();
    expect(screen.getByText(/workout/)).toBeTruthy();
    expect(screen.getByText("Asia/Kolkata")).toBeTruthy();
    // The delivery state is shown as a sentence, not the raw stored value: Phase 17 replaced the
    // machine string a user could not interpret with wording that says what it means.
    expect(screen.getByText("Delivered")).toBeTruthy();
    expect(screen.getByText("06:00 on 1, 2, 3, 4, 5, 6, 0")).toBeTruthy();
    expect(mockGet).toHaveBeenCalledWith(ID);
  });

  it("renders reminder text as text, never as markup", async () => {
    mockGet.mockResolvedValue({ ...reminder, title: "<img src=x onerror=alert(1)>" });

    const { container } = render(<ReminderDetailView id={ID} onBack={() => {}} />);

    await waitFor(() => expect(screen.getByText("<img src=x onerror=alert(1)>")).toBeTruthy());
    expect(container.querySelector("img")).toBeNull();
    // The property that matters is that no element was created from the string. The serialized
    // HTML still contains the characters, escaped - that is the escaping working, not a leak.
    expect(container.querySelector("[onerror]")).toBeNull();
    expect(container.textContent).toContain("<img src=x onerror=alert(1)>");
  });

  it("shows a not-found state for a deleted or foreign reminder", async () => {
    mockGet.mockRejectedValue(new ApiError(404, "not found"));

    render(<ReminderDetailView id={ID} onBack={() => {}} />);

    await waitFor(() => expect(screen.getByText(/Reminder not found/i)).toBeTruthy());
    // The wording must not reveal whether the id belongs to somebody else.
    expect(screen.getByText(/another account/i)).toBeTruthy();
  });

  it("asks the user to sign in again when the session has expired", async () => {
    mockGet.mockRejectedValue(new ApiError(401, "unauthorized"));

    render(<ReminderDetailView id={ID} onBack={() => {}} />);

    await waitFor(() => expect(screen.getByText(/Sign in again/i)).toBeTruthy());
  });

  it("reports a network or server failure without losing the back path", async () => {
    mockGet.mockRejectedValue(new ApiError(500, "boom"));

    render(<ReminderDetailView id={ID} onBack={() => {}} />);

    await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
    expect(screen.getByText(/Back to FitTrack/i)).toBeTruthy();
  });

  it("returns to the application on request", async () => {
    mockGet.mockResolvedValue(reminder);
    let back = 0;

    render(<ReminderDetailView id={ID} onBack={() => { back += 1; }} />);

    await waitFor(() => expect(screen.getByText("Time to train")).toBeTruthy());
    screen.getByText(/Back to FitTrack/i).click();

    expect(back).toBe(1);
  });
});
