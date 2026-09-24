/**
 * weekly-coach
 *
 * Builds a weekly review from the signed-in user's own data. The numbers are
 * computed here from the database and sent to the model as context; the model
 * only writes the narrative, so it cannot invent statistics. The AI key stays
 * server-side and thin data returns an explicit error rather than a fabricated
 * report.
 */

import { createClient } from "npm:@supabase/supabase-js@2.45.4";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Client-Info, Apikey",
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

const round = (n: number, d = 1) => Math.round(n * 10 ** d) / 10 ** d;

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 200, headers: corsHeaders });

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const anonKey = Deno.env.get("SUPABASE_ANON_KEY")!;
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const aiKey = Deno.env.get("OPENAI_API_KEY");
    const model = Deno.env.get("AI_TEXT_MODEL") ?? "gpt-4o-mini";

    const authHeader = req.headers.get("Authorization") ?? "";
    if (!authHeader) return json({ error: "Missing authorization." }, 401);

    const userClient = createClient(supabaseUrl, anonKey, { global: { headers: { Authorization: authHeader } } });
    const { data: userData, error: userError } = await userClient.auth.getUser();
    if (userError || !userData.user) return json({ error: "Your session has expired. Please sign in again." }, 401);
    const userId = userData.user.id;

    const admin = createClient(supabaseUrl, serviceKey);

    const since = new Date();
    since.setDate(since.getDate() - 7);
    const sinceDate = since.toISOString().split("T")[0];

    const [profileRes, metricsRes, workoutsRes, mealsRes, sessionsRes, goalsRes] = await Promise.all([
      admin.from("fitness_profile").select("*").eq("user_id", userId).maybeSingle(),
      admin.from("daily_metrics").select("*").eq("user_id", userId).gte("metric_date", sinceDate),
      admin.from("workouts").select("*").eq("user_id", userId).gte("workout_date", sinceDate),
      admin.from("meals").select("*").eq("user_id", userId).gte("meal_date", sinceDate),
      admin.from("workout_sessions").select("id, duration_minutes, perceived_effort, completed, started_at").eq("user_id", userId).gte("started_at", since.toISOString()),
      admin.from("goals").select("*").eq("user_id", userId).eq("status", "active"),
    ]);

    const profile = profileRes.data;
    const metrics = metricsRes.data ?? [];
    const workouts = workoutsRes.data ?? [];
    const meals = mealsRes.data ?? [];
    const sessions = sessionsRes.data ?? [];
    const goals = goalsRes.data ?? [];

    if (workouts.length === 0 && metrics.length === 0 && meals.length === 0 && sessions.length === 0) {
      return json(
        {
          error: "There is not enough data yet to build a weekly review. Log a few workouts, meals and daily metrics first.",
          code: "INSUFFICIENT_DATA",
        },
        422
      );
    }

    const completed = workouts.filter((w) => w.completed);
    const loggedMealDays = new Set(meals.map((m) => m.meal_date)).size;

    const context = {
      period_days: 7,
      workouts_completed: completed.length,
      detailed_sessions: sessions.filter((s) => s.completed).length,
      weekly_workout_target: profile?.activity_target ?? null,
      training_minutes:
        completed.reduce((s, w) => s + (w.duration_minutes ?? 0), 0) +
        sessions.reduce((s, w) => s + (w.duration_minutes ?? 0), 0),
      average_sleep_hours: metrics.length ? round(metrics.reduce((s, m) => s + Number(m.sleep_hours ?? 0), 0) / metrics.length, 1) : null,
      sleep_target_hours: profile ? Number(profile.sleep_target_hours) : null,
      average_steps: metrics.length ? Math.round(metrics.reduce((s, m) => s + (m.steps ?? 0), 0) / metrics.length) : null,
      step_target: profile?.step_target ?? null,
      average_readiness: metrics.length ? Math.round(metrics.reduce((s, m) => s + (m.readiness ?? 0), 0) / metrics.length) : null,
      average_hrv: metrics.length ? Math.round(metrics.reduce((s, m) => s + (m.hrv ?? 0), 0) / metrics.length) : null,
      days_with_nutrition_logged: loggedMealDays,
      average_daily_calories: loggedMealDays ? Math.round(meals.reduce((s, m) => s + Number(m.calories ?? 0), 0) / loggedMealDays) : null,
      calorie_target: profile?.calorie_target ?? null,
      average_daily_protein_g: loggedMealDays ? Math.round(meals.reduce((s, m) => s + Number(m.protein_g ?? 0), 0) / loggedMealDays) : null,
      protein_target_g: profile?.protein_target_g ?? null,
      scanned_meals: meals.filter((m) => m.source === "scanner").length,
      active_goals: goals.map((g) => ({ title: g.title, current: g.current_value, target: g.target_value, unit: g.unit })),
      primary_goal: profile?.goal ?? null,
    };

    if (!aiKey) {
      return json(
        { error: "The AI coach is not configured yet. Add an OPENAI_API_KEY secret to enable weekly reviews.", code: "AI_NOT_CONFIGURED" },
        503
      );
    }

    const instruction = [
      "You are a supportive fitness coach writing a weekly review.",
      "You will receive a JSON summary of the athlete's real data. Use only those numbers.",
      "Never invent statistics and never state a number that is not in the summary.",
      "If a value is null, say the data was not available rather than guessing.",
      "Do not give medical advice, diagnose conditions, or present uncertain health conclusions as facts.",
      "Keep the tone practical and encouraging, and avoid guarantees about future results.",
      'Respond with JSON only in exactly this shape: {"summary":"...","went_well":["..."],"improve":["..."],"training":"...","nutrition":"...","recovery":"...","focus_next_week":"..."}',
    ].join(" ");

    const aiResponse = await fetch("https://api.openai.com/v1/chat/completions", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${aiKey}` },
      body: JSON.stringify({
        model,
        response_format: { type: "json_object" },
        max_tokens: 900,
        messages: [
          { role: "system", content: instruction },
          { role: "user", content: JSON.stringify(context) },
        ],
      }),
    });

    if (!aiResponse.ok) {
      console.error("AI provider error", aiResponse.status);
      await admin.from("ai_usage").insert({ user_id: userId, feature: "weekly_coach", model, succeeded: false });
      return json({ error: "The coaching service is unavailable right now. Please try again later." }, 502);
    }

    const aiJson = await aiResponse.json();
    let review: Record<string, unknown> = {};
    try {
      review = JSON.parse(aiJson.choices?.[0]?.message?.content ?? "{}");
    } catch {
      return json({ error: "The coaching response could not be read. Please try again." }, 422);
    }

    const asText = (v: unknown, fallback: string) => (typeof v === "string" && v.trim() ? v.trim() : fallback);
    const asList = (v: unknown) => (Array.isArray(v) ? v.filter((x) => typeof x === "string" && x.trim()).slice(0, 6) : []);

    const normalised = {
      summary: asText(review.summary, "Not enough information was available for a summary this week."),
      went_well: asList(review.went_well),
      improve: asList(review.improve),
      training: asText(review.training, "No training observation was produced."),
      nutrition: asText(review.nutrition, "No nutrition observation was produced."),
      recovery: asText(review.recovery, "No recovery observation was produced."),
      focus_next_week: asText(review.focus_next_week, "Keep logging consistently to build a clearer picture."),
      stats: context,
    };

    const { data: saved } = await admin
      .from("coach_notifications")
      .insert({ user_id: userId, title: "Your weekly review", message: normalised.summary, kind: "info", is_read: false })
      .select("*")
      .maybeSingle();

    await admin.from("ai_usage").insert({
      user_id: userId, feature: "weekly_coach", model,
      input_tokens: aiJson.usage?.prompt_tokens ?? 0, output_tokens: aiJson.usage?.completion_tokens ?? 0,
      estimated_cost_usd: 0.001, succeeded: true,
    });

    return json({ review: normalised, notification: saved ?? null, ai_model: model });
  } catch (err) {
    console.error("weekly-coach failed", err);
    return json({ error: "Something went wrong while building your review. Please try again." }, 500);
  }
});
