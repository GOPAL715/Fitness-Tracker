import { beforeEach, describe, expect, it } from "vitest";
import { installFakeIndexedDb } from "./fakeIndexedDb";
import { describe as describeStatus, label } from "../src/lib/offline/syncStatus";
import type { QueuedOperation } from "../src/lib/offline/mutationQueue";

function op(overrides: Partial<QueuedOperation>): QueuedOperation {
  return {
    opId: "op1",
    resource: "/workouts",
    method: "POST",
    payload: {},
    createdAt: 0,
    attempts: 0,
    state: "pending",
    ...overrides,
  } as QueuedOperation;
}

describe("sync status", () => {
  it("reports online when the queue is empty and the network is up", () => {
    expect(describeStatus([], true, false).state).toBe("online");
  });

  it("reports offline regardless of queue contents when the network is down", () => {
    expect(describeStatus([op({})], false, false).state).toBe("offline");
  });

  it("reports syncing while a drain is in flight", () => {
    expect(describeStatus([op({ state: "syncing" })], true, true).state).toBe("syncing");
  });

  it("surfaces pending, failed and conflict as distinct user-visible states", () => {
    expect(describeStatus([op({ state: "pending" })], true, false).state).toBe("pending");
    expect(describeStatus([op({ state: "failed" })], true, false).state).toBe("failed");
    expect(describeStatus([op({ state: "conflict" })], true, false).state).toBe("conflict");
  });

  it("counts each state and never hides a conflict behind a generic error", () => {
    const status = describeStatus(
      [op({ opId: "a", state: "pending" }), op({ opId: "b", state: "conflict" }), op({ opId: "c", state: "failed" })],
      true,
      false
    );
    expect(status).toEqual({ state: "conflict", pending: 1, failed: 1, conflicts: 1 });
  });

  it("renders a readable label for every state", () => {
    expect(label(describeStatus([], true, false))).toBe("Online");
    expect(label(describeStatus([op({})], false, false))).toBe("Offline - 1 pending");
    expect(label(describeStatus([op({})], true, true))).toBe("Syncing");
    expect(label(describeStatus([op({ state: "pending" })], true, false))).toBe("1 pending");
    expect(label(describeStatus([op({ state: "failed" })], true, false))).toBe("1 failed");
    expect(label(describeStatus([op({ state: "conflict" })], true, false))).toBe("1 conflict");
  });
});
