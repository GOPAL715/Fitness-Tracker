// @vitest-environment jsdom
/**
 * Profile save behaviour, driven through the real component and the real data adapter.
 *
 * <p>The HTTP layer is not stubbed: fetch is intercepted, so these assert the actual method, URL and
 * payload the user would put on the wire, and that the save button never gets stuck.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { Profile } from "../src/lib/domain";

// ProfileView reads the session through context it does not otherwise use, so it is stubbed rather
// than standing up the whole provider and its token-refresh side effects.
vi.mock("../src/lib/auth", () => ({ useAuth: () => ({ session: { user: { email: "sam@example.test" } } }) }));

import ProfileView, { formToPayload, profileToForm } from "../src/views/ProfileView";

const profile: Profile = {
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
};

const SAVE_FAILED = "Your changes could not be saved. Please try again.";

type Call = { url: string; method: string; body: Record<string, unknown> | undefined };
let calls: Call[] = [];

function stubFetch(status: number, body: unknown) {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({
      url: String(input),
      method: init?.method ?? "GET",
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    });
    return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
  });
}

function renderProfile(overrides?: Partial<Profile>, onRefresh = vi.fn()) {
  return render(
    <ProfileView profile={{ ...profile, ...overrides }} devices={[]} notifications={[]} onRefresh={onRefresh} />
  );
}

const saveButton = () => screen.getByRole("button", { name: /save changes/i });
const field = (label: string) => screen.getByLabelText(label) as HTMLInputElement;

beforeEach(() => { calls = []; });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("Profile save: request shape", () => {
  it("PUTs the full profile to the row endpoint", async () => {
    stubFetch(200, { ...profile, display_name: "Sam Rivera" });

    renderProfile();
    fireEvent.change(field("Name"), { target: { value: "Sam Rivera" } });
    fireEvent.click(saveButton());

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].method).toBe("PUT");
    // The trailing .eq("id", ...) is what produces the row URL rather than the collection URL.
    expect(calls[0].url).toContain(`/fitness-profile/${profile.id}`);
    expect(calls[0].body).toEqual({
      display_name: "Sam Rivera",
      goal: "Build strength",
      fitness_level: "Intermediate",
      equipment: "Full gym",
      limitations: "None",
      activity_target: 4,
      weekly_minutes: 180,
      sleep_target_hours: 8,
      step_target: 10000,
      calorie_target: 2400,
      protein_target_g: 150,
      water_target_oz: 100,
      target_weight_lb: 175,
    });
  });

  it("never sends a user_id, so the client cannot claim ownership", async () => {
    stubFetch(200, profile);
    renderProfile();
    fireEvent.click(saveButton());

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].body).not.toHaveProperty("user_id");
  });

  it("sends numeric fields as numbers rather than strings", async () => {
    stubFetch(200, profile);
    renderProfile();
    fireEvent.click(saveButton());

    await waitFor(() => expect(calls).toHaveLength(1));
    for (const name of ["activity_target", "sleep_target_hours", "target_weight_lb"]) {
      expect(typeof calls[0].body?.[name]).toBe("number");
    }
  });
});

describe("Profile save: loading state", () => {
  it("confirms and clears the saving state on success", async () => {
    stubFetch(200, { ...profile, display_name: "Sam Rivera" });
    const onRefresh = vi.fn();
    renderProfile(undefined, onRefresh);

    fireEvent.change(field("Name"), { target: { value: "Sam Rivera" } });
    fireEvent.click(saveButton());

    await screen.findByText("Saved");
    expect(saveButton().disabled).toBe(false);
    expect(onRefresh).toHaveBeenCalled();
  });

  it("surfaces a safe error and re-enables the button when the server rejects the save", async () => {
    stubFetch(400, { status: 400, code: "invalid_request", message: "activity_target must be between 1 and 14" });

    renderProfile();
    fireEvent.click(saveButton());

    // A user-safe message, not the raw server text.
    await screen.findByText(SAVE_FAILED);
    // The button must never be left stuck on "Saving…".
    await waitFor(() => expect(saveButton().disabled).toBe(false));
  });

  it("re-enables the button when the network is unavailable", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("Failed to fetch"));

    renderProfile();
    fireEvent.click(saveButton());

    await screen.findByText(SAVE_FAILED);
    await waitFor(() => expect(saveButton().disabled).toBe(false));
  });

  it("re-enables the button when the adapter itself throws", async () => {
    // A guard against a regression where a rejected chain would skip the finally block.
    const broken = {
      from: () => {
        const chain: Record<string, unknown> = {};
        chain.update = () => chain;
        chain.eq = () => chain;
        chain.then = () => { throw new Error("boom"); };
        return chain;
      },
    };
    vi.doMock("../src/lib/api/dataAdapter", () => ({ apiData: broken }));
    vi.resetModules();
    const Fresh = (await import("../src/views/ProfileView")).default;
    stubFetch(200, profile);

    render(<Fresh profile={profile} devices={[]} notifications={[]} onRefresh={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await screen.findByText(SAVE_FAILED);
    expect((screen.getByRole("button", { name: /save changes/i }) as HTMLButtonElement).disabled).toBe(false);
    vi.doUnmock("../src/lib/api/dataAdapter");
  });

  it("explains itself instead of failing silently when there is no profile row", async () => {
    stubFetch(200, profile);
    render(<ProfileView profile={null} devices={[]} notifications={[]} onRefresh={vi.fn()} />);

    fireEvent.click(saveButton());

    await screen.findByText("Your profile is still loading. Please try again in a moment.");
    expect(calls).toHaveLength(0);
  });
});

describe("Profile form reacting to server data", () => {
  it("seeds the form from the profile it is given", () => {
    renderProfile({ display_name: "Sam Rivera", activity_target: 5, sleep_target_hours: 7.5 });
    expect(field("Name").value).toBe("Sam Rivera");
    expect(field("Workouts per week").value).toBe("5");
    expect(field("Sleep target (hours)").value).toBe("7.5");
  });

  it("picks up refreshed server data after a reload", () => {
    const { rerender } = renderProfile({ display_name: "Sam Rivera" });
    expect(field("Name").value).toBe("Sam Rivera");

    // A new server row arrives after save + refresh.
    rerender(
      <ProfileView
        profile={{ ...profile, display_name: "Sam Rivera", activity_target: 6 }}
        devices={[]}
        notifications={[]}
        onRefresh={vi.fn()}
      />
    );

    expect(field("Workouts per week").value).toBe("6");
  });

  it("does not discard unsaved edits when an unrelated profile object arrives", () => {
    const { rerender } = renderProfile();
    fireEvent.change(field("Name"), { target: { value: "Typing…" } });

    rerender(
      <ProfileView profile={{ ...profile }} devices={[]} notifications={[]} onRefresh={vi.fn()} />
    );

    // The user's in-progress edit survives a background refresh.
    expect(field("Name").value).toBe("Typing…");
  });

  it("reflects persisted values after a successful save and refresh", async () => {
    stubFetch(200, { ...profile, display_name: "Sam Rivera", activity_target: 6 });
    const view = renderProfile();

    fireEvent.change(field("Name"), { target: { value: "Sam Rivera" } });
    fireEvent.change(field("Workouts per week"), { target: { value: "6" } });
    fireEvent.click(saveButton());
    await screen.findByText("Saved");

    // The parent re-reads and hands back the persisted row.
    await act(async () => {
      view.rerender(
        <ProfileView
          profile={{ ...profile, display_name: "Sam Rivera", activity_target: 6 }}
          devices={[]}
          notifications={[]}
          onRefresh={vi.fn()}
        />
      );
    });

    expect(field("Workouts per week").value).toBe("6");
  });
});

describe("Profile form payload helpers", () => {
  it("falls back to the application defaults when a value is missing", () => {
    const form = profileToForm({ ...profile, activity_target: undefined as unknown as number });
    expect(form.activity_target).toBe(4);
    expect(form.sleep_target_hours).toBe(8);
  });

  it("coerces the numeric fields in the payload it builds", () => {
    const payload = formToPayload(profileToForm(profile));
    expect(payload.step_target).toBe(10000);
    expect(payload.target_weight_lb).toBe(175);
    expect(typeof payload.sleep_target_hours).toBe("number");
  });
});
