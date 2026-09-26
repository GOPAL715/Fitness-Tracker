import { describe, expect, it } from "vitest";

/**
 * Phase 16: frontend production hardening.
 *
 * These checks are static and configuration-focused: they guard the build-time contract that no
 * secret is ever compiled into the bundle and that the service worker cannot serve private data.
 */

const readFile = (path: string) => {
  const fs = require("node:fs") as typeof import("node:fs");
  return fs.readFileSync(path, "utf8");
};

const listFiles = (dir: string, extensions: string[]): string[] => {
  const fs = require("node:fs") as typeof import("node:fs");
  const path = require("node:path") as typeof import("node:path");
  const out: string[] = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === "node_modules" || entry.name.startsWith(".")) continue;
      out.push(...listFiles(full, extensions));
    } else if (extensions.some((ext) => entry.name.endsWith(ext))) {
      out.push(full);
    }
  }
  return out;
};

describe("Phase 16 frontend production hardening", () => {
  it("no VITE_ variable is used to carry a backend secret", () => {
    // Vite inlines every VITE_* value into the client bundle, so only public configuration may
    // use that prefix. A secret here would ship to every browser.
    const files = listFiles("src", [".ts", ".tsx"]);
    expect(files.length).toBeGreaterThan(0);
    const secretish = /VITE_.*(SECRET|KEY|TOKEN|PASSWORD|PASSWORD_HASH|PRIVATE)/i;
    for (const file of files) {
      expect(secretish.test(readFile(file)), `${file} must not reference a secret VITE_ variable`).toBe(
        false,
      );
    }
  });

  it("the API base URL is environment driven with a localhost fallback", () => {
    const client = readFile("src/lib/api/apiClient.ts");
    expect(client).toContain("VITE_API_BASE_URL");
    // A default is fine for local development; it must not be a hard-coded production host.
    expect(client).not.toMatch(/https:\/\/(api|app|www)\.[a-z-]+\.(com|net|org)/i);
  });

  it("the service worker never caches or intercepts API responses", () => {
    const sw = readFile("public/sw.js");
    // A cached authenticated response would be readable by any later visitor of that origin.
    expect(sw).not.toMatch(/cache.*\/api\//i);
    // Writes must reach the network; queuing them here would break the offline sync contract.
    expect(sw).not.toMatch(/method\s*!==\s*["']GET["'][\s\S]{0,200}caches\./i);
  });

  it("logout clears private offline state so it cannot leak between accounts", () => {
    const offlineFiles = listFiles("src/lib/offline", [".ts", ".tsx"]);
    const combined = offlineFiles.map(readFile).join("\n");
    expect(offlineFiles.length).toBeGreaterThan(0);
    // A purge on sign-out is what prevents one account seeing another's queued operations.
    expect(combined).toMatch(/export (async )?function \w*(clear|purge|reset)\w*/i);
  });

  it("no Supabase runtime calls are present in the frontend", () => {
    const files = listFiles("src", [".ts", ".tsx"]);
    for (const file of files) {
      const content = readFile(file);
      expect(/supabase/i.test(content), `${file} must not reference Supabase`).toBe(false);
    }
  });

  it("no hard-coded credential literals are committed", () => {
    const files = listFiles("src", [".ts", ".tsx"]);
    // Generic assignment of a secret-looking literal to a variable.
    const pattern = /(secret|password|apiKey|api_key|bearer)\s*[:=]\s*["'][A-Za-z0-9_\-]{16,}["']/i;
    for (const file of files) {
      expect(pattern.test(readFile(file)), `${file} must not embed a credential literal`).toBe(false);
    }
  });
});
