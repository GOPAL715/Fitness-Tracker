import { describe, it, expect } from "vitest";
import { hydrateSessions, hydrateTemplates, needsOnboarding, EMPTY_APP_DATA } from "../src/features/appData/appData";
import { TABS, DEFAULT_TAB } from "../src/features/navigation/tabs";
import type { Exercise, ExerciseSet, WorkoutExercise, WorkoutSession, WorkoutTemplate, TemplateExercise } from "../src/lib/types";
import type { Profile } from "../src/lib/supabase";

/* ---------- Fixtures ---------- */

const exercise: Exercise = {
  id: "e1", name: "Bench Press", description: "", muscle_group: "Chest", secondary_muscles: [],
  equipment: "Barbell", difficulty: "Intermediate", instructions: "", is_compound: true,
};

function exerciseMap(...items: Exercise[]): Map<string, Exercise> {
  return new Map(items.map((e) => [e.id, e]));
}

function session(id: string): WorkoutSession {
  return {
    id, user_id: "u", title: "Push", workout_type: "Strength",
    started_at: "2026-09-20T10:00:00Z", completed_at: null, duration_minutes: 45,
    notes: null, perceived_effort: 7, completed: true, created_at: "",
  };
}

function we(id: string, sessionId: string, exerciseId = "e1"): WorkoutExercise {
  return { id, workout_session_id: sessionId, exercise_id: exerciseId, order_index: 0, notes: null };
}

function set(id: string, weId: string): ExerciseSet {
  return {
    id, workout_exercise_id: weId, set_number: 1, reps: 10, weight: 100, weight_unit: "lbs",
    duration_seconds: null, distance: null, distance_unit: null, rpe: null, completed: true,
  };
}

function template(id: string): WorkoutTemplate {
  return {
    id, user_id: "u", name: "Push Day", description: "", workout_type: "Strength",
    estimated_minutes: 45, is_favorite: false,
  };
}

function te(id: string, templateId: string, exerciseId = "e1"): TemplateExercise {
  return {
    id, template_id: templateId, exercise_id: exerciseId, order_index: 0,
    target_sets: 3, target_reps: "8-12", target_weight: null,
  };
}

/* ---------- Session hydration ---------- */

describe("hydrateSessions", () => {
  it("attaches exercises and their sets", () => {
    const result = hydrateSessions([session("s1")], [we("w1", "s1")], [set("x1", "w1")], exerciseMap(exercise));
    expect(result).toHaveLength(1);
    expect(result[0].exercises).toHaveLength(1);
    expect(result[0].exercises[0].exercise?.name).toBe("Bench Press");
    expect(result[0].exercises[0].sets).toHaveLength(1);
  });

  it("returns an empty exercise list for a session with none", () => {
    const result = hydrateSessions([session("s1")], [], [], exerciseMap(exercise));
    expect(result[0].exercises).toEqual([]);
  });

  it("resolves an unknown exercise to null rather than throwing", () => {
    const result = hydrateSessions([session("s1")], [we("w1", "s1", "missing")], [], exerciseMap(exercise));
    expect(result[0].exercises[0].exercise).toBeNull();
  });

  it("returns empty sets when none exist", () => {
    const result = hydrateSessions([session("s1")], [we("w1", "s1")], [], exerciseMap(exercise));
    expect(result[0].exercises[0].sets).toEqual([]);
  });

  it("does not attach another session's exercises", () => {
    const result = hydrateSessions([session("s1"), session("s2")], [we("w1", "s2")], [], exerciseMap(exercise));
    expect(result[0].exercises).toHaveLength(0);
    expect(result[1].exercises).toHaveLength(1);
  });

  it("does not attach another exercise's sets", () => {
    const result = hydrateSessions(
      [session("s1")],
      [we("w1", "s1"), we("w2", "s1")],
      [set("x1", "w1")],
      exerciseMap(exercise)
    );
    expect(result[0].exercises[0].sets).toHaveLength(1);
    expect(result[0].exercises[1].sets).toHaveLength(0);
  });

  it("handles empty input", () => {
    expect(hydrateSessions([], [], [], new Map())).toEqual([]);
  });

  it("preserves the original session fields", () => {
    const result = hydrateSessions([session("s1")], [], [], new Map());
    expect(result[0].title).toBe("Push");
    expect(result[0].perceived_effort).toBe(7);
  });
});

/* ---------- Template hydration ---------- */

describe("hydrateTemplates", () => {
  it("attaches exercises to templates", () => {
    const result = hydrateTemplates([template("t1")], [te("i1", "t1")], exerciseMap(exercise));
    expect(result[0].items).toHaveLength(1);
    expect(result[0].items[0].exercise?.id).toBe("e1");
  });

  it("resolves an unknown exercise to null", () => {
    const result = hydrateTemplates([template("t1")], [te("i1", "t1", "missing")], exerciseMap(exercise));
    expect(result[0].items[0].exercise).toBeNull();
  });

  it("does not leak items between templates", () => {
    const result = hydrateTemplates([template("t1"), template("t2")], [te("i1", "t2")], exerciseMap(exercise));
    expect(result[0].items).toHaveLength(0);
    expect(result[1].items).toHaveLength(1);
  });

  it("handles empty input", () => {
    expect(hydrateTemplates([], [], new Map())).toEqual([]);
  });

  it("preserves template fields", () => {
    const result = hydrateTemplates([template("t1")], [], new Map());
    expect(result[0].name).toBe("Push Day");
    expect(result[0].estimated_minutes).toBe(45);
  });
});

/* ---------- Onboarding detection ---------- */

describe("needsOnboarding", () => {
  function profile(name: string): Profile {
    return {
      id: "p", user_id: "u", display_name: name, goal: "Build strength",
      activity_target: 4, weekly_minutes: 180, fitness_level: "Intermediate",
      equipment: "Full gym", limitations: "None", sleep_target_hours: 8,
      step_target: 10000, calorie_target: 2400, protein_target_g: 150,
      water_target_oz: 100, target_weight_lb: 175,
    };
  }

  it("is false when there is no profile", () => {
    expect(needsOnboarding(null)).toBe(false);
  });

  it("is true for the seeded default name", () => {
    expect(needsOnboarding(profile("Alex Morgan"))).toBe(true);
  });

  it("is false once the user has set their own name", () => {
    expect(needsOnboarding(profile("Sam Rivera"))).toBe(false);
  });

  it("is false for a name that merely contains the default", () => {
    expect(needsOnboarding(profile("Alex Morgana"))).toBe(false);
  });
});

/* ---------- Empty state ---------- */

describe("EMPTY_APP_DATA", () => {
  it("uses empty arrays for every collection", () => {
    const collections = Object.entries(EMPTY_APP_DATA).filter(([key]) => key !== "profile");
    for (const [, value] of collections) {
      expect(Array.isArray(value)).toBe(true);
      expect(value).toHaveLength(0);
    }
  });

  it("has a null profile", () => {
    expect(EMPTY_APP_DATA.profile).toBeNull();
  });
});

/* ---------- Navigation config ---------- */

describe("tab configuration", () => {
  it("defines every tab expected by the shell", () => {
    const ids = TABS.map((t) => t.id);
    expect(ids).toEqual([
      "today", "workouts", "progress", "nutrition",
      "habits", "goals", "calendar", "profile",
    ]);
  });

  it("has a unique id and a label for each tab", () => {
    expect(new Set(TABS.map((t) => t.id)).size).toBe(TABS.length);
    for (const t of TABS) {
      expect(t.label.length).toBeGreaterThan(0);
      expect(t.icon).toBeTruthy();
    }
  });

  it("defaults to a tab that exists", () => {
    expect(TABS.some((t) => t.id === DEFAULT_TAB)).toBe(true);
  });
});
