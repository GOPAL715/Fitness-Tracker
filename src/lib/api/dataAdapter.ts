import { apiClient, json } from "./client";

type Row = Record<string, any>;
type Query = { filters: Array<[string, unknown]>; orderBy?: [string, boolean]; limitCount?: number; upsert?: boolean; single: boolean };
function query(table: string): any {
  let q: Query = { filters: [], single: false };
  const api = {
    select() { return api; }, maybeSingle() { q.single = true; return done(); },
    eq(k: string, v: unknown) { q.filters.push([k, v]); return api; },
    in() { return api; }, gte() { return api; },
    order(k: string, options?: { ascending?: boolean }) { q.orderBy = [k, options?.ascending !== false]; return api; },
    limit(n: number) { q.limitCount = n; return api; },
    async insert(payload: Row | Row[]) { const body = Array.isArray(payload) ? payload : [payload]; return done("POST", body); },
    async update(payload: Row) { return done("PUT", payload); },
    async upsert(payload: Row) { return done("PUT", payload); },
    async delete() { return done("DELETE"); },
  };
  type Body = Row | Row[];
  async function done(method = "GET", body?: Body) {
    let response: any;
    const resource = table.replace(/_/g, "-");
    if (method === "GET") {
      const queryString = q.filters.map(([k,v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`).join("&");
      response = await apiClient(`/${resource}${queryString ? `?${queryString}` : ""}`);
    } else {
      const first = q.filters[0];
      const path = first && method !== "POST" ? `/${resource}/${encodeURIComponent(String(first[1]))}` : `/${resource}`;
      response = await apiClient(path, { method, ...(body ? json(q.upsert && Array.isArray(body) ? body[0] : body) : {}) });
    }
    const data = q.single ? (Array.isArray(response) ? response[0] : response) : response;
    return { data, error: null };
  }
  return api;
}
export const apiData = { from: query };

export * from '../domain';


