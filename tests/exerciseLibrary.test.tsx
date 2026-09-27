// @vitest-environment jsdom
/**
 * Exercise Library behaviour, driven through the real component.
 *
 * <p>The library receives its data as a prop from the existing app-data read, so these tests exercise
 * the view exactly as the app mounts it. The important one is {@code secondary_muscles}: the API
 * contract declares an array and the view iterates it, so a regression to the stored CSV string would
 * throw here rather than in production.
 */
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { ExerciseLibraryView } from "../src/views/ExerciseLibraryView";
import type { Exercise } from "../src/lib/types";

function exercise(overrides: Partial<Exercise> & { id: string; name: string }): Exercise {
  return {
    description: "A standard movement",
    muscle_group: "Chest",
    secondary_muscles: [],
    equipment: "Barbell",
    difficulty: "Intermediate",
    instructions: "Brace, then press.",
    is_compound: true,
    ...overrides,
  };
}

const catalog: Exercise[] = [
  exercise({ id: "e1", name: "Bench Press", muscle_group: "Chest", equipment: "Barbell", secondary_muscles: ["Triceps", "Shoulders"] }),
  exercise({ id: "e2", name: "Squat", muscle_group: "Legs", equipment: "Barbell", secondary_muscles: ["Glutes"] }),
  exercise({ id: "e3", name: "Push-Up", muscle_group: "Chest", equipment: "Bodyweight", secondary_muscles: [] }),
  exercise({ id: "e4", name: "Running", muscle_group: "Cardio", equipment: "None", secondary_muscles: [] }),
];

const renderLibrary = (exercises: Exercise[] = catalog) =>
  render(<ExerciseLibraryView exercises={exercises} />);

/** Scope to the results area, so a name that also appears in a filter option is unambiguous. */
const results = () => screen.getByText(/exercises you can add to any workout/).closest(".flex-col") as HTMLElement;

afterEach(() => cleanup());

describe("Exercise library rendering", () => {
  it("renders every catalog exercise", () => {
    renderLibrary();
    expect(screen.getByText("Bench Press")).toBeTruthy();
    expect(screen.getByText("Squat")).toBeTruthy();
    expect(screen.getByText("Push-Up")).toBeTruthy();
    expect(screen.getByText("Running")).toBeTruthy();
  });

  it("reports the catalog size", () => {
    renderLibrary();
    expect(screen.getByText("4 exercises you can add to any workout")).toBeTruthy();
  });

  it("groups exercises by muscle group", () => {
    renderLibrary();
    // Read the group headings themselves, so a name shared with a filter option is unambiguous.
    const headings = Array.from(results().querySelectorAll("h3")).map((h) => h.textContent ?? "");
    expect(headings.some((h) => h.startsWith("Chest"))).toBe(true);
    expect(headings.some((h) => h.startsWith("Legs"))).toBe(true);
  });
});

describe("Exercise library search", () => {
  it("filters by name", () => {
    renderLibrary();
    fireEvent.change(screen.getByLabelText("Search exercises"), { target: { value: "squat" } });

    expect(screen.getByText("Squat")).toBeTruthy();
    expect(screen.queryByText("Bench Press")).toBeNull();
  });

  it("matches on muscle group", () => {
    renderLibrary();
    fireEvent.change(screen.getByLabelText("Search exercises"), { target: { value: "cardio" } });

    expect(screen.getByText("Running")).toBeTruthy();
    expect(screen.queryByText("Squat")).toBeNull();
  });

  it("matches on equipment", () => {
    renderLibrary();
    fireEvent.change(screen.getByLabelText("Search exercises"), { target: { value: "bodyweight" } });

    expect(screen.getByText("Push-Up")).toBeTruthy();
    expect(screen.queryByText("Squat")).toBeNull();
  });

  it("matches on a secondary muscle without throwing", () => {
    // The regression this guards: secondary_muscles arrived as a CSV string, so .some() threw.
    renderLibrary();
    expect(() =>
      fireEvent.change(screen.getByLabelText("Search exercises"), { target: { value: "glutes" } })
    ).not.toThrow();

    expect(screen.getByText("Squat")).toBeTruthy();
    expect(screen.queryByText("Bench Press")).toBeNull();
  });

  it("is case insensitive and ignores surrounding whitespace", () => {
    renderLibrary();
    fireEvent.change(screen.getByLabelText("Search exercises"), { target: { value: "  BENCH  " } });
    expect(screen.getByText("Bench Press")).toBeTruthy();
  });

  it("shows the empty state when nothing matches", () => {
    renderLibrary();
    fireEvent.change(screen.getByLabelText("Search exercises"), { target: { value: "zzzzz" } });

    expect(screen.getByText("No exercises match")).toBeTruthy();
    expect(screen.queryByText("Bench Press")).toBeNull();
  });
});

describe("Exercise library filters", () => {
  it("filters by muscle group", () => {
    renderLibrary();
    fireEvent.change(screen.getByLabelText("Filter by muscle group"), { target: { value: "Legs" } });

    expect(screen.getByText("Squat")).toBeTruthy();
    expect(screen.queryByText("Bench Press")).toBeNull();
  });

  it("filters by equipment", () => {
    renderLibrary();
    fireEvent.change(screen.getByLabelText("Filter by equipment"), { target: { value: "Bodyweight" } });

    expect(screen.getByText("Push-Up")).toBeTruthy();
    expect(screen.queryByText("Squat")).toBeNull();
  });

  it("combines search with a filter", () => {
    renderLibrary();
    fireEvent.change(screen.getByLabelText("Filter by muscle group"), { target: { value: "Chest" } });
    fireEvent.change(screen.getByLabelText("Search exercises"), { target: { value: "push" } });

    expect(screen.getByText("Push-Up")).toBeTruthy();
    expect(screen.queryByText("Bench Press")).toBeNull();
  });

  it("offers an All option that restores the full catalog", () => {
    renderLibrary();
    fireEvent.change(screen.getByLabelText("Filter by muscle group"), { target: { value: "Legs" } });
    fireEvent.change(screen.getByLabelText("Filter by muscle group"), { target: { value: "All" } });

    expect(screen.getByText("Squat")).toBeTruthy();
    expect(screen.getByText("Bench Press")).toBeTruthy();
  });
});

describe("Exercise library empty catalog", () => {
  it("shows the empty state without crashing when there are no exercises", () => {
    expect(() => renderLibrary([])).not.toThrow();
    expect(screen.getByText("No exercises match")).toBeTruthy();
    expect(screen.getByText("0 exercises you can add to any workout")).toBeTruthy();
  });

  it("tolerates a null secondary_muscles entry at render time", () => {
    // Defensive: a malformed row must not take the whole screen down.
    const broken = [exercise({ id: "b1", name: "Broken Row", secondary_muscles: null as unknown as string[] })];
    expect(() => renderLibrary(broken)).not.toThrow();
    expect(screen.getByText("Broken Row")).toBeTruthy();
  });
});

describe("Exercise library details", () => {
  it("opens a detail modal for the chosen exercise", () => {
    renderLibrary();
    fireEvent.click(within(results()).getByText("Bench Press"));

    expect(screen.getByText("Brace, then press.")).toBeTruthy();
    // Scoped to the modal: the description also renders on the card behind it.
    const modal = screen.getByText("Brace, then press.").closest(".modal") as HTMLElement;
    expect(within(modal).getByText("A standard movement")).toBeTruthy();
  });

  it("lists secondary muscles as individual badges", () => {
    renderLibrary();
    fireEvent.click(within(results()).getByText("Bench Press"));

    // Previously a single CSV badge, or a crash once the value became a list.
    // "Shoulders" is also a muscle-group option, so scope to the modal.
    const modal = screen.getByText("Brace, then press.").closest(".modal") as HTMLElement;
    expect(within(modal).getByText("Triceps")).toBeTruthy();
    expect(within(modal).getByText("Shoulders")).toBeTruthy();
  });

  it("omits the secondary muscle section when the list is empty", () => {
    renderLibrary();
    fireEvent.click(within(results()).getByText("Running"));
    const modal = screen.getByRole("heading", { name: "Running" }).closest(".modal") as HTMLElement;
    expect(within(modal).queryByText("Also works")).toBeNull();
  });

  it("closes the modal", () => {
    renderLibrary();
    fireEvent.click(within(results()).getByText("Bench Press"));
    const modal = screen.getByText("Brace, then press.").closest(".modal") as HTMLElement;
    expect(modal).toBeTruthy();

    fireEvent.click(within(modal).getByText("Close"));
    expect(screen.queryByText("Brace, then press.")).toBeNull();
  });
});
