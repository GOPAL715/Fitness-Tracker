/**
 * analyze-food-photo
 *
 * Identifies foods and estimates portions from a photo, then resolves nutrition
 * from the `foods` table. The AI key never leaves the server, the model is only
 * asked for names and grams (never calories), and its response is validated
 * before use.
 */

import { createClient } from "npm:@supabase/supabase-js@2.45.4";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Client-Info, Apikey",
};

const MAX_ITEMS = 12;
const MAX_NAME_LENGTH = 80;

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

type Detection = { name: string; estimated_grams: number; confidence: number };

function parseDetections(raw: unknown): { ok: true; items: Detection[] } | { ok: false; reason: string } {
  let parsed = raw;
  if (typeof raw === "string") {
    try {
      parsed = JSON.parse(raw);
    } catch {
      return { ok: false, reason: "not_json" };
    }
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    return { ok: false, reason: "malformed" };
  }

  const list = (parsed as Record<string, unknown>).items;
  if (!Array.isArray(list) || list.length === 0) return { ok: false, reason: "no_items" };
  if (list.length > MAX_ITEMS) return { ok: false, reason: "too_many" };

  const items: Detection[] = [];
  for (const entry of list) {
    if (typeof entry !== "object" || entry === null || Array.isArray(entry)) continue;
    const row = entry as Record<string, unknown>;
    if (typeof row.name !== "string") continue;
    const name = row.name.trim().slice(0, MAX_NAME_LENGTH);
    if (name.length < 2) continue;
    const grams = Number(row.estimated_grams);
    if (!Number.isFinite(grams) || grams <= 0 || grams > 5000) continue;
    const rawConf = Number(row.confidence);
    const confidence = Number.isFinite(rawConf) ? Math.min(1, Math.max(0, rawConf)) : 0.5;
    items.push({ name, estimated_grams: Math.round(grams), confidence: Math.round(confidence * 1000) / 1000 });
  }

  if (items.length === 0) return { ok: false, reason: "no_usable_items" };
  return { ok: true, items };
}

type FoodRow = { id: string; name: string; [k: string]: unknown };

function matchFood(name: string, foods: FoodRow[]): FoodRow | null {
  const needle = name.trim().toLowerCase();
  if (!needle) return null;
  const exact = foods.find((f) => f.name.toLowerCase() === needle);
  if (exact) return exact;
  const partial = foods.find((f) => f.name.toLowerCase().includes(needle) || needle.includes(f.name.toLowerCase()));
  if (partial) return partial;
  const words = needle.split(/\s+/).filter((w) => w.length > 3);
  let best: { food: FoodRow; score: number } | null = null;
  for (const f of foods) {
    const fname = f.name.toLowerCase();
    const score = words.reduce((s, w) => (fname.includes(w) ? s + 1 : s), 0);
    if (score > 0 && (!best || score > best.score)) best = { food: f, score };
  }
  return best?.food ?? null;
}

const round = (n: number, d = 1) => Math.round(n * 10 ** d) / 10 ** d;

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 200, headers: corsHeaders });

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const anonKey = Deno.env.get("SUPABASE_ANON_KEY")!;
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const aiKey = Deno.env.get("OPENAI_API_KEY");
    const visionModel = Deno.env.get("AI_VISION_MODEL") ?? "gpt-4o-mini";

    const authHeader = req.headers.get("Authorization") ?? "";
    if (!authHeader) return json({ error: "Missing authorization." }, 401);

    const userClient = createClient(supabaseUrl, anonKey, { global: { headers: { Authorization: authHeader } } });
    const { data: userData, error: userError } = await userClient.auth.getUser();
    if (userError || !userData.user) return json({ error: "Your session has expired. Please sign in again." }, 401);
    const userId = userData.user.id;

    const body = await req.json().catch(() => null);
    const scanId = body?.scan_id;
    if (typeof scanId !== "string" || !scanId) return json({ error: "A scan id is required." }, 400);

    const admin = createClient(supabaseUrl, serviceKey);

    const { data: scan, error: scanError } = await admin
      .from("food_scans")
      .select("id, user_id, image_path")
      .eq("id", scanId)
      .maybeSingle();

    if (scanError || !scan) return json({ error: "That scan could not be found." }, 404);
    if (scan.user_id !== userId) return json({ error: "That scan does not belong to you." }, 403);

    if (!aiKey) {
      await admin.from("food_scans").update({ status: "failed", error_message: "AI provider is not configured." }).eq("id", scanId);
      return json(
        { error: "AI analysis is not configured yet. Add an OPENAI_API_KEY secret to enable photo scanning.", code: "AI_NOT_CONFIGURED" },
        503
      );
    }

    await admin.from("food_scans").update({ status: "processing" }).eq("id", scanId);

    const { data: signed, error: signError } = await admin.storage.from("food-images").createSignedUrl(scan.image_path, 300);
    if (signError || !signed?.signedUrl) {
      await admin.from("food_scans").update({ status: "failed", error_message: "The meal photo could not be read." }).eq("id", scanId);
      return json({ error: "The meal photo could not be read. Please upload it again." }, 500);
    }

    const prompt = [
      "You identify food from a photograph for a nutrition tracker.",
      "Identify each distinct food component separately, including separate components of mixed dishes",
      "such as rice, dal, curry, curd, salad or bread served together.",
      "Estimate the edible portion weight of each component in grams.",
      'Respond with JSON only in exactly this shape: {"items":[{"name":"Cooked white rice","estimated_grams":200,"confidence":0.93}]}',
      "confidence is a number from 0 to 1 for how sure you are of the identification.",
      "Do not include calories or macronutrients. Only names, grams and confidence.",
    ].join(" ");

    const aiResponse = await fetch("https://api.openai.com/v1/chat/completions", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${aiKey}` },
      body: JSON.stringify({
        model: visionModel,
        response_format: { type: "json_object" },
        max_tokens: 700,
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: prompt },
              { type: "image_url", image_url: { url: signed.signedUrl } },
            ],
          },
        ],
      }),
    });

    if (!aiResponse.ok) {
      console.error("AI provider error", aiResponse.status);
      await admin.from("ai_usage").insert({ user_id: userId, feature: "food_scan", model: visionModel, succeeded: false });
      await admin.from("food_scans").update({ status: "failed", error_message: "The AI provider rejected the request." }).eq("id", scanId);
      return json({ error: "The food analysis service is unavailable right now. Please try again." }, 502);
    }

    const aiJson = await aiResponse.json();
    const usage = aiJson.usage ?? {};
    const parsed = parseDetections(aiJson.choices?.[0]?.message?.content);

    if (!parsed.ok) {
      await admin.from("ai_usage").insert({
        user_id: userId, feature: "food_scan", model: visionModel,
        input_tokens: usage.prompt_tokens ?? 0, output_tokens: usage.completion_tokens ?? 0,
        estimated_cost_usd: 0.001, succeeded: false,
      });
      await admin.from("food_scans").update({ status: "failed", error_message: "The analysis response could not be interpreted." }).eq("id", scanId);
      return json({ error: "We could not interpret that photo. Try a clearer picture." }, 422);
    }

    const { data: foods } = await admin
      .from("foods")
      .select("id, name, calories, protein_g, carbs_g, fat_g, fiber_g, sugar_g, sodium_mg");

    const foodList = (foods ?? []) as FoodRow[];

    await admin.from("food_scan_items").delete().eq("scan_id", scanId);

    const rows = parsed.items.map((item) => {
      const match = matchFood(item.name, foodList);
      const factor = item.estimated_grams / 100;
      const num = (key: string) => (match ? round(Number(match[key] ?? 0) * factor, 1) : 0);
      return {
        scan_id: scanId,
        food_id: match?.id ?? null,
        food_name: match?.name ?? item.name,
        estimated_grams: item.estimated_grams,
        confidence: item.confidence,
        calories: num("calories"),
        protein_g: num("protein_g"),
        carbs_g: num("carbs_g"),
        fat_g: num("fat_g"),
        fiber_g: num("fiber_g"),
        sugar_g: num("sugar_g"),
        sodium_mg: num("sodium_mg"),
      };
    });

    const { data: inserted, error: insertError } = await admin.from("food_scan_items").insert(rows).select("*");

    if (insertError) {
      await admin.from("food_scans").update({ status: "failed", error_message: "The detected items could not be stored." }).eq("id", scanId);
      return json({ error: "The detected items could not be saved. Please try again." }, 500);
    }

    await admin.from("food_scans").update({ status: "completed", ai_model: visionModel, error_message: null }).eq("id", scanId);
    await admin.from("ai_usage").insert({
      user_id: userId, feature: "food_scan", model: visionModel,
      input_tokens: usage.prompt_tokens ?? 0, output_tokens: usage.completion_tokens ?? 0,
      estimated_cost_usd: 0.002, succeeded: true,
    });

    return json({ items: inserted ?? [], ai_model: visionModel });
  } catch (err) {
    console.error("analyze-food-photo failed", err);
    return json({ error: "Something went wrong while analysing that photo. Please try again." }, 500);
  }
});
