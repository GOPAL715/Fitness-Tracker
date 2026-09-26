/**
 * Drains the offline queue once connectivity returns.
 *
 * <p>Operations replay in creation order. A failure stops the drain so later operations cannot
 * overtake an earlier one for the same aggregate, and permanent failures are parked rather than
 * retried forever. Credentials are never read from here: the queue holds payloads only and the
 * transport is supplied by the caller.
 */
import {
  classify,
  listAll,
  MAX_ATTEMPTS,
  remove,
  update,
  type QueuedOperation,
} from "./mutationQueue";

export interface SyncSummary {
  synced: number;
  failed: number;
  conflicts: number;
  remaining: number;
}

/** Transport supplied by the caller so this module stays free of fetch/auth concerns. */
export type Sender = (op: QueuedOperation) => Promise<number>;

export async function drainQueue(send: Sender): Promise<SyncSummary> {
  const summary: SyncSummary = { synced: 0, failed: 0, conflicts: 0, remaining: 0 };
  const queue = await listAll();

  for (const op of queue) {
    // Only pending work participates; a parked failure or conflict is left untouched.
    if (op.state !== "pending") {
      summary.remaining += 1;
      continue;
    }

    await update(op.opId, { state: "syncing" });
    const attempts = op.attempts + 1;
    let status = 0;
    try {
      status = await send(op);
    } catch {
      status = 0;
    }

    // 2xx means the server confirmed and committed the write, so the item is now safe to drop.
    if (status >= 200 && status < 300) {
      await remove(op.opId);
      summary.synced += 1;
      continue;
    }

    const next = classify(status, attempts);
    if (next === "pending") {
      // Transient failure: keep it queued and stop, preserving order for this drain.
      await update(op.opId, { state: "pending", attempts, lastError: `http_${status}` });
      summary.remaining += 1;
      break;
    }
    if (next === "conflict") {
      // Never silently overwrite: the operation stays visible for the user to resolve.
      await update(op.opId, { state: "conflict", attempts, lastError: "conflict" });
      summary.conflicts += 1;
      summary.remaining += 1;
      continue;
    }
    // Permanent failure, or the retry budget is now spent: park it, never loop forever.
    await update(op.opId, { state: "failed", attempts, lastError: `http_${status}` });
    summary.failed += 1;
    summary.remaining += 1;
    void MAX_ATTEMPTS;
  }

  return summary;
}

/** Aggregated queue state for the sync indicator. */
export async function pendingCount(): Promise<number> {
  return (await listAll()).filter((op) => op.state === "pending" || op.state === "syncing").length;
}
