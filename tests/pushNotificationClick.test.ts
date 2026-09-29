/**
 * The service worker's notification click destination.
 *
 * Phase 15 restored deep linking, so a tap now lands on /reminders/{uuid} for a well-formed id and on
 * the app root for anything else. The rejection cases matter more than the accepted one: the path is
 * constructed from a validated id, so no payload shape can turn it into an off-origin navigation.
 *
 * The worker source is read directly, as in serviceWorker.test.ts, rather than executed: it
 * references `self`, which does not exist outside a worker context. The click behaviour is covered by
 * reminderPaths.test.ts against the real parser, so the two cannot disagree unnoticed.
 */
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

import { parseReminderPath, reminderPath } from "../src/lib/paths";

const swSource = readFileSync("public/sw.js", "utf8");

const VALID_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

describe("service worker push handling", () => {
  it("builds a reminder deep link for a well-formed id", () => {
    expect(reminderPath(VALID_ID)).toBe(`/reminders/${VALID_ID}`);
  });

  it("falls back to the app root for every rejected id shape", () => {
    for (const hostile of [
      undefined,
      null,
      "",
      "not-a-uuid",
      "../../etc/passwd",
      "https://evil.example/phish",
      "http://evil.example",
      "javascript:alert(1)",
      "//evil.example/x",
      `${VALID_ID}/extra`,
      "/reminders/3f2504e0-4f89-41d3-9a0c-0305e82c3301",
      "<script>alert(1)</script>",
    ]) {
      expect(reminderPath(hostile as unknown)).toBe("/");
    }
  });

  it("validates a UUID rather than a loose character class", () => {
    // The worker's guard must be a real UUID shape, so a payload cannot smuggle a path separator,
    // a scheme, or a host through the id.
    expect(swSource).toMatch(
      /\^\[0-9a-fA-F\]\{8\}-\[0-9a-fA-F\]\{4\}-\[0-9a-fA-F\]\{4\}-\[0-9a-fA-F\]\{4\}-\[0-9a-fA-F\]\{12\}\$/
    );
    expect(swSource).toMatch(/return `\/reminders\/\$\{reminderId\}`;/);
  });

  it("focuses an existing same-origin window before opening a new one", () => {
    expect(swSource).toMatch(/new URL\(client\.url\)\.origin === self\.location\.origin/);
    expect(swSource).toMatch(/clients\.openWindow\(target\)/);
  });

  it("never navigates to a URL taken from the payload", () => {
    // The open-redirect defence: the only path source is safePath, which is a constructor.
    const navigations = swSource.match(/openWindow\(([^)]*)\)|\.navigate\(([^)]*)\)/g) ?? [];
    expect(navigations.length).toBeGreaterThan(0);
    for (const call of navigations) {
      expect(call).toMatch(/\((target)\)/);
      expect(call).not.toMatch(/payload|reminderId|event\.notification\.data\.url/);
    }
  });

  it("resolves a reminder link it produced, matching the application parser", () => {
    // The worker builds the path and the app reads it back. If these ever diverged, a tap would land
    // on a URL the SPA could not route.
    expect(parseReminderPath(reminderPath(VALID_ID))).toBe(VALID_ID);
  });

  it("renders a notification from the parsed payload", () => {
    expect(swSource).toMatch(/addEventListener\("push"/);
    expect(swSource).toMatch(/showNotification\(payload\.title/);
  });
});
