// @vitest-environment jsdom
/**
 * Phase 17: the delivery history section on the reminder detail view.
 *
 * The API boundary is stubbed at the module level, so the real rendering is exercised: categories are
 * shown as sentences a user can act on rather than machine strings, and a category the server could
 * not name is never dressed up as a cause. Everything shown is a server-supplied name from a closed
 * vocabulary, so there is no provider text to leak into the DOM and nothing to invent.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";

import ReminderDetailView from "../src/views/ReminderDetailView";
import { ApiError } from "../src/lib/api/apiClient";
import { getReminder, getReminderDeliveryHistory, type DeliveryAttempt } from "../src/lib/api/reminderApi";

vi.mock("../src/lib/api/reminderApi", () => ({
  getReminder: vi.fn(),
  getReminderDeliveryHistory: vi.fn(),
}));

const mockGet = vi.mocked(getReminder);
const mockHistory = vi.mocked(getReminderDeliveryHistory);
const ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

const reminder = {
  id: ID,
  type: "workout",
  title: "Time to train",
  message: "Session starts now",
  scheduled_time: "06:00",
  days_of_week: "1,2,3,4,5",
  timezone: "Asia/Kolkata",
  recurrence: "daily",
  enabled: true,
  next_occurrence_at: "2026-09-30T01:30:00Z",
  delivery_status: "delivered",
};

function attempt(overrides: Partial<DeliveryAttempt>): DeliveryAttempt {
  return {
    occurrenceAt: "2026-09-29T01:30:00Z",
    state: "failed",
    attempts: 1,
    reason: "permanent",
    failureCategory: "UNKNOWN",
    deliveredAt: null,
    recordedAt: "2026-09-29T01:30:00Z",
    ...overrides,
  };
}

function history(attempts: DeliveryAttempt[], hasMore = false) {
  return { attempts, limit: 20, offset: 0, hasMore };
}

beforeEach(() => {
  mockGet.mockReset();
  mockHistory.mockReset();
  mockGet.mockResolvedValue(reminder);
  mockHistory.mockResolvedValue(history([]));
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("reminder delivery history", () => {
  it("explains a failure as an action the user can take, not a machine string", async () => {
    mockHistory.mockResolvedValue(history([
      attempt({ failureCategory: "NO_SUBSCRIPTION", state: "failed" }),
    ]));

    render(<ReminderDetailView id={ID} onBack={() => {}} />);

    await waitFor(() => expect(screen.getByText(/Delivery history/i)).toBeTruthy());
    expect(screen.getByText(/Notifications are turned off for this account/i)).toBeTruthy();
    expect(screen.getByText(/Not delivered/)).toBeTruthy();
    // The raw category name is never what the user is shown.
    expect(screen.queryByText("NO_SUBSCRIPTION")).toBeNull();
  });

  it("renders a reason for every category the server can report", async () => {
    const categories = [
      "NO_SUBSCRIPTION", "INVALID_SUBSCRIPTION", "RATE_LIMITED",
      "TEMPORARY_PROVIDER_ERROR", "PROVIDER_REJECTED", "UNKNOWN",
    ] as const;
    mockHistory.mockResolvedValue(history(
      categories.map((category, index) => attempt({
        failureCategory: category,
        occurrenceAt: `2026-09-2${index}T01:30:00Z`,
      })),
    ));

    render(<ReminderDetailView id={ID} onBack={() => {}} />);

    await waitFor(() => expect(screen.getAllByText(/Not delivered/).length).toBe(categories.length));
    // Each category produced its own sentence, so none silently rendered as nothing.
    expect(screen.getByText(/subscription has expired/i)).toBeTruthy();
    expect(screen.getByText(/notification service was busy/i)).toBeTruthy();
    expect(screen.getByText(/could not be reached/i)).toBeTruthy();
    expect(screen.getByText(/reason was not recorded/i)).toBeTruthy();
  });

  it("does not speculate about a cause the server could not name", async () => {
    mockHistory.mockResolvedValue(history([
      attempt({ failureCategory: "UNKNOWN", state: "exhausted", attempts: 3 }),
    ]));

    render(<ReminderDetailView id={ID} onBack={() => {}} />);

    await waitFor(() => expect(screen.getByText(/reason was not recorded/i)).toBeTruthy());
    // No invented cause, and the retry count is shown because it explains the state.
    expect(screen.getByText(/3 tries/)).toBeTruthy();
  });

  it("shows a delivered occurrence as delivered, with no reason attached", async () => {
    mockHistory.mockResolvedValue(history([
      attempt({
        state: "delivered",
        failureCategory: "UNKNOWN",
        reason: null,
        deliveredAt: "2026-09-29T01:30:01Z",
      }),
    ]));

    render(<ReminderDetailView id={ID} onBack={() => {}} />);

    await waitFor(() => expect(screen.getAllByText("Delivered").length).toBeGreaterThan(0));
    expect(screen.queryByText(/reason was not recorded/i)).toBeNull();
  });

  it("says so plainly when the reminder has never been due", async () => {
    mockHistory.mockResolvedValue(history([]));

    render(<ReminderDetailView id={ID} onBack={() => {}} />);

    await waitFor(() => expect(screen.getByText(/has not been due yet/i)).toBeTruthy());
  });

  it("keeps the reminder usable when history cannot be loaded", async () => {
    mockHistory.mockRejectedValue(new ApiError("boom", 500));

    render(<ReminderDetailView id={ID} onBack={() => {}} />);

    // The reminder itself still renders; only the supporting section degrades.
    await waitFor(() => expect(screen.getByText("Time to train")).toBeTruthy());
    expect(screen.getByText(/history could not be loaded/i)).toBeTruthy();
  });

  it("shows a paging control only when the server says more exist", async () => {
    mockHistory.mockResolvedValue(history([attempt({})], true));

    render(<ReminderDetailView id={ID} onBack={() => {}} />);

    await waitFor(() => expect(screen.getByText("Load more")).toBeTruthy());
  });

  it("shows no paging control on the last page", async () => {
    mockHistory.mockResolvedValue(history([attempt({})], false));

    render(<ReminderDetailView id={ID} onBack={() => {}} />);

    await waitFor(() => expect(screen.getByText(/Not delivered/)).toBeTruthy());
    expect(screen.queryByText("Load more")).toBeNull();
  });

  it("requests the first page explicitly rather than relying on a default", async () => {
    render(<ReminderDetailView id={ID} onBack={() => {}} />);

    await waitFor(() => expect(mockHistory).toHaveBeenCalledWith(ID, 20, 0));
  });
});
