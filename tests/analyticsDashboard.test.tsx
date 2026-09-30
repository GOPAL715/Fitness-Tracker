// @vitest-environment jsdom
/**
 * Phase 21: the analytics layer wired into TodayView and ProgressView.
 *
 * <p>The API is stubbed at the module boundary, so these are presentation and interaction cases. The
 * properties that matter and are easy to regress are:
 *
 * <ul>
 *   <li>a null measure renders as "no data" rather than 0, so an absent reading is never reported as
 *       a real one;</li>
 *   <li>activity calories and session calories are shown separately and never added, because the
 *       server keeps them as different facts;</li>
 *   <li>a UTC fallback is disclosed rather than presented as the user's own calendar;</li>
 *   <li>loading, failure and empty are distinguishable from "has data";</li>
 *   <li>switching the trend bucket refetches, and a stale response cannot repaint the chart.</li>
 * </ul>
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

import TodayView from "../src/views/TodayView";
import ProgressView, { trendBarsFor } from "../src/views/ProgressView";
import {
  getDashboardAnalytics,
  getTrendsAnalytics,
  type DashboardAnalytics,
  type TrendsAnalytics,
} from "../src/lib/api/analyticsApi";
import type { DailyMetric, Meal, Profile, Workout, BodyMetric, PersonalRecord } from "../src/lib/domain";
import type { SessionWithDetail } from "../src/lib/workoutMetrics";
import type { Goal } from "../src/lib/types";

vi.mock("../src/lib/api/analyticsApi", async () => {
  const actual = await vi.importActual<typeof import("../src/lib/api/analyticsApi")>(
    "../src/lib/api/analyticsApi"
  );
  return { ...actual, getDashboardAnalytics: vi.fn(), getTrendsAnalytics: vi.fn() };
});

const mockDashboard = vi.mocked(getDashboardAnalytics);
const mockTrends = vi.mocked(getTrendsAnalytics);

function dashboard(overrides: Partial<DashboardAnalytics> = {}): DashboardAnalytics {
  return {
    date: "2026-05-20",
    from: "2026-05-20",
    to: "2026-05-20",
    timezone: "UTC",
    timezone_resolved: false,
    activity: {
      steps: 8200,
      active_minutes: 45,
      sleep_hours: 7.5,
      calories_burned: 2400,
      water_oz: 50,
      readiness: 78,
    },
    nutrition: { calories: 1900, protein_g: 120, carbs_g: 210, fat_g: 60, fiber_g: 28 },
    workout: { sessions: 1, minutes: 45, calories: 400, quick_logged: 1, logged_sessions: 0 },
    body: { weight_lb: 180, body_fat_pct: 18 },
    targets: {
      step_target: 10000,
      calorie_target: 2400,
      protein_target_g: 150,
      water_target_oz: 100,
      target_weight_lb: 170,
    },
    ...overrides,
  };
}

function bucket(overrides: Partial<TrendsAnalytics["buckets"][number]> = {}) {
  return {
    start: "2026-05-18",
    end: "2026-05-24",
    days: 3,
    steps: 21000,
    active_minutes: 120,
    activity_calories_burned: 7000,
    sleep_hours_avg: 7.4,
    nutrition_calories: 5200,
    protein_g: 300,
    carbs_g: 600,
    fat_g: 150,
    fiber_g: 70,
    workouts: 3,
    workout_minutes: 150,
    workout_calories: 1200,
    quick_logged: 3,
    logged_sessions: 0,
    ...overrides,
  };
}

function trends(overrides: Partial<TrendsAnalytics> = {}): TrendsAnalytics {
  return {
    from: "2026-02-20",
    to: "2026-05-20",
    bucket: "week",
    timezone: "UTC",
    timezone_resolved: false,
    buckets: [bucket()],
    ...overrides,
  };
}

const noop = () => {};
const asyncNoop = async () => {};

/** Minimal props; the analytics panel renders from the API, not from these. */
function todayProps() {
  return {
    profile: null as Profile | null,
    todayMetric: null as DailyMetric | null,
    recent: [] as DailyMetric[],
    workouts: [] as Workout[],
    meals: [] as Meal[],
    habits: [],
    habitLogs: [],
    onRefresh: noop,
    onLogWater: asyncNoop,
    onOpenWorkouts: noop,
    onOpenHabits: noop,
    onOpenGoals: noop,
  };
}

function progressProps() {
  return {
    metrics: [] as DailyMetric[],
    workouts: [] as Workout[],
    records: [] as PersonalRecord[],
    body: [] as BodyMetric[],
    sessions: [] as SessionWithDetail[],
    goals: [] as Goal[],
    onRefresh: noop,
  };
}

beforeEach(() => {
  mockDashboard.mockReset();
  mockTrends.mockReset();
});

afterEach(() => {
  cleanup();
});

/* ------------------------------------------------------------------ TodayView */

describe("TodayView analytics", () => {
  it("shows a loading state before the analytics arrive", async () => {
    let release: (value: DashboardAnalytics) => void = () => {};
    mockDashboard.mockReturnValue(new Promise<DashboardAnalytics>((r) => (release = r)));

    render(<TodayView {...todayProps()} />);

    expect(await screen.findByText("Loading today's analytics")).toBeTruthy();
    release(dashboard());
    await waitFor(() => expect(screen.queryByText("Loading today's analytics")).toBeNull());
  });

  it("renders the day's figures from the server", async () => {
    mockDashboard.mockResolvedValue(dashboard());

    render(<TodayView {...todayProps()} />);

    expect(await screen.findByText("Today from your analytics")).toBeTruthy();
    expect(screen.getByText("8,200")).toBeTruthy();
    expect(screen.getByText("steps")).toBeTruthy();
  });

  it("reports a failure and offers a retry", async () => {
    mockDashboard.mockRejectedValue(new Error("Your analytics could not be loaded."));

    render(<TodayView {...todayProps()} />);

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.getByText("Analytics unavailable")).toBeTruthy();
    expect(screen.getByText("Try again")).toBeTruthy();
  });

  it("retries when asked", async () => {
    mockDashboard.mockRejectedValueOnce(new Error("temporary")).mockResolvedValueOnce(dashboard());

    render(<TodayView {...todayProps()} />);
    fireEvent.click(await screen.findByText("Try again"));

    await waitFor(() => expect(mockDashboard).toHaveBeenCalledTimes(2));
  });

  it("distinguishes an unrecorded measure from a recorded zero", async () => {
    // workout_sessions has no calorie column, so the server sends null rather than 0.
    mockDashboard.mockResolvedValue(
      dashboard({
        workout: { sessions: 1, minutes: 45, calories: null, quick_logged: 0, logged_sessions: 1 },
      })
    );

    render(<TodayView {...todayProps()} />);

    // "Not recorded" is the session-calorie measure. The panel still shows the activity estimate,
    // so the point is that the *absent* one is never rendered as a zero.
    expect(await screen.findByText("Not recorded")).toBeTruthy();
    const absent = screen.getByText("Not recorded").closest("div")?.parentElement;
    expect(absent?.querySelector('[data-testid="measure-absent"]')).toBeTruthy();
    expect(absent?.querySelector('[data-testid="measure-value"]')).toBeNull();
  });

  it("keeps activity and session calories as separate figures", async () => {
    mockDashboard.mockResolvedValue(dashboard());

    render(<TodayView {...todayProps()} />);

    await screen.findByText("Today from your analytics");
    expect(screen.getByText("activity calories (device estimate)")).toBeTruthy();
    expect(screen.getByText("session calories (typed)")).toBeTruthy();
    // Both figures are shown, and neither is presented as their sum.
    expect(screen.getByText("2,400")).toBeTruthy();
    expect(screen.getByText("400")).toBeTruthy();
    expect(screen.queryByText("2,800")).toBeNull();
  });

  it("discloses a UTC fallback instead of implying a resolved zone", async () => {
    mockDashboard.mockResolvedValue(dashboard({ timezone: "UTC", timezone_resolved: false }));

    render(<TodayView {...todayProps()} />);

    const notice = await screen.findByTestId("analytics-timezone-notice");
    expect(notice.textContent).toContain("UTC");
  });

  it("does not show the notice when the user's own zone was used", async () => {
    mockDashboard.mockResolvedValue(
      dashboard({ timezone: "Asia/Kolkata", timezone_resolved: true })
    );

    render(<TodayView {...todayProps()} />);

    await screen.findByText("Today from your analytics");
    expect(screen.queryByTestId("analytics-timezone-notice")).toBeNull();
  });
});

/* --------------------------------------------------------------- ProgressView */

describe("ProgressView trends", () => {
  it("shows a loading state before the series arrives", async () => {
    let release: (value: TrendsAnalytics) => void = () => {};
    mockTrends.mockReturnValue(new Promise<TrendsAnalytics>((r) => (release = r)));

    render(<ProgressView {...progressProps()} />);

    expect(await screen.findByText("Loading week trends")).toBeTruthy();
    release(trends());
    await waitFor(() => expect(screen.queryByText("Loading week trends")).toBeNull());
  });

  it("renders day buckets when day is selected", async () => {
    mockTrends.mockResolvedValue(trends({ bucket: "day" }));

    render(<ProgressView {...progressProps()} />);
    fireEvent.click(await screen.findByText("day"));

    await waitFor(() =>
      expect(mockTrends).toHaveBeenCalledWith(
        expect.objectContaining({ bucket: "day" })
      )
    );
  });

  it("renders week buckets and requests them", async () => {
    mockTrends.mockResolvedValue(trends());

    render(<ProgressView {...progressProps()} />);

    expect(await screen.findByText("Trends")).toBeTruthy();
    await waitFor(() => expect(mockTrends).toHaveBeenCalled());
    expect(mockTrends.mock.calls[0][0]).toMatchObject({ bucket: "week" });
  });

  it("renders month buckets when month is selected", async () => {
    mockTrends.mockResolvedValue(trends({ bucket: "month" }));

    render(<ProgressView {...progressProps()} />);
    fireEvent.click(await screen.findByText("month"));

    await waitFor(() =>
      expect(mockTrends).toHaveBeenCalledWith(expect.objectContaining({ bucket: "month" }))
    );
  });

  it("reports an empty series as empty rather than as zeros", async () => {
    mockTrends.mockResolvedValue(trends({ buckets: [] }));

    render(<ProgressView {...progressProps()} />);

    expect(await screen.findByText("No week data yet")).toBeTruthy();
  });

  it("reports a failure without breaking the rest of the view", async () => {
    mockTrends.mockRejectedValue(new Error("trends unavailable"));

    render(<ProgressView {...progressProps()} />);

    expect(await screen.findByRole("alert")).toBeTruthy();
    // The pre-existing 14 day trend card is still there.
    expect(screen.getByText("14 day trend")).toBeTruthy();
  });

  it("marks the selected bucket for assistive technology", async () => {
    mockTrends.mockResolvedValue(trends());

    render(<ProgressView {...progressProps()} />);
    await screen.findByText("Trends");

    expect(screen.getByText("week").getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByText("day").getAttribute("aria-pressed")).toBe("false");
  });

  it("does not let a stale response repaint after the bucket changes", async () => {
    // The first (week) request is slow; the second (month) is fast. If the late week reply were
    // allowed to write state it would overwrite the month series the user actually asked for.
    let releaseWeek: (value: TrendsAnalytics) => void = () => {};
    mockTrends
      .mockReturnValueOnce(
        new Promise<TrendsAnalytics>((r) => (releaseWeek = r))
      )
      .mockResolvedValueOnce(
        trends({ bucket: "month", buckets: [bucket({ steps: 555, start: "2026-05-01" })] })
      );

    render(<ProgressView {...progressProps()} />);
    fireEvent.click(await screen.findByText("month"));

    await waitFor(() => expect(screen.getByText("555 steps")).toBeTruthy());

    // The superseded response finally lands and must be ignored.
    releaseWeek(trends({ bucket: "week", buckets: [bucket({ steps: 21000 })] }));
    await waitFor(() => expect(screen.queryByText("21000 steps")).toBeNull());
    expect(screen.getByText("555 steps")).toBeTruthy();
  });

  it("discloses a UTC fallback on the trend series", async () => {
    mockTrends.mockResolvedValue(trends({ timezone_resolved: false }));

    render(<ProgressView {...progressProps()} />);

    expect((await screen.findAllByTestId("analytics-timezone-notice")).length).toBeGreaterThan(0);
  });

  it("requests a range the backend accepts", async () => {
    mockTrends.mockResolvedValue(trends());

    render(<ProgressView {...progressProps()} />);
    await screen.findByText("Trends");

    const params = mockTrends.mock.calls[0][0] as Record<string, string>;
    expect(params.bucket).toBe("week");
    // 90 days inclusive, inside the backend's 366-day bound.
    expect(params.from).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(params.to).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    const span =
      (Date.parse(params.to) - Date.parse(params.from)) / 86_400_000 + 1;
    expect(span).toBeLessThanOrEqual(366);
  });
});

/* ---------------------------------------------------------- the bar mapping */

describe("trendBarsFor", () => {
  it("maps a bucket to a labelled bar", () => {
    const bars = trendBarsFor([bucket({ steps: 1234 })], "week");
    expect(bars).toHaveLength(1);
    expect(bars[0].value).toBe(1234);
    expect(bars[0].label.length).toBeGreaterThan(0);
  });

  it("omits a bucket with no recorded steps instead of drawing zero", () => {
    const bars = trendBarsFor(
      [bucket({ steps: null }), bucket({ steps: 900, start: "2026-04-06" })],
      "week"
    );
    expect(bars).toHaveLength(1);
    expect(bars[0].value).toBe(900);
  });

  it("labels day buckets by the day number and wider buckets by month", () => {
    expect(trendBarsFor([bucket({ steps: 100, start: "2026-05-18" })], "day")[0].label).toBe("18");
    expect(trendBarsFor([bucket({ steps: 100, start: "2026-05-18" })], "month")[0].label).toBe(
      new Date("2026-05-18T00:00:00").toLocaleDateString(undefined, { month: "short" })
    );
  });

  it("returns nothing for an empty series", () => {
    expect(trendBarsFor([], "week")).toEqual([]);
  });
});
