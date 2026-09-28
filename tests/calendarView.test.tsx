// @vitest-environment jsdom
/**
 * The calendar, through the real component and the real adapter.
 *
 * <p>Two regressions are pinned here. The view used to be fed the whole account history and grouped
 * it in the browser, so it now reads one bounded window from the calendar summary endpoint. And an
 * undated personal record used to reach the grid as a null date key, which pinned itself to the top
 * of the history timeline and rendered as "Invalid Date".
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import CalendarView from "../src/views/CalendarView";
import { setAuthTokens } from "../src/lib/api/apiClient";
import { todayISO } from "../src/lib/utils";
import type { CalendarSummary } from "../src/lib/api/calendarApi";

type Call = { url: string; method: string };
let calls: Call[] = [];

const emptySummary = (from: string, to: string): CalendarSummary => ({
  from, to, days: [], sessions: [], meals: [], habitLogs: [], bodyMetrics: [], personalRecords: [],
});

/** Stubs the calendar summary endpoint, answering every window with the supplied activity. */
function stubSummary(summary: Partial<CalendarSummary> = {}, status = 200) {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL) => {
    const url = String(input);
    calls.push({ url, method: "GET" });
    const params = new URL(url, "http://localhost").searchParams;
    return {
      ok: status >= 200 && status < 300,
      status,
      json: async () => ({ ...emptySummary(params.get("from") ?? "", params.get("to") ?? ""), ...summary }),
    } as Response;
  });
}

const session = (date: string, title: string) => ({
  id: `s-${date}-${title}`, date, title, workout_type: "Strength", duration_minutes: 30, perceived_effort: 6,
});

/** Scopes a query to the day panel, since a title also appears in the history timeline. */
const withinDayPanel = () => within(document.querySelectorAll(".card")[1] as HTMLElement);

beforeEach(() => { calls = []; setAuthTokens("test-access-token"); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); setAuthTokens(null, null); });

describe("calendar data loading", () => {
  it("reads one bounded window from the calendar summary endpoint", async () => {
    stubSummary();
    render(<CalendarView />);

    await waitFor(() => expect(calls.length).toBeGreaterThan(0));
    expect(calls[0].url).toContain("/calendar/summary?from=");
    expect(calls[0].url).toContain("&to=");
    // The old design read the whole account history instead.
    expect(calls.some((c) => c.url.includes("app-data"))).toBe(false);
  });

  it("reloads when the month changes and cannot navigate into the future", async () => {
    stubSummary();
    render(<CalendarView />);
    await waitFor(() => expect(calls).toHaveLength(1));

    fireEvent.click(screen.getByLabelText("Previous month"));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1].url).not.toBe(calls[0].url);

    fireEvent.click(screen.getByLabelText("Next month"));
    await waitFor(() => expect(calls).toHaveLength(3));

    // The month is clamped at the current one, so a further click cannot move into the future.
    fireEvent.click(screen.getByLabelText("Next month"));
    expect(calls[2].url).toBe(calls[0].url);
  });

  it("surfaces a failed load instead of showing an empty calendar as if it were real", async () => {
    stubSummary({}, 500);
    render(<CalendarView />);
    await screen.findByText("The calendar could not be loaded. Please try again.");
  });

  it("clears the loading state once the request settles", async () => {
    stubSummary();
    render(<CalendarView />);

    await waitFor(() => expect(screen.queryByText("Loading calendar…")).toBeNull());
  });
});

describe("month grid", () => {
  it("renders every day of the current month", async () => {
    stubSummary();
    render(<CalendarView />);
    await waitFor(() => expect(calls).toHaveLength(1));

    const now = new Date();
    const daysInMonth = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate();
    expect(screen.getByText(now.toLocaleDateString(undefined, { month: "long", year: "numeric" }))).toBeTruthy();
    expect(document.querySelectorAll(".cal-cell")).toHaveLength(daysInMonth);
  });

  it("marks today and never allows selecting a future day", async () => {
    stubSummary();
    render(<CalendarView />);
    await waitFor(() => expect(calls).toHaveLength(1));

    expect(screen.getByLabelText(todayISO()).className).toContain("cal-cell-today");
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    const lastDay = new Date(tomorrow.getFullYear(), tomorrow.getMonth() + 1, 0).getDate();
    if (tomorrow.getDate() <= lastDay) {
      const pad = (n: number) => String(n).padStart(2, "0");
      const iso = `${tomorrow.getFullYear()}-${pad(tomorrow.getMonth() + 1)}-${pad(tomorrow.getDate())}`;
      expect((screen.getByLabelText(iso) as HTMLButtonElement).disabled).toBe(true);
    }
  });
});
describe("activity rendering", () => {
  it("shows day detail for the selected date", async () => {
    const d = todayISO();
    stubSummary({
      sessions: [session(d, "Morning lift")],
      meals: [{ id: "m1", date: d, meal_type: "LUNCH", name: "Lunch", calories: 500 }],
      personalRecords: [{ id: "p1", date: d, exercise: "Bench", record_value: 225, unit: "lbs" }],
    });
    render(<CalendarView />);
    await waitFor(() => expect(calls).toHaveLength(1));

    fireEvent.click(screen.getByLabelText(d));
    expect(withinDayPanel().getByText("Morning lift")).toBeTruthy();
    expect(screen.getByText(/1 meal logged/)).toBeTruthy();
    expect(screen.getByText(/1 personal record/)).toBeTruthy();
  });

  it("reports an empty day rather than inventing activity", async () => {
    stubSummary();
    render(<CalendarView />);
    await waitFor(() => expect(calls).toHaveLength(1));

    expect(screen.getByText("Nothing recorded this day")).toBeTruthy();
  });

  it("shows several activity types on one day", async () => {
    const d = todayISO();
    stubSummary({
      sessions: [session(d, "Lift")],
      meals: [{ id: "m1", date: d, meal_type: "DINNER", name: "Dinner", calories: 600 }],
      habitLogs: [{ id: "h1", date: d, name: "Water" }],
      personalRecords: [{ id: "p1", date: d, exercise: "Squat", record_value: 315, unit: "lbs" }],
      bodyMetrics: [{ id: "b1", date: d, weight_lb: 180, body_fat_pct: 15, waist_in: 32 }],
    });
    render(<CalendarView />);
    await waitFor(() => expect(calls).toHaveLength(1));

    fireEvent.click(screen.getByLabelText(d));
    expect(withinDayPanel().getByText("Lift")).toBeTruthy();
    expect(screen.getByText(/1 habit completed/)).toBeTruthy();
    expect(screen.getByText(/Body measurement/)).toBeTruthy();
  });
});
describe("recent history", () => {
  it("orders events newest first", async () => {
    stubSummary({ sessions: [session("2026-01-05", "Older"), session("2026-01-20", "Newer")] });
    render(<CalendarView />);
    await screen.findByText("Recent history");

    const titles = Array.from(document.querySelectorAll(".timeline-row")).map((r) => r.textContent ?? "");
    expect(titles[0]).toContain("Newer");
    expect(titles[1]).toContain("Older");
  });

  it("caps the timeline at forty entries", async () => {
    // Descending dates, so the cap keeps the newest ones.
    const sessions = Array.from({ length: 55 }, (_, i) =>
      session(`2026-03-${String(55 - i).padStart(2, "0")}`, `Session ${i}`),
    );
    stubSummary({ sessions });
    render(<CalendarView />);
    await screen.findByText("Recent history");

    expect(document.querySelectorAll(".timeline-row")).toHaveLength(40);
    expect(screen.getByText("Session 0")).toBeTruthy();
    expect(screen.queryByText("Session 54")).toBeNull();
  });

  it("reports an empty history rather than showing nothing", async () => {
    stubSummary();
    render(<CalendarView />);

    expect(await screen.findByText("No history yet")).toBeTruthy();
  });
});

describe("undated personal records", () => {
  it("never renders an undated record as Invalid Date or as the newest event", async () => {
    // The server excludes undated records, so a stale client could still hand one over.
    stubSummary({
      personalRecords: [
        { id: "p1", date: "2026-03-10", exercise: "Bench", record_value: 225, unit: "lbs" },
        { id: "p2", date: null as unknown as string, exercise: "Ghost lift", record_value: 999, unit: "lbs" },
      ],
    });
    render(<CalendarView />);
    await screen.findByText("Recent history");

    expect(screen.queryByText(/Invalid Date/)).toBeNull();
    expect(screen.queryByText(/Ghost lift/)).toBeNull();
  });
});

describe("filters", () => {
  it("hides activity that does not match the selected filter", async () => {
    const d = todayISO();
    stubSummary({
      sessions: [session(d, "Lift")],
      meals: [{ id: "m1", date: d, meal_type: "LUNCH", name: "Lunch", calories: 400 }],
    });
    render(<CalendarView />);
    await waitFor(() => expect(calls).toHaveLength(1));

    fireEvent.click(screen.getByLabelText(d));
    expect(withinDayPanel().getByText("Lift")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Nutrition" }));
    await waitFor(() => expect(screen.queryByText("Lift")).toBeNull());
    expect(withinDayPanel().getByText(/1 meal logged/)).toBeTruthy();
  });
});
