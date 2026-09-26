/**
 * The application's offline-capable mutation workflow for workout sessions.
 *
 * <p>Online, the existing composite REST endpoint is called directly. Offline, the identical
 * request - same path, method, payload and idempotency key - is queued in IndexedDB and replayed
 * when connectivity returns. Nothing credential-related is ever placed in the queue.
 */
import { API_BASE_URL, apiClient, json } from "../api/apiClient";
import { enqueue, newOpId, type QueuedOperation } from "./mutationQueue";
import { drainQueue } from "./syncEngine";

export const WORKOUT_SESSION_RESOURCE = "/workout-sessions/complete";

export type SubmitResult = { mode: "online" } | { mode: "offline"; opId: string };

function isOnline(): boolean {
  return typeof navigator === "undefined" ? true : navigator.onLine !== false;
}

/**
 * Submits a workout session, queuing it when the network is unavailable.
 *
 * @returns which path was taken, so the UI can tell the user the session is pending
 */
export async function submitWorkoutSession(payload: unknown): Promise<SubmitResult> {
  if (isOnline()) {
    await apiClient(WORKOUT_SESSION_RESOURCE, { method: "POST", ...json(payload) });
    return { mode: "online" };
  }
  // Offline: persist the exact backend request so it can be replayed verbatim.
  const op = await enqueue({
    opId: newOpId(),
    resource: WORKOUT_SESSION_RESOURCE,
    method: "POST",
    payload,
  });
  return { mode: "offline", opId: op.opId };
}

/**
 * Replays queued workout-session operations.
 *
 * <p>The idempotency key is sent on every attempt, so replaying the same operation twice resolves
 * to the single aggregate the server already created.
 */
export async function syncWorkoutSessions(): Promise<{ synced: number; failed: number; conflicts: number }> {
  const summary = await drainQueue(async (op: QueuedOperation) => {
    try {
      const response = await fetch(`${API_BASE_URL}${op.resource}`, {
        method: op.method,
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": op.idempotencyKey ?? op.opId,
        },
        body: JSON.stringify(op.payload),
      });
      return response.status;
    } catch {
      return 0;
    }
  });
  return { synced: summary.synced, failed: summary.failed, conflicts: summary.conflicts };
}
