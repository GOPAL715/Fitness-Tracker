export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL as string | undefined)?.replace(/\/$/, "") ?? "http://localhost:8080/api/v1";

const storage = typeof localStorage === "undefined" ? null : localStorage;
let accessToken: string | null = storage?.getItem("fittrack_access_token") ?? null;
let refreshToken: string | null = storage?.getItem("fittrack_refresh_token") ?? null;
let onUnauthorized: (() => void) | null = null;

export class ApiError extends Error {
  constructor(public status: number, message: string, public code?: string, public details?: unknown) {
    super(message); this.name = "ApiError";
  }
}

export function setAuthTokens(access: string | null, refresh?: string | null) {
  accessToken = access; refreshToken = refresh ?? refreshToken;
  if (access) storage?.setItem("fittrack_access_token", access);
  else storage?.removeItem("fittrack_access_token");
  if (refresh !== undefined) {
    if (refresh) storage?.setItem("fittrack_refresh_token", refresh);
    else storage?.removeItem("fittrack_refresh_token");
  }
}
export function getAccessToken() { return accessToken; }
export function getRefreshToken() { return refreshToken; }
export function setUnauthorizedHandler(handler: () => void) { onUnauthorized = handler; }

async function refreshSession() {
  if (!refreshToken) return false;
  try {
    const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ refreshToken }),
    });
    if (!response.ok) return false;
    const body = unwrap<any>(await response.json());
    setAuthTokens(body.access_token ?? body.accessToken, body.refresh_token ?? body.refreshToken);
    return true;
  } catch { return false; }
}

function unwrap<T>(value: any): T {
  return (value?.data ?? value?.result ?? value) as T;
}

export async function apiClient<T = unknown>(path: string, options: RequestInit & { retry?: boolean } = {}): Promise<T> {
  const headers = new Headers(options.headers);
  if (!(options.body instanceof FormData) && options.body !== undefined && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
  let response: Response;
  try { response = await fetch(`${API_BASE_URL}${path.startsWith("/") ? path : `/${path}`}`, { ...options, headers }); }
  catch { throw new ApiError(0, "Unable to reach the server. Check your connection and try again."); }
  if (response.status === 401 && options.retry !== false && await refreshSession()) return apiClient<T>(path, { ...options, retry: false });
  if (response.status === 401) { setAuthTokens(null, null); onUnauthorized?.(); }
  const payload = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) throw new ApiError(response.status, payload?.message ?? payload?.error ?? "The request failed.", payload?.code, payload);
  return unwrap<T>(payload);
}

export const json = (body: unknown): RequestInit => ({ body: JSON.stringify(body) });

