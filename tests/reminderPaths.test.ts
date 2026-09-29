import { describe, expect, it } from "vitest";

import { isReminderId, parseReminderPath, reminderPath } from "../src/lib/paths";

const ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

describe("reminder path parsing", () => {
  it("accepts the canonical reminder route", () => {
    expect(parseReminderPath(`/reminders/${ID}`)).toBe(ID);
  });

  it("accepts a route written with a trailing slash or a query string", () => {
    // Both are the same destination; a query string must not be able to steer navigation.
    expect(parseReminderPath(`/reminders/${ID}/`)).toBeNull();
    expect(parseReminderPath(`/reminders/${ID}?from=push`)).toBe(ID);
    expect(parseReminderPath(`/reminders/${ID}#top`)).toBe(ID);
  });

  it("returns null for every path that is not a reminder route", () => {
    for (const path of [
      "/",
      "/today",
      "/reminders",
      "/reminders/",
      "/reminders/not-a-uuid",
      "/reminders/12345",
      "/workouts",
      "/api/v1/reminders",
      `//evil.example/reminders/${ID}`,
      `https://evil.example/reminders/${ID}`,
      "",
    ]) {
      expect(parseReminderPath(path)).toBeNull();
    }
  });

  it("rejects a value that is not a path at all", () => {
    expect(parseReminderPath(undefined)).toBeNull();
    expect(parseReminderPath(null)).toBeNull();
  });

  it("never produces an off-origin path, whatever it is given", () => {
    // The property the notification click depends on.
    for (const hostile of [
      "javascript:alert(1)",
      "https://evil.example",
      "http://evil.example",
      "//evil.example",
      "../../etc/passwd",
      "/reminders/../../admin",
      "<script>alert(1)</script>",
      `/${ID}`,
      { toString: () => `https://evil.example` },
    ]) {
      const path = reminderPath(hostile as unknown);
      expect(path.startsWith("/")).toBe(true);
      expect(path.includes("evil.example")).toBe(false);
      expect(path.toLowerCase().includes("javascript:")).toBe(false);
    }
  });

  it("builds the canonical path for a valid id and the root for anything else", () => {
    expect(reminderPath(ID)).toBe(`/reminders/${ID}`);
    expect(reminderPath("nope")).toBe("/");
    expect(reminderPath(undefined)).toBe("/");
  });

  it("round-trips: a built path parses back to the same id", () => {
    // The service worker builds the path and the app parses it; they must agree.
    for (const id of [ID, ID.toUpperCase()]) {
      expect(parseReminderPath(reminderPath(id))?.toLowerCase()).toBe(id.toLowerCase());
    }
  });

  it("identifies reminder ids strictly", () => {
    expect(isReminderId(ID)).toBe(true);
    expect(isReminderId(ID.toUpperCase())).toBe(true);
    expect(isReminderId("3f2504e0-4f89-41d3-9a0c")).toBe(false);
    expect(isReminderId(`${ID}x`)).toBe(false);
    expect(isReminderId(42)).toBe(false);
  });
});
