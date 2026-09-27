import { apiClient, json, ApiError } from "./client";

type Row = Record<string, any>;
type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

/** The single result shape every chain resolves to, so callers never need a try/catch. */
export type QueryResult<T = any> = { data: T; error: ApiError | null };

type Query = {
  filters: Array<[string, unknown]>;
  orderBy?: [string, boolean];
  limitCount?: number;
  upsert: boolean;
  single: boolean;
  /** Set by insert/update/upsert/delete, and read only when the chain is awaited. */
  mutation?: { method: Method; payload?: Row | Row[] };
};

/** Chainable builder. Every method returns the same object, so a filter can follow a mutation. */
type Builder = {
  select(columns?: string): Builder;
  maybeSingle(): Builder;
  eq(column: string, value: unknown): Builder;
  in(column: string, values: unknown): Builder;
  gte(column: string, value: unknown): Builder;
  order(column: string, options?: { ascending?: boolean }): Builder;
  limit(count: number): Builder;
  insert(payload: Row | Row[]): Builder;
  update(payload: Row): Builder;
  /** The second argument is accepted for call-site compatibility; the conflict target is not sent. */
  upsert(payload: Row, options?: { onConflict?: string }): Builder;
  delete(): Builder;
} & PromiseLike<QueryResult>;

function query(table: string): Builder {
  const q: Query = { filters: [], upsert: false, single: false };
  const resource = table.replace(/_/g, "-");

  const api: Builder = {
    select() { return api; },
    // Reads only record intent here; the request is issued on await, so a filter chained after this
    // call is still applied. This is what makes .insert(...).select().maybeSingle() work.
    maybeSingle() { q.single = true; return api; },
    eq(column, value) { q.filters.push([column, value]); return api; },
    in() { return api; },
    gte() { return api; },
    order(column, options) { q.orderBy = [column, options?.ascending !== false]; return api; },
    limit(count) { q.limitCount = count; return api; },
    // A mutation records intent and returns the builder rather than firing immediately, so a
    // trailing .eq() still contributes the resource id to the path.
    insert(payload) { q.mutation = { method: "POST", payload }; return api; },
    update(payload) { q.mutation = { method: "PUT", payload }; return api; },
    upsert(payload) { q.upsert = true; q.mutation = { method: "PUT", payload }; return api; },
    delete() { q.mutation = { method: "DELETE" }; return api; },
    /**
     * Makes the chain awaitable, and awaiting it is what performs the request.
     *
     * <p>This is the whole point of the module: were update() to fire eagerly, a following .eq()
     * would arrive too late to affect the path.
     */
    then(onFulfilled, onRejected) { return execute().then(onFulfilled, onRejected); },
  };

  async function execute(): Promise<QueryResult> {
    try {
      const response = await send();
      const data = q.single ? (Array.isArray(response) ? response[0] : response) : response;
      return { data, error: null };
    } catch (cause) {
      // A failure is reported through `error` rather than thrown, because every existing caller
      // destructures { data, error }. The message is the server's own already-safe text.
      const error = cause instanceof ApiError ? cause : new ApiError(0, "The request failed. Please try again.");
      return { data: null, error };
    }
  }

  async function send(): Promise<unknown> {
    const mutation = q.mutation;
    if (!mutation) {
      const queryString = q.filters
        .map(([column, value]) => `${encodeURIComponent(column)}=${encodeURIComponent(String(value))}`)
        .join("&");
      return apiClient(`/${resource}${queryString ? `?${queryString}` : ""}`);
    }
    const first = q.filters[0];
    const path = first && mutation.method !== "POST"
      ? `/${resource}/${encodeURIComponent(String(first[1]))}`
      : `/${resource}`;
    const payload = mutation.payload === undefined
      ? undefined
      : q.upsert && Array.isArray(mutation.payload) ? mutation.payload[0] : mutation.payload;
    return apiClient(path, { method: mutation.method, ...(payload !== undefined ? json(payload) : {}) });
  }

  return api;
}
export const apiData = { from: query };

export * from '../domain';


