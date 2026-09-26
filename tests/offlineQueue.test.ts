import { beforeEach, describe, expect, it } from "vitest";
import { installFakeIndexedDb } from "./fakeIndexedDb";
import {
  classify,
  clear,
  enqueue,
  listAll,
  MAX_ATTEMPTS,
  newOpId,
  update,
} from "../src/lib/offline/mutationQueue";
import { drainQueue, pendingCount } from "../src/lib/offline/syncEngine";
import type { QueuedOperation } from "../src/lib/offline/mutationQueue";

beforeEach(() => {
  installFakeIndexedDb();
});

async function queueOne(overrides: Partial<QueuedOperation> = {}): Promise<QueuedOperation> {
  return enqueue({
    opId: newOpId(),
    resource: "/workout-sessions",
    method: "POST",
    payload: { title: "Offline session" },
    ...overrides,
  } as Parameters<typeof enqueue>[0]);
}

describe("offline mutation queue", () => {
  it("enqueues an operation with the metadata needed for a safe retry", async () => {
    const op = await queueOne();
    expect(op.state).toBe("pending");
    expect(op.attempts).toBe(0);
    expect(op.createdAt).toBeGreaterThan(0);
    const stored = await listAll();
    expect(stored).toHaveLength(1);
    expect(stored[0].opId).toBe(op.opId);
  });

  it("persists across queue instances and preserves creation order", async () => {
    const first = await queueOne();
    await new Promise((r) => setTimeout(r, 2));
    const second = await queueOne();
    const ordered = await listAll();
    expect(ordered.map((o) => o.opId)).toEqual([first.opId, second.opId]);
  });

  it("never stores credentials in the queue", async () => {
    const op = await queueOne({ payload: { title: "Leg day" } } as Partial<QueuedOperation>);
    const raw = JSON.stringify(await listAll());
    expect(raw).not.toMatch(/access_token|refresh_token|password|authorization/i);
    expect(op.payload).toEqual({ title: "Leg day" });
  });

  it("generates distinct idempotency keys", () => {
    const keys = new Set(Array.from({ length: 50 }, () => newOpId()));
    expect(keys.size).toBe(50);
  });
});

describe("sync classification", () => {
  it("treats conflicts as a distinct, user-visible state", () => {
    expect(classify(409, 1)).toBe("conflict");
  });

  it("retries transient failures only within the attempt budget", () => {
    expect(classify(0, 1)).toBe("pending");
    expect(classify(503, 2)).toBe("pending");
    expect(classify(503, MAX_ATTEMPTS)).toBe("failed");
  });

  it("fails immediately on a non-retryable client error", () => {
    expect(classify(400, 1)).toBe("failed");
    expect(classify(401, 1)).toBe("failed");
  });
});

describe("offline sync", () => {
  it("sends queued operations once and removes the item only after confirmed success", async () => {
    const op = await queueOne();
    const sent: string[] = [];
    const summary = await drainQueue(async (o) => {
      sent.push(o.opId);
      return 200;
    });
    expect(sent).toEqual([op.opId]);
    expect(summary.synced).toBe(1);
    expect(await listAll()).toHaveLength(0);
  });

  it("keeps the item when the server has not confirmed the write", async () => {
    await queueOne();
    // A 202 is a non-2xx failure here on purpose: only 2xx confirms.
    await drainQueue(async () => 100);
    expect(await listAll()).toHaveLength(1);
  });

  it("keeps a transiently failing operation queued and increments its attempt count", async () => {
    const op = await queueOne();
    const summary = await drainQueue(async () => 503);
    expect(summary.failed).toBe(0);
    const [stored] = await listAll();
    expect(stored.state).toBe("pending");
    expect(stored.attempts).toBe(1);
    expect(stored.lastError).toBe("http_503");
    expect(stored.opId).toBe(op.opId);
  });

  it("parks an operation permanently once the retry budget is exhausted", async () => {
    await queueOne();
    for (let i = 0; i < MAX_ATTEMPTS; i += 1) await drainQueue(async () => 500);
    const [stored] = await listAll();
    expect(stored.state).toBe("failed");
    expect(stored.attempts).toBe(MAX_ATTEMPTS);
  });

  it("surfaces a conflict instead of silently overwriting server state", async () => {
    await queueOne();
    const summary = await drainQueue(async () => 409);
    const [stored] = await listAll();
    expect(summary.conflicts).toBe(1);
    expect(stored.state).toBe("conflict");
  });

  it("preserves ordering by stopping the drain on a transient failure", async () => {
    const first = await queueOne();
    await new Promise((r) => setTimeout(r, 2));
    const second = await queueOne();
    const seen: string[] = [];
    await drainQueue(async (o) => {
      seen.push(o.opId);
      return o.opId === first.opId ? 503 : 200;
    });
    // The second operation must not overtake the blocked first one.
    expect(seen).toEqual([first.opId]);
    const stored = await listAll();
    expect(stored.find((o) => o.opId === second.opId)?.state).toBe("pending");
  });

  it("reports the pending count for the sync indicator", async () => {
    await queueOne();
    expect(await pendingCount()).toBe(1);
    await clear();
    expect(await pendingCount()).toBe(0);
  });

  it("does not resend an operation already parked as failed", async () => {
    await queueOne();
    await update((await listAll())[0].opId, { state: "failed" });
    const seen: string[] = [];
    await drainQueue(async (o) => {
      seen.push(o.opId);
      return 200;
    });
    expect(seen).toEqual([]);
  });
});
