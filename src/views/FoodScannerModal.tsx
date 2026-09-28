import { useMemo, useRef, useState } from "react";
import { Camera, Upload, Sparkles, Check, Trash2, Search, Plus, AlertTriangle, RefreshCw, X } from "lucide-react";
import { MEAL_TYPES } from "../lib/domain";
import { analyzeFoodPhoto, correctScanItems, confirmFoodScan } from "../lib/api/foodScannerApi";
import type { Food } from "../lib/types";
import { Modal } from "../components/ui";
import { calculateNutrition, isValidImageFile, compressImage, sumNutrition } from "../lib/nutrition";
import { confidenceLabel, needsReview } from "../lib/foodScan";
import { todayISO, round } from "../lib/utils";

type DraftItem = {
  key: string;
  itemId: string;
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
  
  const fileInput = useRef<HTMLInputElement>(null);
  const cameraInput = useRef<HTMLInputElement>(null);

  const [stage, setStage] = useState<Stage>("choose");
  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [items, setItems] = useState<DraftItem[]>([]);
  const [mealType, setMealType] = useState<string>("Lunch");
  const [scanId, setScanId] = useState<string | null>(null);
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
      if (rows.length === 0) throw new Error("No food was detected in that photo. Try a clearer shot.");
      setScanId(payload.id ?? null);
      setAiModel(payload.model ?? null);
      // The item id comes from the server and is what a correction is addressed by, so two items
      // that happen to share a name are still corrected independently.
      setItems(rows.map((row) => ({ key: nextKey(), itemId: row.id, food_id: row.foodId ?? null, food_name: row.name || "Unknown food", grams: Number(row.grams ?? 100), confidence: row.confidence == null ? null : Number(row.confidence), user_edited: false })));
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


  /**
   * Confirms the scan through the server, in the two steps the server owns.
   *
   * <p>Corrections go out addressed by item id, and confirmation is a single transactional call that
   * creates the meal and its items together. This used to insert a meal, then its items, then patch
   * the scan, all from the browser, so a failure part way through left a meal with no items and a
   * scan pointing at it. The server also derives the totals, so the numbers shown here and the
   * numbers stored cannot drift apart.
   */
  async function confirmMeal() {
    if (!scanId || items.length === 0) return;
    const unmatched = items.filter((i) => !i.food_id);
    if (unmatched.length > 0) {
      setError("Some items have no match in the nutrition database. Use the Change food button on each of them before saving.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      // Only items the user actually changed are sent; the rest are already correct on the server,
      // which also holds the nutrition for each of them.
      const corrections = items
        .filter((i) => i.user_edited)
        .map((i) => ({ itemId: i.itemId, foodId: i.food_id, name: i.food_name, grams: i.grams }));
      if (corrections.length > 0) await correctScanItems(scanId, corrections);
      await confirmFoodScan(scanId, { mealDate: todayISO(), mealType: mealType.toUpperCase() });
      onSaved();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : "That meal could not be saved. Please try again.");
    } finally {
      setSaving(false);
    }
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
            <input data-testid="scanner-camera" ref={cameraInput} type="file" accept="image/jpeg,image/png,image/webp" capture="environment" hidden onChange={(e) => handleFile(e.target.files?.[0])} />
            <input data-testid="scanner-file" ref={fileInput} type="file" accept="image/jpeg,image/png,image/webp" hidden onChange={(e) => handleFile(e.target.files?.[0])} />
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
                <span style={{ fontWeight: 700, color: "#f0f6fc" }}>Change food</span>
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
                  <button key={f.id} className="picker-item" onClick={() => changeFood(editingKey, f)}>
                    <span style={{ fontWeight: 600, color: "#f0f6fc" }}>{f.name}</span>
                    <span className="stat-meta">{f.calories} kcal · {f.protein_g}g protein per 100g</span>
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





