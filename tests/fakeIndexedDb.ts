/**
 * Minimal in-memory IndexedDB stand-in for tests.
 *
 * <p>Node has no IndexedDB, and the queue must be exercised against a real asynchronous store
 * rather than a stubbed module. Only the surface the queue uses is implemented: open, one
 * object store, get/put/delete/getAll/clear.
 */

type Row = Record<string, unknown>;

class FakeRequest<T> {
  onsuccess: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onupgradeneeded: (() => void) | null = null;
  result!: T;
  error: unknown = null;
}

class FakeObjectStore {
  constructor(private rows: Map<string, Row>, private indexes: Set<string>) {}

  createIndex(name: string) { this.indexes.add(name); return name; }

  put(row: Row) {
    const request = new FakeRequest<void>();
    queueMicrotask(() => {
      this.rows.set(String(row.opId), { ...row });
      request.result = undefined as unknown as void;
      request.onsuccess?.();
    });
    return request as unknown as IDBRequest<void>;
  }

  delete(key: IDBValidKey) {
    const request = new FakeRequest<void>();
    queueMicrotask(() => {
      this.rows.delete(String(key));
      request.result = undefined as unknown as void;
      request.onsuccess?.();
    });
    return request as unknown as IDBRequest<void>;
  }

  getAll() {
    const request = new FakeRequest<Row[]>();
    queueMicrotask(() => {
      request.result = [...this.rows.values()];
      request.onsuccess?.();
    });
    return request as unknown as IDBRequest<Row[]>;
  }

  clear() {
    const request = new FakeRequest<void>();
    queueMicrotask(() => {
      this.rows.clear();
      request.result = undefined as unknown as void;
      request.onsuccess?.();
    });
    return request as unknown as IDBRequest<void>;
  }
}

class FakeDb {
  rows = new Map<string, Row>();
  indexes = new Set<string>();
  objectStoreNames = { contains: () => true };
  transaction() {
    const db = this;
    return {
      objectStore: () => new FakeObjectStore(db.rows, db.indexes),
      oncomplete: null as (() => void) | null,
    } as unknown as IDBTransaction;
  }
  close() { /* nothing to release */ }
}

let database: FakeDb | null = null;

export function installFakeIndexedDb(): void {
  database = new FakeDb();
  (globalThis as unknown as { indexedDB: unknown }).indexedDB = {
    open() {
      const request = new FakeRequest<FakeDb>();
      queueMicrotask(() => {
        request.result = database as unknown as FakeDb;
        request.onsuccess?.();
      });
      return request as unknown as IDBOpenDBRequest;
    },
  };
}
