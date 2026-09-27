// @vitest-environment jsdom
/**
 * Habits, through the real component and the real adapter.
 *
 * <p>Pins the mutation-handling regressions: every habit and reminder action used to be
 * fire-and-forget, so a rejected check-in still refreshed the screen and looked saved, the delete
 * dialog closed whether or not the delete worked, and a thrown request could leave a creator
 * stuck on "Saving". Also pins the streak label, which claimed a longest streak that is not
 * calculated.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import HabitsView from "../src/views/HabitsView";
import { setAuthTokens } from "../src/lib/api/apiClient";
import { dateOffset, todayISO } from "../src/lib/utils";
import type { Habit, HabitLog, Reminder } from "../src/lib/types";

type Call = { url: string; method: string; body: any };
let calls: Call[] = [];

function stubFetch(status: number, body: unknown = {}) {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({
      url: String(input),
      method: init?.method ?? "GET",
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    });
    return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
  });
}

const habit: Habit = {
  id: "h1", user_id: "u1", name: "Drink water", description: "", icon: "droplet",
  target_per_week: 7, color: "#38bdf8", active: true,
};

const log = (date: string, id = `l-${date}`): HabitLog =>
  ({ id, user_id: "u1", habit_id: "h1", log_date: date, completed: true });

const reminder: Reminder = {
  id: "r1", user_id: "u1", type: "WORKOUT", title: "Train", message: "",
  scheduled_time: "18:00:00", days_of_week: [0, 1, 2, 3, 4, 5, 6], enabled: true,
  quiet_hours_start: "22:00:00", quiet_hours_end: "07:00:00",
};

function renderHabits(overrides: Partial<React.ComponentProps<typeof HabitsView>> = {}) {
  const onRefresh = vi.fn();
  const props = { habits: [habit], logs: [] as HabitLog[], reminders: [] as Reminder[], onRefresh, ...overrides };
  return { onRefresh, ...render(<HabitsView {...props} />) };
}

/** The day cell for a habit on a given date, addressed by its accessible name. */
function dayFor(name: string, date: string) {
  return screen.getByRole("button", { name: `${name} on ${date}` });
}

beforeEach(() => { calls = []; setAuthTokens("test-access-token"); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); setAuthTokens(null, null); });

describe("habit check-ins", () => {
  it("checks a day in and refreshes on success", async () => {
    stubFetch(200, log(todayISO()));
    const { onRefresh } = renderHabits();
    const today = todayISO();

    fireEvent.click(dayFor("Drink water", today));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].method).toBe("POST");
    expect(calls[0].url).toContain("/habit-logs");
    // The client sends its own calendar date, not a UTC-derived one.
    expect(calls[0].body.log_date).toBe(today);
    expect(calls[0].body.completed).toBe(true);
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
  });

  it("surfaces a failed check-in instead of refreshing silently", async () => {
    stubFetch(400, { message: "habit is not active" });
    const { onRefresh } = renderHabits();

    fireEvent.click(dayFor("Drink water", todayISO()));

    // Previously the error was discarded and onRefresh ran anyway.
    await screen.findByText("That check-in could not be saved. Please try again.");
    expect(onRefresh).not.toHaveBeenCalled();
  });

  it("clears the busy state after a failed check-in", async () => {
    stubFetch(400);
    renderHabits();
    const cell = dayFor("Drink water", todayISO());

    fireEvent.click(cell);
    await screen.findByText("That check-in could not be saved. Please try again.");
    // A row left disabled could never be retried.
    await waitFor(() => expect((dayFor("Drink water", todayISO()) as HTMLButtonElement).disabled).toBe(false));
  });

  it("removes a check-in by deleting the log, and refreshes", async () => {
    const today = todayISO();
    stubFetch(200, {});
    const existing = log(today, "log-1");
    const { onRefresh } = renderHabits({ logs: [existing] });

    fireEvent.click(dayFor("Drink water", today));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].method).toBe("DELETE");
    expect(calls[0].url).toContain("/habit-logs/log-1");
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
  });

  it("surfaces a failed un-check without refreshing", async () => {
    const today = todayISO();
    stubFetch(500);
    const { onRefresh } = renderHabits({ logs: [log(today, "log-1")] });

    fireEvent.click(dayFor("Drink water", today));

    await screen.findByText("That check-in could not be removed. Please try again.");
    expect(onRefresh).not.toHaveBeenCalled();
  });
});

describe("habit create and delete", () => {
  function openNewHabitDialog() {
    fireEvent.click(screen.getByRole("button", { name: /new habit/i }));
    fireEvent.change(screen.getByPlaceholderText(/e\.g\./i), { target: { value: "Read more" } });
  }

  it("creates a habit and refreshes on success", async () => {
    stubFetch(200, habit);
    const { onRefresh } = renderHabits();
    openNewHabitDialog();
    fireEvent.click(screen.getByRole("button", { name: /^save habit$/i }));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].method).toBe("POST");
    expect(calls[0].body.name).toBe("Read more");
    expect(calls[0].body.target_per_week).toBe(7);
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
  });

  it("surfaces a failed create and leaves the dialog open", async () => {
    stubFetch(400, { message: "target_per_week is required" });
    const { onRefresh } = renderHabits();
    openNewHabitDialog();
    fireEvent.click(screen.getByRole("button", { name: /^save habit$/i }));

    await screen.findByText("That habit could not be saved. Please try again.");
    expect(onRefresh).not.toHaveBeenCalled();
  });

  it("always clears Saving, even when the request throws", async () => {
    // A thrown request used to leave the button stuck on "Saving" for good.
    vi.spyOn(globalThis, "fetch").mockImplementation(async () => { throw new Error("network down"); });
    renderHabits();
    openNewHabitDialog();
    fireEvent.click(screen.getByRole("button", { name: /^save habit$/i }));

    await screen.findByText("That habit could not be saved. Please try again.");
    await waitFor(() => expect((screen.getByRole("button", { name: /^save habit$/i }) as HTMLButtonElement).disabled).toBe(false));
  });

  it("refuses to submit a blank name without calling the server", async () => {
    stubFetch(200, habit);
    renderHabits();
    fireEvent.click(screen.getByRole("button", { name: /new habit/i }));
    fireEvent.click(screen.getByRole("button", { name: /^save habit$/i }));

    await screen.findByText("Give the habit a name.");
    expect(calls).toHaveLength(0);
  });

  it("deletes a habit and closes the dialog only after it succeeds", async () => {
    stubFetch(200, {});
    const { onRefresh } = renderHabits();

    fireEvent.click(screen.getByLabelText("Delete Drink water"));
    fireEvent.click(within(screen.getByText("Delete habit?").closest(".modal") as HTMLElement)
      .getByRole("button", { name: "Delete habit" }));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].method).toBe("DELETE");
    expect(calls[0].url).toContain("/habits/h1");
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByText("Delete habit?")).toBeNull());
  });

  it("keeps the dialog open and shows the error when a delete fails", async () => {
    stubFetch(500);
    const { onRefresh } = renderHabits();

    fireEvent.click(screen.getByLabelText("Delete Drink water"));
    fireEvent.click(within(screen.getByText("Delete habit?").closest(".modal") as HTMLElement)
      .getByRole("button", { name: "Delete habit" }));

    // Previously the dialog closed regardless, so a failed delete looked like it worked.
    await screen.findByText("That habit could not be removed. Please try again.");
    expect(screen.getByText("Delete habit?")).toBeTruthy();
    expect(onRefresh).not.toHaveBeenCalled();
  });

});

describe("reminder actions", () => {
  it("disables a reminder and refreshes on success", async () => {
    stubFetch(200, { ...reminder, enabled: false });
    const { onRefresh } = renderHabits({ reminders: [reminder] });

    fireEvent.click(screen.getByRole("button", { name: "Disable" }));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].method).toBe("PUT");
    expect(calls[0].url).toContain("/reminders/r1");
    expect(calls[0].body.enabled).toBe(false);
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
  });

  it("surfaces a failed reminder toggle instead of refreshing silently", async () => {
    stubFetch(500);
    const { onRefresh } = renderHabits({ reminders: [reminder] });

    fireEvent.click(screen.getByRole("button", { name: "Disable" }));

    await screen.findByText("That reminder could not be updated. Please try again.");
    expect(onRefresh).not.toHaveBeenCalled();
  });

  it("surfaces a failed reminder delete without refreshing", async () => {
    stubFetch(500);
    const { onRefresh } = renderHabits({ reminders: [reminder] });

    fireEvent.click(screen.getByLabelText("Delete reminder"));

    await screen.findByText("That reminder could not be removed. Please try again.");
    expect(onRefresh).not.toHaveBeenCalled();
  });

  it("deletes a reminder and refreshes on success", async () => {
    stubFetch(200, {});
    const { onRefresh } = renderHabits({ reminders: [reminder] });

    fireEvent.click(screen.getByLabelText("Delete reminder"));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].method).toBe("DELETE");
    expect(calls[0].url).toContain("/reminders/r1");
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
  });

  it("always clears Saving when creating a reminder throws", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(async () => { throw new Error("network down"); });
    renderHabits();
    fireEvent.click(screen.getByRole("button", { name: /add reminder/i }));
    fireEvent.change(screen.getByPlaceholderText(/e\.g\. time to train/i), { target: { value: "Stretch" } });
    fireEvent.click(screen.getByRole("button", { name: /^save reminder$/i }));

    await screen.findByText("That reminder could not be saved. Please try again.");
    await waitFor(() => expect((screen.getByRole("button", { name: /^save reminder$/i }) as HTMLButtonElement).disabled).toBe(false));
  });
});

describe("streak label", () => {
  it("describes the number it actually shows as the current streak", () => {
    // The screen showed the current streak under a "longest active streak" label.
    renderHabits({ logs: [log(todayISO()), log(dateOffset(1)), log(dateOffset(2))] });
    expect(screen.getByText("current streak")).toBeTruthy();
    expect(screen.queryByText(/longest/i)).toBeNull();
  });
});
