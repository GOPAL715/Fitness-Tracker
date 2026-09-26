/**
 * Durable offline mutation queue backed by IndexedDB.
 *
 * <p>localStorage is deliberately not used: it is synchronous, string-only and unsuitable for a
 * queue that must survive reloads and retries. Credentials never enter this store - only
 * non-sensitive operation metadata and payloads.
 */

export type QueuedState = "pending" | "syncing" | "failed" | "conflict";

export interface QueuedOperation {
  /** Client-generated idempotency key, stable across retries. */
  opId: string;
  resource: string;
  method: "POST" | "PUT" | "PATCH" | "DELETE";
  payload: unknown;
  /**
   * Idempotency key sent to the server so a retried submission resolves to the original
   * aggregate instead of creating a duplicate. Same value as {@link opId} unless overridden.
   */
  idempotencyKey?: string;
  createdAt: number;
  attempts: number;
  lastError?: string;
  state: QueuedState;
}

const DB_NAME = "fittrack-offline";
const DB_VERSION = 1;
const STORE = "mutations";

/** Maximum attempts before an operation is parked as permanently failed. */
export const MAX_ATTEMPTS = 5;

function hasIndexedDb(): boolean {
  return typeof indexedDB !== "undefined";
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE)) {
        const store = db.createObjectStore(STORE, { keyPath: "opId" });
        // Ordering is by creation time, so the queue replays in the order the user acted.
        store.createIndex("createdAt", "createdAt", { unique: false });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function tx<T>(mode: IDBTransactionMode, work: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return openDb().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const transaction = db.transaction(STORE, mode);
        const request = work(transaction.objectStore(STORE));
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
        transaction.oncomplete = () => db.close();
      })
  );
}

/** Generates a collision-resistant idempotency key without needing crypto.randomUUID. */
export function newOpId(): string {
  const random = Math.random().toString(36).slice(2, 10);
  return `op_${Date.now().toString(36)}_${random}`;
}

export async function enqueue(
  operation: Omit<QueuedOperation, "createdAt" | "attempts" | "state">
): Promise<QueuedOperation> {
  const record: QueuedOperation = {
    ...operation,
    // The op id doubles as the server idempotency key, so every retry of the same
    // logical operation carries the same key.
    idempotencyKey: operation.idempotencyKey ?? operation.opId,
    createdAt: Date.now(),
    attempts: 0,
    state: "pending",
  };
  await tx("readwrite", (store) => store.put(record));
  return record;
}

/** All queued operations in creation order. */
export async function listAll(): Promise<QueuedOperation[]> {
  if (!hasIndexedDb()) return [];
  const all = await tx<QueuedOperation[]>("readonly", (store) => store.getAll());
  return [...all].sort((a, b) => a.createdAt - b.createdAt);
}

export async function update(opId: string, patch: Partial<QueuedOperation>): Promise<void> {
  const existing = (await listAll()).find((op) => op.opId === opId);
  if (!existing) return;
  await tx("readwrite", (store) => store.put({ ...existing, ...patch }));
}

export async function remove(opId: string): Promise<void> {
  await tx("readwrite", (store) => store.delete(opId));
}

export async function clear(): Promise<void> {
  await tx("readwrite", (store) => store.clear());
}

/**
 * Decides what to do after one delivery attempt.
 *
 * <p>Retryable failures are retried until MAX_ATTEMPTS, then parked as failed so a permanent
 * problem is never retried forever. A conflict is surfaced rather than being retried blindly,
 * because silently overwriting server state would lose data.
 */
export function classify(status: number, attempts: number): QueuedState {
  if (status === 409) return "conflict";
  const retryable = status === 0 || status === 408 || status === 429 || status >= 500;
  if (!retryable) return "failed";
  return attempts >= MAX_ATTEMPTS ? "failed" : "pending";
}
