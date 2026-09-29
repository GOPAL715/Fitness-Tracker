// @vitest-environment jsdom
/**
 * The Reminders management tab and its create/edit form.
 *
 * The API module is stubbed wholesale, so these tests also pin the contract the UI depends on: that
 * writes carry only {@link ReminderWrite} fields - never the server-owned delivery state - and that a
 * weekly reminder is sent as day indices matching the backend's 0=Sunday contract.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

import RemindersView from "../src/views/RemindersView";
import ReminderFormModal, { WEEK_DAYS, parseDays } from "../src/views/ReminderFormModal";
import { ApiError } from "../src/lib/api/apiClient";
import {
  createReminder, deleteReminder, listReminders, rescheduleReminder, setReminderEnabled, updateReminder,
} from "../src/lib/api/reminderApi";

vi.mock("../src/lib/api/reminderApi", () => ({
  listReminders: vi.fn(),
  getReminder: vi.fn(),
  createReminder: vi.fn(),
  updateReminder: vi.fn(),
  setReminderEnabled: vi.fn(),
  deleteReminder: vi.fn(),
  rescheduleReminder: vi.fn(),
}));

const mockList = vi.mocked(listReminders);
const mockCreate = vi.mocked(createReminder);
const mockUpdate = vi.mocked(updateReminder);
const mockEnabled = vi.mocked(setReminderEnabled);
const mockDelete = vi.mocked(deleteReminder);
const mockReschedule = vi.mocked(rescheduleReminder);

const reminder = {
  id: "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
  user_id: "user-1",
  title: "Time to train",
  message: "Session starts now",
  scheduled_time: "06:00:00",
  days_of_week: "1,3",
  enabled: true,
  timezone: "Asia/Kolkata",
  recurrence: "weekly",
  delivery_status: "delivered",
  next_occurrence_at: "2026-09-30T01:30:00Z",
};

beforeEach(() => {
  vi.clearAllMocks();
  mockList.mockResolvedValue([reminder]);
  mockCreate.mockResolvedValue(reminder);
  mockUpdate.mockResolvedValue(reminder);
  mockEnabled.mockResolvedValue(undefined);
  mockDelete.mockResolvedValue(undefined);
  mockReschedule.mockResolvedValue({ id: reminder.id, next_occurrence_at: "x", timezone: "UTC" });
});

afterEach(() => cleanup());

describe("reminders list", () => {
  it("shows a loading state, then the reminders", async () => {
    render(<RemindersView onOpen={() => {}} />);
    expect(screen.getByText(/Loading reminders/i)).toBeTruthy();
    await waitFor(() => expect(screen.getByText("Time to train")).toBeTruthy());
  });

  it("renders the stored schedule in the caller's own terms", async () => {
    render(<RemindersView onOpen={() => {}} />);
    await waitFor(() => expect(screen.getByText(/Monday, Wednesday/)).toBeTruthy());
    expect(screen.getByText(/06:00 Asia\/Kolkata/)).toBeTruthy();
  });

  it("shows an empty state when there are none", async () => {
    mockList.mockResolvedValue([]);
    render(<RemindersView onOpen={() => {}} />);
    await waitFor(() => expect(screen.getByText(/No reminders yet/i)).toBeTruthy());
  });

  it("reports a load failure and offers a retry", async () => {
    mockList.mockRejectedValue(new ApiError(500, "boom"));
    render(<RemindersView onOpen={() => {}} />);
    await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
    mockList.mockResolvedValue([reminder]);
    // Scoped to the card: the shell renders its own "Try again" when app data fails, so an
    // unscoped query would match two controls.
    // Matched on the button, not the text: the error message itself ends in "try again", so a text
    // query inside the card would match both.
    const card = screen.getByRole("alert").closest("div") as HTMLElement;
    fireEvent.click(within(card).getByRole("button", { name: /try again/i }));
    await waitFor(() => expect(screen.getByText("Time to train")).toBeTruthy());
  });

  it("opens a reminder through the callback rather than navigating by itself", async () => {
    let opened = "";
    render(<RemindersView onOpen={(id) => { opened = id; }} />);
    await waitFor(() => expect(screen.getByText("Time to train")).toBeTruthy());
    fireEvent.click(screen.getByText("Time to train"));
    expect(opened).toBe(reminder.id);
  });

  it("pauses and resumes through a single enabled write", async () => {
    render(<RemindersView onOpen={() => {}} />);
    await waitFor(() => expect(screen.getByText(/Pause/i)).toBeTruthy());
    fireEvent.click(screen.getByText(/Pause/i));
    await waitFor(() => expect(mockEnabled).toHaveBeenCalledWith(reminder.id, false));
  });

  it("never offers delivery state as a promise about notifications", async () => {
    render(<RemindersView onOpen={() => {}} />);
    await waitFor(() => expect(screen.getByText(/Last delivery: delivered/)).toBeTruthy());
    expect(screen.queryByText(/notifications are on/i)).toBeNull();
  });
});

describe("reminder form", () => {
  it("sends only writable fields, never the server-owned delivery state", async () => {
    render(<ReminderFormModal reminder={reminder} onClose={() => {}} onSaved={() => {}} />);
    fireEvent.click(screen.getByText(/Save changes/i));

    await waitFor(() => expect(mockUpdate).toHaveBeenCalled());
    const sent = mockUpdate.mock.calls[0][1] as Record<string, unknown>;
    expect(sent.title).toBe("Time to train");
    for (const forbidden of ["id", "user_id", "delivery_status", "delivery_attempts",
      "last_error", "last_delivered_at", "next_occurrence_at"]) {
      expect(Object.keys(sent)).not.toContain(forbidden);
    }
  });

  it("sends a weekly reminder as day indices with 0 = Sunday", async () => {
    render(<ReminderFormModal reminder={null} onClose={() => {}} onSaved={() => {}} />);
    fireEvent.change(screen.getByLabelText(/Title/i), { target: { value: "Weekly" } });
    fireEvent.change(screen.getByLabelText(/Repeats/i), { target: { value: "weekly" } });
    fireEvent.click(screen.getByLabelText("Sunday"));
    fireEvent.click(screen.getByLabelText("Wednesday"));
    fireEvent.click(screen.getByText(/Create reminder/i));

    await waitFor(() => expect(mockCreate).toHaveBeenCalled());
    expect((mockCreate.mock.calls[0][0] as Record<string, unknown>).days_of_week).toBe("0,3");
  });

  it("requires at least one day for a weekly reminder", async () => {
    render(<ReminderFormModal reminder={null} onClose={() => {}} onSaved={() => {}} />);
    fireEvent.change(screen.getByLabelText(/Title/i), { target: { value: "Weekly" } });
    fireEvent.change(screen.getByLabelText(/Repeats/i), { target: { value: "weekly" } });
    fireEvent.click(screen.getByText(/Create reminder/i));

    await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
    expect(mockCreate).not.toHaveBeenCalled();
  });

  it("exposes exactly the seven day indices the backend understands", () => {
    expect(WEEK_DAYS.map((d) => d.index)).toEqual([0, 1, 2, 3, 4, 5, 6]);
    expect(WEEK_DAYS[0].label).toBe("Sunday");
    expect(WEEK_DAYS[6].label).toBe("Saturday");
  });

  it("only offers the three recurrence literals the backend parses", () => {
    render(<ReminderFormModal reminder={null} onClose={() => {}} onSaved={() => {}} />);
    const options = Array.from(document.querySelectorAll("#reminder-recurrence option"))
      .map((o) => (o as HTMLOptionElement).value);
    expect(options).toEqual(["once", "daily", "weekly"]);
  });

  it("preserves the stored timezone on edit rather than using the browser one", () => {
    render(<ReminderFormModal reminder={reminder} onClose={() => {}} onSaved={() => {}} />);
    expect((screen.getByLabelText(/Timezone/i) as HTMLSelectElement).value).toBe("Asia/Kolkata");
  });

  it("keeps an unlisted stored timezone selected rather than silently replacing it", () => {
    const exotic = { ...reminder, timezone: "Mars/Olympus_Mons" };
    render(<ReminderFormModal reminder={exotic} onClose={() => {}} onSaved={() => {}} />);
    expect((screen.getByLabelText(/Timezone/i) as HTMLSelectElement).value).toBe("Mars/Olympus_Mons");
  });

  it("describes once the way the server actually behaves", () => {
    render(<ReminderFormModal reminder={null} onClose={() => {}} onSaved={() => {}} />);
    fireEvent.change(screen.getByLabelText(/Repeats/i), { target: { value: "once" } });
    expect(screen.getByText(/already passed today will run again tomorrow/i)).toBeTruthy();
  });

  it("asks before deleting, and only then deletes", async () => {
    render(<ReminderFormModal reminder={reminder} onClose={() => {}} onSaved={() => {}} onDeleted={() => {}} />);
    fireEvent.click(screen.getByText(/Delete/i));
    expect(mockDelete).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText(/Yes, delete/i));
    await waitFor(() => expect(mockDelete).toHaveBeenCalledWith(reminder.id));
  });

  it("recomputes the schedule after a save so the next time is not stale", async () => {
    render(<ReminderFormModal reminder={reminder} onClose={() => {}} onSaved={() => {}} />);
    fireEvent.click(screen.getByText(/Save changes/i));
    await waitFor(() => expect(mockReschedule).toHaveBeenCalledWith(reminder.id));
  });

  it("keeps the save when only the reschedule fails", async () => {
    mockReschedule.mockRejectedValue(new ApiError(500, "boom"));
    const saved = vi.fn();
    render(<ReminderFormModal reminder={reminder} onClose={() => {}} onSaved={saved} />);
    fireEvent.click(screen.getByText(/Save changes/i));
    await waitFor(() => expect(saved).toHaveBeenCalled());
  });

  it("surfaces a save failure instead of closing the form", async () => {
    mockCreate.mockRejectedValue(new ApiError(400, "nope"));
    render(<ReminderFormModal reminder={null} onClose={() => {}} onSaved={() => {}} />);
    fireEvent.change(screen.getByLabelText(/Title/i), { target: { value: "X" } });
    fireEvent.click(screen.getByText(/Create reminder/i));
    await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
  });
});

describe("day parsing", () => {
  it("reads the stored comma-separated indices", () => {
    expect(parseDays("0,3")).toEqual([0, 3]);
    // Order follows the stored string, not the weekday order; the form sorts on write instead.
    expect(parseDays("1,2,3,4,5,6,0")).toEqual([1, 2, 3, 4, 5, 6, 0]);
  });

  it("ignores anything out of range or malformed", () => {
    expect(parseDays("0,99,-1,abc")).toEqual([0]);
    expect(parseDays(null)).toEqual([]);
    expect(parseDays("")).toEqual([]);
  });
});
