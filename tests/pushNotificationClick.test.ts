/**
 * The service worker's notification click destination.
 *
 * Phase 14 changed this deliberately. FitTrack has no URL-based SPA routing and no reminder detail
 * route, so a payload-supplied path like /reminders/{id} implied deep-linking the app cannot do. The
 * destination is now the app root, and these tests pin that plus the property that actually matters
 * for security: a payload can never influence where the browser is sent.
 *
 * The worker source is read directly, as in serviceWorker.test.ts, rather than executed: it
 * references `self`, which does not exist outside a worker context.
 */
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const swSource = readFileSync("public/sw.js", "utf8");

describe("service worker push handling", () => {
  it("sends every notification click to the app root, not a reminder path", () => {
    // The concrete regression this guards: a future edit reintroducing /reminders/{id}, which the SPA
    // would silently ignore while looking like a working deep link.
    expect(swSource).not.toMatch(/return\s+`\/reminders/);
    expect(swSource).not.toMatch(/openWindow\(`\/reminders/);
  });

  it("validates a reminder id without using it for navigation", () => {
    // The validation is retained so a malformed or hostile payload is rejected on its own terms,
    // even though the result is the same either way today.
    expect(swSource).toMatch(/^\s*if \(typeof reminderId !== "string" \|\| !\/\^\[0-9a-fA-F-\]\{1,64\}\$\/\.test\(reminderId\)\) return "\/";$/m);
  });

  it("focuses an existing same-origin window before opening a new one", () => {
    // Requirement: focus when a window exists, open "/" when none does, and never leave the origin.
    expect(swSource).toMatch(/new URL\(client\.url\)\.origin === self\.location\.origin/);
    expect(swSource).toMatch(/clients\.openWindow\(target\)/);
  });

  it("never navigates to a URL taken from the payload", () => {
    // The open-redirect defence: the only path source is safePath, which is a constant.
    const navigations = swSource.match(/openWindow\(([^)]*)\)|\.navigate\(([^)]*)\)/g) ?? [];
    expect(navigations.length).toBeGreaterThan(0);
    for (const call of navigations) {
      expect(call).toMatch(/\((target)\)/);
      expect(call).not.toMatch(/payload|reminderId|event\.notification\.data\.url/);
    }
  });

  it("renders a notification from the parsed payload", () => {
    expect(swSource).toMatch(/addEventListener\("push"/);
    expect(swSource).toMatch(/showNotification\(payload\.title/);
  });
});
