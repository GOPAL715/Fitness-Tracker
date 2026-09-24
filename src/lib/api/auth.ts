import { apiClient, json, setAuthTokens, getRefreshToken, ApiError } from "./apiClient";

export type AuthUser = { id: string; email: string; enabled?: boolean; [key: string]: unknown };
export type AuthSession = { accessToken: string; refreshToken?: string | null; user: AuthUser };
type AuthResponse = { access_token: string; refresh_token: string; expires_at?: string; token_type?: string; user?: AuthUser };

function session(value: AuthResponse): AuthSession {
  return { accessToken: value.access_token, refreshToken: value.refresh_token, user: value.user ?? { id: "", email: "" } };
}
export async function register(email: string, password: string) { const value = await apiClient<AuthResponse>("/auth/register", { method: "POST", ...json({ email, password }) }); const result = session(value); setAuthTokens(result.accessToken, result.refreshToken); return result; }
export async function login(email: string, password: string) { const value = await apiClient<AuthResponse>("/auth/login", { method: "POST", ...json({ email, password }) }); const result = session(value); setAuthTokens(result.accessToken, result.refreshToken); return result; }
export async function refresh(refreshToken: string) { return apiClient<AuthResponse>("/auth/refresh", { method: "POST", ...json({ refreshToken }) }); }
export async function me(): Promise<AuthUser> { return apiClient<AuthUser>("/auth/me"); }
export async function logout() { const currentRefreshToken = getRefreshToken(); try { await apiClient<void>("/auth/logout", { method: "POST", ...json({ refreshToken: currentRefreshToken }) }); } finally { setAuthTokens(null, null); } }
export function authError(error: unknown): string {
  const message = error instanceof ApiError ? error.message : (error as Error)?.message ?? "";
  const m = message.toLowerCase();
  if (m.includes("invalid") || m.includes("credentials") || m.includes("unauthorized")) return "That email or password is not correct.";
  if (m.includes("already") || m.includes("exists")) return "An account already exists with that email. Try signing in instead.";
  if (m.includes("password")) return "Your password must be at least 6 characters.";
  if (m.includes("email")) return "Please enter a valid email address.";
  if (!message || m.includes("network") || m.includes("fetch") || m.includes("reach")) return "We could not reach the server. Check your connection and try again.";
  return "Something went wrong. Please try again.";
}

