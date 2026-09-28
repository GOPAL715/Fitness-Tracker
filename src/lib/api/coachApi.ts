import { apiClient, ApiError, json } from "./apiClient";

/** The server accepts only these windows; anything else is a 400 by design. */
export const ALLOWED_WINDOW_DAYS = [7, 30, 90] as const;
export type WindowDays = (typeof ALLOWED_WINDOW_DAYS)[number];

/** Matches the server's CoachDtos.DEFAULT_WINDOW_DAYS. */
export const DEFAULT_WINDOW_DAYS: WindowDays = 7;

/** Server-side hard limit. Enforced again on the server; mirrored here to fail fast. */
export const MAX_QUESTION_LENGTH = 500;

export type CoachInsight = {
  summary: string;
  observations: string[];
  recommendations: string[];
  next_actions: string[];
  warnings: string[];
  model: string;
  request_id: string;
};

export type CoachInsightRequest = {
  question?: string;
  window_days?: WindowDays;
};

export type AskCoachOptions = {
  question?: string;
  windowDays?: WindowDays;
  /**
   * Caller-supplied key for replay protection. A repeat of the same key is refused by the server
   * with 409 rather than billed twice, so a retried tap must reuse one key rather than mint a new
   * one for each attempt.
   */
  idempotencyKey?: string;
  signal?: AbortSignal;
};

/** A fresh key per logical ask, so a genuine second question is never mistaken for a replay. */
export function newIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return `coach-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

function validate(question: string | undefined, windowDays: WindowDays | undefined): void {
  if (question !== undefined) {
    if (question.trim().length === 0) throw new Error("Enter a question, or leave it empty for a general review.");
    if (question.length > MAX_QUESTION_LENGTH) throw new Error(`Keep your question under ${MAX_QUESTION_LENGTH} characters.`);
  }
  if (windowDays !== undefined && !ALLOWED_WINDOW_DAYS.includes(windowDays)) throw new Error("Choose a 7, 30 or 90 day window.");
}

/**
 * Asks the AI Coach for structured insights.
 *
 * <p>The response is normalised into the server contract, so a missing optional array becomes an
 * empty array here rather than a render-time crash. That is deliberately not validation: the server
 * has already range-checked every field, and this only guards the shape the renderer depends on.
 */
export async function askCoach(options: AskCoachOptions = {}): Promise<CoachInsight> {
  const { question, windowDays = DEFAULT_WINDOW_DAYS, idempotencyKey, signal } = options;
  validate(question, windowDays);

  const body: CoachInsightRequest = { window_days: windowDays };
  if (question !== undefined) body.question = question;

  const headers: Record<string, string> = {};
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;

  const raw = await apiClient<Partial<CoachInsight>>("/coach/insights", {
    method: "POST",
    ...json(body),
    headers,
    ...(signal ? { signal } : {}),
  });

  return normalise(raw);
}

function asStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

function normalise(raw: Partial<CoachInsight>): CoachInsight {
  return {
    summary: typeof raw.summary === "string" ? raw.summary : "",
    observations: asStringArray(raw.observations),
    recommendations: asStringArray(raw.recommendations),
    next_actions: asStringArray(raw.next_actions),
    warnings: asStringArray(raw.warnings),
    model: typeof raw.model === "string" ? raw.model : "",
    request_id: typeof raw.request_id === "string" ? raw.request_id : "",
  };
}

/**
 * Turns a Coach failure into a message worth showing a user.
 *
 * <p>Each status means something different and the copy says so: a quota refusal is temporary, a
 * replay means the request already ran, and a 502 means the provider side failed. Collapsing them
 * into one "something went wrong" would leave the user unable to decide whether retrying is worth it.
 */
export function coachErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error ? error.message : "Something went wrong. Please try again.";
  switch (error.status) {
    case 400: return error.message || "That request was not valid. Try a shorter question.";
    case 401: return "Please sign in again to use the Coach.";
    case 409: return "That request was already processed. Ask again to get a new answer.";
    case 429: return "You have reached the Coach request limit. Wait a minute and try again.";
    case 502: return "The Coach is temporarily unavailable. Please try again shortly.";
    case 503: return "The Coach is not configured right now. Please try again later.";
    default: return error.message || "Something went wrong. Please try again.";
  }
}
