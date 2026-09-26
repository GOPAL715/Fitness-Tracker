/** Read-only service worker source, used to assert cache-isolation rules. */
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const source = readFileSync("public/sw.js", "utf8");

describe("service worker cache isolation", () => {
  it("never caches API responses, so no user data is shared between accounts", () => {
    expect(source).toMatch(/isPrivateRequest/);
    // The private-request check must run before any cache write.
    const guardAt = source.indexOf("if (isPrivateRequest(url)) return;");
    const cacheWriteAt = source.indexOf("cache.put(");
    expect(guardAt).toBeGreaterThan(-1);
    expect(cacheWriteAt).toBeGreaterThan(guardAt);
    expect(source).toMatch(/url\.pathname\.startsWith\("\/api\/"\)/);
  });

  it("does not intercept writes, so queued offline mutations bypass the worker", () => {
    expect(source).toMatch(/if \(request\.method !== "GET"\) return;/);
  });

  it("only stores same-origin basic responses", () => {
    expect(source).toMatch(/response\.type === "basic" && sameOrigin/);
  });

  it("purges every cache on sign-out so no stale data survives a user switch", () => {
    expect(source).toMatch(/PURGE_USER_DATA/);
    expect(source).toMatch(/caches\.keys\(\)\.then\(\(keys\) => Promise\.all\(keys\.map\(\(k\) => caches\.delete\(k\)\)\)\)/);
  });

  it("drops caches from previous versions on activation", () => {
    expect(source).toMatch(/!k\.startsWith\(VERSION\)/);
  });
});

describe("service worker never stores credentials", () => {
  it("references no token, password or health data", () => {
    expect(source).not.toMatch(/access_token|refresh_token|Authorization:/i);
  });
});
