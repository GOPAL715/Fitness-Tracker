import { useMemo, useRef, useState } from "react";
import { Camera, Upload, Sparkles, Check, Trash2, Search, Plus, AlertTriangle, RefreshCw, X } from "lucide-react";
import { MEAL_TYPES } from "../lib/domain";
import { apiData } from "../lib/api/dataAdapter";
import { analyzeFoodPhoto } from "../lib/api/foodScannerApi";
import type { Food } from "../lib/types";
import { Modal } from "../components/ui";
import { calculateNutrition, isValidImageFile, compressImage, sumNutrition } from "../lib/nutrition";
import { parseDetections, confidenceLabel, needsReview } from "../lib/foodScan";
import { useAuth } from "../lib/auth";
import { todayISO, round } from "../lib/utils";

type DraftItem = {
  key: string;
  food_id: string | null;
  food_name: string;
  grams: number;
  confidence: number | null;
  user_edited: boolean;
};

type Props = { foods: Food[]; onClose: () => void; onSaved: () => void };
type Stage = "choose" | "preview" | "analyzing" | "review";

let counter = 0;
const nextKey = () => `i${++counter}`;

export default function FoodScannerModal({ foods, onClose, onSaved }: Props) {
  const { user } = useAuth();
  
  const fileInput = useRef<HTMLInputElement>(null);
  const cameraInput = useRef<HTMLInputElement>(null);

  const [stage, setStage] = useState<Stage>("choose");
  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [items, setItems] = useState<DraftItem[]>([]);
  const [mealType, setMealType] = useState<string>("Lunch");
  const [apiDataScanId, setScanId] = useState<string | null>(null);
  const [aiModel, setAiModel] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notConfigured, setNotConfigured] = useState(false);
  const [saving, setSaving] = useState(false);
  const [progressStep, setProgressStep] = useState(0);
  const [editingKey, setEditingKey] = useState<string | null>(null);
  const [foodSearch, setFoodSearch] = useState("");

  const totals = useMemo(() => {
    return sumNutrition(
      items.map((i) => {
        const food = foods.find((f) => f.id === i.food_id);
        if (!food) return { calories: 0, protein_g: 0, carbs_g: 0, fat_g: 0, fiber_g: 0, sugar_g: 0, sodium_mg: 0 };
        return calculateNutrition(food, i.grams);
      })
    );
  }, [items, foods]);

  const filteredFoods = useMemo(() => {
    const needle = foodSearch.trim().toLowerCase();
    if (!needle) return foods.slice(0, 30);
    return foods.filter((f) => f.name.toLowerCase().includes(needle) || f.category.toLowerCase().includes(needle)).slice(0, 30);
  }, [foods, foodSearch]);

  function handleFile(selected: File | undefined) {
    if (!selected) return;
    const check = isValidImageFile(selected);
    if (!check.ok) {
      setError(check.reason ?? "That image cannot be used.");
      return;
    }
    setError(null);
    setNotConfigured(false);
    setFile(selected);
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    setPreviewUrl(URL.createObjectURL(selected));
    setStage("preview");
  }

  async function analyze() {
    if (!file) return;
    setStage("analyzing"); setProgressStep(1); setError(null);
    try {
      const compressed = await compressImage(file);
      const payload = await analyzeFoodPhoto(compressed);
      const rows = Array.isArray(payload?.items) ? payload.items : [];
      const parsed = parseDetections({ items: rows });
      if (!parsed.ok) throw new Error(parsed.reason);
      setScanId(payload.apiDataScanId ?? payload.scan_id ?? null); setAiModel(payload.ai_model ?? null);
      setItems(rows.map((row: Record<string, unknown>) => ({ key: nextKey(), food_id: (row.food_id as string) ?? null, food_name: String(row.food_name ?? "Unknown food"), grams: Number(row.estimated_grams ?? 100), confidence: row.confidence == null ? null : Number(row.confidence), user_edited: false })));
      setStage("review");
    } catch (e) { setError(e instanceof Error ? e.message : "Something went wrong while analysing that photo. Please try again."); setStage("preview"); }
  }
  function updateGrams(key: string, grams: number) {
    setItems((prev) =>
      prev.map((i) => (i.key === key ? { ...i, grams: Math.max(1, Math.min(5000, Number.isFinite(grams) ? grams : i.grams)), user_edited: true } : i))
    );
  }

  function removeItem(key: string) {
    setItems((prev) => prev.filter((i) => i.key !== key));
  }

  function changeFood(key: string, food: Food) {
    setItems((prev) => prev.map((i) => (i.key === key ? { ...i, food_id: food.id, food_name: food.name, user_edited: true } : i)));
    setEditingKey(null);
    setFoodSearch("");
  }

  function addManualFood(food: Food) {
    setItems((prev) => [...prev, { key: nextKey(), food_id: food.id, food_name: food.name, grams: 100, confidence: null, user_edited: true }]);
    setEditingKey(null);
    setFoodSearch("");
  }

  async function confirmMeal() {
    if (!user || items.length === 0) return;
    if (totals.calories <= 0) {
      setError("None of these foods matched the nutrition database. Adjust the items before saving.");
      return;
    }
    setSaving(true);
    setError(null);

    const { data: meal, error: mealError } = await apiData
      .from("meals")
      .insert({
        user_id: user.id,
        meal_date: todayISO(),
        meal_type: mealType,
        name: items.map((i) => i.food_name).slice(0, 3).join(", ") + (items.length > 3 ? ` +${items.length - 3}` : ""),
        calories: Math.round(totals.calories),
        protein_g: Math.round(totals.protein_g),
        carbs_g: Math.round(totals.carbs_g),
        fat_g: Math.round(totals.fat_g),
        fiber_g: Math.round(totals.fiber_g),
        source: "scanner",
      })
      .select("id")
      .maybeSingle();

    if (mealError || !meal) {
      setSaving(false);
      setError("That meal could not be saved. Please try again.");
      return;
    }

    const rows = items.map((i) => {
      const food = foods.find((f) => f.id === i.food_id);
      const values = food ? calculateNutrition(food, i.grams) : { calories: 0, protein_g: 0, carbs_g: 0, fat_g: 0, fiber_g: 0, sugar_g: 0, sodium_mg: 0 };
      return {
        meal_id: meal.id,
        food_id: i.food_id,
        food_name: i.food_name,
        quantity: 1,
        grams: i.grams,
        calories: values.calories,
        protein_g: values.protein_g,
        carbs_g: values.carbs_g,
        fat_g: values.fat_g,
        fiber_g: values.fiber_g,
        source: "scanner",
      };
    });

    await apiData.from("meal_items").insert(rows);

    // Preserve the user's corrections and link the scan to the saved meal.
    if (apiDataScanId) {
      await apiData.from("food_scans").update({ status: "confirmed", meal_id: meal.id }).eq("id", apiDataScanId);
      for (const item of items) {
        if (!item.user_edited) continue;
        await apiData
          .from("food_scan_items")
          .update({ confirmed_grams: item.grams, user_edited: true })
          .eq("scan_id", apiDataScanId)
          .eq("food_name", item.food_name);
      }
    }

    setSaving(false);
    onSaved();
    onClose();
  }

  const analysisSteps = ["Detecting foods", "Estimating portions", "Looking up nutrition", "Calculating nutrition"];

  return (
    <Modal title="AI food scanner" onClose={onClose}>
      <div className="flex-col" style={{ gap: 16 }}>
        {error && (
          <div className="form-error" role="alert">
            <AlertTriangle size={16} />
            <span>{error}</span>
          </div>
        )}

        {notConfigured && (
          <div className="info-note">
            Photo scanning needs an AI provider key on the server. Everything else, including manual meal logging and
            the nutrition database, works without it.
          </div>
        )}

        {stage === "choose" && (
          <div className="scanner-choose">
            <p style={{ fontSize: 14, color: "#94a3b8", margin: 0, lineHeight: 1.6 }}>
              Photograph your meal and FitTrack will identify each food and estimate the portion. You can correct
              anything before saving.
            </p>
            <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
              <button className="btn" onClick={() => cameraInput.current?.click()}>
                <Camera size={16} /> Take a photo
              </button>
              <button className="btn btn-secondary" onClick={() => fileInput.current?.click()}>
                <Upload size={16} /> Upload a photo
              </button>
            </div>
            <input ref={cameraInput} type="file" accept="image/jpeg,image/png,image/webp" capture="environment" hidden onChange={(e) => handleFile(e.target.files?.[0])} />
            <input ref={fileInput} type="file" accept="image/jpeg,image/png,image/webp" hidden onChange={(e) => handleFile(e.target.files?.[0])} />
          </div>
        )}

        {stage === "preview" && previewUrl && (
          <div className="flex-col" style={{ gap: 14 }}>
            <img src={previewUrl} alt="Meal preview" className="scan-preview" />
            <div style={{ display: "flex", gap: 10, justifyContent: "flex-end", flexWrap: "wrap" }}>
              <button className="btn btn-secondary" onClick={() => { setFile(null); setPreviewUrl(null); setStage("choose"); }}>
                Retake
              </button>
              <button className="btn" onClick={analyze}>
                <Sparkles size={16} /> Analyze food
              </button>
            </div>
          </div>
        )}

        {stage === "analyzing" && (
          <div className="flex-col" style={{ gap: 14, alignItems: "center", padding: "20px 0" }}>
            {previewUrl && <img src={previewUrl} alt="Meal being analysed" className="scan-preview" style={{ maxHeight: 180 }} />}
            <span className="spinner" />
            <div className="scan-steps">
              {analysisSteps.map((s, i) => (
                <div key={s} className={`scan-step ${i <= progressStep ? "scan-step-done" : ""}`}>
                  {i < progressStep ? <Check size={14} color="#4ade80" /> : <span className="scan-dot" />}
                  <span>{s}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {stage === "review" && (
          <div className="flex-col" style={{ gap: 14 }}>
            {previewUrl && <img src={previewUrl} alt="Analysed meal" className="scan-preview" style={{ maxHeight: 160 }} />}

            <div className="form-group">
              <label className="form-label">Which meal is this?</label>
              <select className="form-select" value={mealType} onChange={(e) => setMealType(e.target.value)}>
                {MEAL_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>

            {needsReview(items) && (
              <div className="warn-note">Food identification may be uncertain. Please review the items and portions before saving.</div>
            )}

            <div className="flex-col" style={{ gap: 10 }}>
              {items.map((item) => {
                const food = foods.find((f) => f.id === item.food_id);
                const values = food ? calculateNutrition(food, item.grams) : null;
                const conf = confidenceLabel(item.confidence);
                return (
                  <div key={item.key} className="scan-item">
                    <div className="scan-item-head">
                      <div style={{ minWidth: 0 }}>
                        <div className="scan-item-name">{item.food_name}</div>
                        <div className="scan-item-meta">
                          {item.confidence !== null && (
                            <span className={`conf conf-${conf.tone}`}>{Math.round(item.confidence * 100)}% · {conf.text}</span>
                          )}
                          {item.user_edited && <span className="conf conf-edited">Edited by you</span>}
                        </div>
                      </div>
                      <button className="icon-btn" onClick={() => removeItem(item.key)} aria-label={`Remove ${item.food_name}`}>
                        <Trash2 size={15} color="#f87171" />
                      </button>
                    </div>

                    <div className="scan-item-controls">
                      <div className="portion-control">
                        <button className="portion-btn" onClick={() => updateGrams(item.key, item.grams - 10)} aria-label="Decrease portion">−</button>
                        <input className="form-input portion-input" type="number" min={1} max={5000} value={Math.round(item.grams)} onChange={(e) => updateGrams(item.key, Number(e.target.value))} aria-label="Portion in grams" />
                        <span className="portion-unit">g</span>
                        <button className="portion-btn" onClick={() => updateGrams(item.key, item.grams + 10)} aria-label="Increase portion">+</button>
                      </div>
                      <button className="btn btn-secondary btn-sm" onClick={() => setEditingKey(editingKey === item.key ? null : item.key)}>
                        <Search size={13} /> Change food
                      </button>
                    </div>

                    {values && (
                      <div className="scan-item-nutrition">
                        <span>≈{Math.round(values.calories)} kcal</span>
                        <span>{round(values.protein_g, 1)}g protein</span>
                        <span>{round(values.carbs_g, 1)}g carbs</span>
                        <span>{round(values.fat_g, 1)}g fat</span>
                      </div>
                    )}
                    {!food && (
                      <p className="scan-item-warn">
                        No nutrition match found. Use “Change food” to pick the closest item so totals are accurate.
                      </p>
                    )}
                  </div>
                );
              })}
            </div>

            <div className="scan-totals">
              <div><span className="stat-meta">Estimated calories</span><strong>{Math.round(totals.calories)} kcal</strong></div>
              <div><span className="stat-meta">Protein</span><strong>{round(totals.protein_g, 1)} g</strong></div>
              <div><span className="stat-meta">Carbs</span><strong>{round(totals.carbs_g, 1)} g</strong></div>
              <div><span className="stat-meta">Fat</span><strong>{round(totals.fat_g, 1)} g</strong></div>
            </div>

            <p className="estimate-note">Portions and nutrition from a photo are estimates. Adjust anything that looks wrong, then confirm.</p>

            <div style={{ display: "flex", gap: 10, justifyContent: "space-between", flexWrap: "wrap" }}>
              <button className="btn btn-secondary" onClick={() => setEditingKey("__add__")}>
                <Plus size={16} /> Add a food
              </button>
              <div style={{ display: "flex", gap: 10 }}>
                <button className="btn btn-secondary" onClick={() => setStage("preview")}>
                  <RefreshCw size={15} /> Re-analyze
                </button>
                <button className="btn" onClick={confirmMeal} disabled={saving || items.length === 0}>
                  <Check size={16} /> {saving ? "Saving…" : "Confirm meal"}
                </button>
              </div>
            </div>

            {aiModel && <p className="stat-meta" style={{ textAlign: "right" }}>Analysed with {aiModel}</p>}
          </div>
        )}

        {editingKey && (
          <div className="picker-overlay">
            <div className="picker">
              <div className="picker-head">
                <span style={{ fontWeight: 700, color: "#f0f6fc" }}>{editingKey === "__add__" ? "Add a food" : "Change food"}</span>
                <button className="icon-btn" onClick={() => { setEditingKey(null); setFoodSearch(""); }} aria-label="Close">
                  <X size={16} color="#94a3b8" />
                </button>
              </div>
              <div style={{ position: "relative", marginBottom: 12 }}>
                <Search size={16} color="#64748b" style={{ position: "absolute", left: 12, top: 12 }} />
                <input className="form-input" style={{ paddingLeft: 36 }} placeholder="Search the nutrition database" value={foodSearch} onChange={(e) => setFoodSearch(e.target.value)} autoFocus />
              </div>
              <div className="picker-list">
                {filteredFoods.map((f) => (
                  <button key={f.id} className="picker-item" onClick={() => (editingKey === "__add__" ? addManualFood(f) : changeFood(editingKey, f))}>
                    <span style={{ fontWeight: 600, color: "#f0f6fc" }}>{f.name}</span>
                    <span className="stat-meta">{f.calories} kcal · {f.protein_g}g protein per {f.serving_size}{f.serving_unit}</span>
                  </button>
                ))}
                {filteredFoods.length === 0 && (
                  <p style={{ color: "#64748b", fontSize: 14, textAlign: "center", padding: 20 }}>No matching food in the database.</p>
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </Modal>
  );
}





