/**
 * Connectivity and sync state for the offline indicator.
 *
 * <p>Derived from the browser's online signal plus the queue contents, so the UI can show a
 * truthful Online / Offline / Syncing / Pending / Failed / Conflict state without polling the
 * server. Kept separate from the views so it can be unit tested.
 */
import { listAll, type QueuedOperation } from "./mutationQueue";

export type SyncState = "online" | "offline" | "syncing" | "pending" | "failed" | "conflict";

export interface SyncStatus {
  state: SyncState;
  pending: number;
  failed: number;
  conflicts: number;
}

function isOnline(): boolean {
  return typeof navigator === "undefined" ? true : navigator.onLine !== false;
}

export function describe(queue: QueuedOperation[], online: boolean, syncing: boolean): SyncStatus {
  const pending = queue.filter((op) => op.state === "pending" || op.state === "syncing").length;
  const conflicts = queue.filter((op) => op.state === "conflict").length;
  const failed = queue.filter((op) => op.state === "failed").length;

  if (!online) return { state: "offline", pending, failed, conflicts };
  if (syncing) return { state: "syncing", pending, failed, conflicts };
  if (conflicts > 0) return { state: "conflict", pending, failed, conflicts };
  if (failed > 0) return { state: "failed", pending, failed, conflicts };
  if (pending > 0) return { state: "pending", pending, failed, conflicts };
  return { state: "online", pending, failed, conflicts };
}

/** Current status, for the indicator component. */
export async function currentStatus(syncing = false): Promise<SyncStatus> {
  return describe(await listAll(), isOnline(), syncing);
}

/** Human-readable label; kept in one place so the UI stays consistent. */
export function label(status: SyncStatus): string {
  switch (status.state) {
    case "offline":
      return status.pending > 0 ? `Offline - ${status.pending} pending` : "Offline";
    case "syncing":
      return "Syncing";
    case "pending":
      return `${status.pending} pending`;
    case "failed":
      return `${status.failed} failed`;
    case "conflict":
      return `${status.conflicts} conflict${status.conflicts === 1 ? "" : "s"}`;
    default:
      return "Online";
  }
}
