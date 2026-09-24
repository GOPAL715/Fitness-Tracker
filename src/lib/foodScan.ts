/**
 * Validates the structured response returned by the AI vision provider.
 *
 * Model output is untrusted input: anything that does not match the expected
 * shape is rejected rather than coerced, so malformed results can never reach
 * the database or the UI as though they were real data.
 */

export type AiDetection = { name: string; estimated_grams: number; confidence: number };

export type ParsedDetections = { ok: true; items: AiDetection[] } | { ok: false; reason: string };

const MAX_ITEMS = 12;
const MAX_NAME_LENGTH = 80;

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

export function parseDetections(raw: unknown): ParsedDetections {
  if (typeof raw === "string") {
    try {
      return parseDetections(JSON.parse(raw));
    } catch {
      return { ok: false, reason: "The analysis response was not valid JSON." };
    }
  }
  if (!isObject(raw)) return { ok: false, reason: "The analysis response was empty or malformed." };

  const list = raw.items;
  if (!Array.isArray(list)) return { ok: false, reason: "The analysis response did not contain a list of foods." };
  if (list.length === 0) return { ok: false, reason: "No food was detected in that photo. Try a clearer shot." };
  if (list.length > MAX_ITEMS) return { ok: false, reason: "Too many items were detected. Please try a simpler photo." };

  const items: AiDetection[] = [];
  for (const entry of list) {
    if (!isObject(entry)) continue;

    if (typeof entry.name !== "string") continue;
    const name = entry.name.trim().slice(0, MAX_NAME_LENGTH);
    if (name.length < 2) continue;

    const grams = Number(entry.estimated_grams);
    if (!Number.isFinite(grams) || grams <= 0 || grams > 5000) continue;

    const rawConf = Number(entry.confidence);
    const confidence = Number.isFinite(rawConf) ? Math.min(1, Math.max(0, rawConf)) : 0.5;

    items.push({ name, estimated_grams: Math.round(grams), confidence: Math.round(confidence * 1000) / 1000 });
  }

  if (items.length === 0) return { ok: false, reason: "No usable food items were found in the analysis." };
  return { ok: true, items };
}

export function confidenceLabel(confidence: number | null): { text: string; tone: "high" | "medium" | "low" } {
  const c = confidence ?? 0;
  if (c >= 0.8) return { text: "High confidence", tone: "high" };
  if (c >= 0.55) return { text: "Medium confidence", tone: "medium" };
  return { text: "Low confidence", tone: "low" };
}

export function needsReview(items: { confidence: number | null }[]): boolean {
  return items.some((i) => (i.confidence ?? 0) < 0.55);
}


