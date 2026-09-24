import { useMemo, useState } from "react";
import {
  Plus, Trash2, Flame, Beef, Droplets, Utensils, Coffee, Sun, Moon,
  Camera, Search, X, Check, AlertTriangle,
} from "lucide-react";
import { apiData, MEAL_TYPES, type Meal, type Profile, type DailyMetric } from "../lib/api/dataAdapter";
import type { Food, MealItem } from "../lib/types";
import { macroTotals } from "../lib/insights";
import { calculateNutrition, sumNutrition } from "../lib/nutrition";
import { Modal, EmptyState, SectionHeader, ProgressRing } from "../components/ui";
import { todayISO, formatDate, round } from "../lib/utils";
import FoodScannerModal from "./FoodScannerModal";

type Props = {
  meals: Meal[];
  mealItems: MealItem[];
  foods: Food[];
  profile: Profile | null;
  todayMetric: DailyMetric | null;
  onRefresh: () => void;
};

const mealIcon = {
  Breakfast: <Coffee size={18} />,
  Lunch: <Sun size={18} />,
  Dinner: <Moon size={18} />,
  Snack: <Utensils size={18} />,
};

type ComposeRow = { key: string; food: Food; grams: number };

let rowCounter = 0;
const nextRowKey = () => `r${++rowCounter}`;

export default function NutritionView({ meals, mealItems, foods, profile, todayMetric, onRefresh }: Props) {
  const [manualOpen, setManualOpen] = useState(false);
  const [scannerOpen, setScannerOpen] = useState(false);
  const [foodPickerOpen, setFoodPickerOpen] = useState(false);
  const [pickerTarget, setPickerTarget] = useState<number | null>(null);
  const [foodSearch, setFoodSearch] = useState("");
  const [expanded, setExpanded] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [drafts, setDrafts] = useState<ComposeRow[]>([]);
  const [mealForm, setMealForm] = useState({ meal_type: "Breakfast" as string, name: "" });

  const today = todayISO();
  const todayMeals = useMemo(() => meals.filter((m) => m.meal_date === today), [meals, today]);
  const totals = useMemo(() => macroTotals(todayMeals), [todayMeals]);
  const weekMeals = useMemo(() => meals.filter((m) => m.meal_date >= offsetDays(6)), [meals]);
  const weekTotals = useMemo(() => macroTotals(weekMeals), [weekMeals]);

  const calorieTarget = profile?.calorie_target ?? 2400;
  const proteinTarget = profile?.protein_target_g ?? 150;
  const waterTarget = profile?.water_target_oz ?? 100;
  const fiberTotal = useMemo(
    () => round(todayMeals.reduce((s, m) => s + Number(m.fiber_g ?? 0), 0), 1),
    [todayMeals]
  );

  const draftTotals = useMemo(() => {
    if (drafts.length === 0) return null;
    return sumNutrition(drafts.map((d) => calculateNutrition(d.food, d.grams)));
  }, [drafts]);

  const filteredFoods = useMemo(() => {
    const needle = foodSearch.trim().toLowerCase();
    if (!needle) return foods.slice(0, 40);
    return foods.filter((f) => f.name.toLowerCase().includes(needle) || f.category.toLowerCase().includes(needle)).slice(0, 40);
  }, [foods, foodSearch]);

  function itemsForMeal(mealId: string) {
    return mealItems.filter((i) => i.meal_id === mealId);
  }

  function openManual() {
    setDrafts([]);
    setMealForm({ meal_type: "Breakfast", name: "" });
    setError(null);
    setManualOpen(true);
  }

  function addDraft(food: Food) {
    if (pickerTarget !== null) {
      setDrafts((prev) => prev.map((d, i) => (i === pickerTarget ? { ...d, food } : d)));
    } else {
      setDrafts((prev) => [...prev, { key: nextRowKey(), food, grams: 100 }]);
    }
    setPickerTarget(null);
    setFoodPickerOpen(false);
    setFoodSearch("");
  }

  function setGrams(key: string, grams: number) {
    setDrafts((prev) =>
      prev.map((d) => (d.key === key ? { ...d, grams: Math.max(1, Math.min(5000, Number.isFinite(grams) ? grams : d.grams)) } : d))
    );
  }

  async function saveManualMeal() {
    if (drafts.length === 0) {
      setError("Add at least one food item.");
      return;
    }
    const name = mealForm.name.trim() || drafts.map((d) => d.food.name).slice(0, 3).join(", ");
    const values = sumNutrition(drafts.map((d) => calculateNutrition(d.food, d.grams)));

    setSaving(true);
    setError(null);

    const { data: meal, error: insertError } = await apiData
      .from("meals")
      .insert({
        meal_date: today,
        meal_type: mealForm.meal_type,
        name,
        calories: Math.round(values.calories),
        protein_g: Math.round(values.protein_g),
        carbs_g: Math.round(values.carbs_g),
        fat_g: Math.round(values.fat_g),
        fiber_g: Math.round(values.fiber_g),
        source: "manual",
      })
      .select("id")
      .maybeSingle();

    if (insertError || !meal) {
      setSaving(false);
      setError("That meal could not be saved. Please try again.");
      return;
    }

    const rows = drafts.map((d) => {
      const v = calculateNutrition(d.food, d.grams);
      return {
        meal_id: meal.id,
        food_id: d.food.id,
        food_name: d.food.name,
        quantity: 1,
        grams: d.grams,
        calories: v.calories,
        protein_g: v.protein_g,
        carbs_g: v.carbs_g,
        fat_g: v.fat_g,
        fiber_g: v.fiber_g,
        source: "manual",
      };
    });
    await apiData.from("meal_items").insert(rows);

    setSaving(false);
    setManualOpen(false);
    onRefresh();
  }

  async function deleteMeal(id: string) {
    await apiData.from("meals").delete().eq("id", id);
    onRefresh();
  }

  async function repeatMeal(m: Meal) {
    const items = itemsForMeal(m.id);
    const { data: created } = await apiData
      .from("meals")
      .insert({
        meal_date: today,
        meal_type: m.meal_type,
        name: m.name,
        calories: m.calories,
        protein_g: m.protein_g,
        carbs_g: m.carbs_g,
        fat_g: m.fat_g,
        fiber_g: m.fiber_g,
        source: m.source,
      })
      .select("id")
      .maybeSingle();
    if (created && items.length) {
      await apiData.from("meal_items").insert(
        items.map((i) => ({
          meal_id: created.id,
          food_id: i.food_id,
          food_name: i.food_name,
          quantity: i.quantity,
          grams: i.grams,
          calories: i.calories,
          protein_g: i.protein_g,
          carbs_g: i.carbs_g,
          fat_g: i.fat_g,
          fiber_g: i.fiber_g,
          source: i.source,
        }))
      );
    }
    onRefresh();
  }

  const recentMeals = useMemo(() => {
    const seen = new Set<string>();
    return meals
      .filter((m) => {
        if (m.meal_date === today || seen.has(m.name)) return false;
        seen.add(m.name);
        return true;
      })
      .slice(0, 6);
  }, [meals, today]);

  return (
    <div className="flex-col" style={{ animation: "fadeInUp 0.4s ease both" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: 12 }}>
        <SectionHeader title="Nutrition" subtitle="Calories, macros and hydration for today" />
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
          <button className="btn" onClick={() => setScannerOpen(true)}>
            <Camera size={16} /> Scan food with AI
          </button>
          <button className="btn btn-secondary" onClick={openManual}>
            <Plus size={16} /> Manual entry
          </button>
        </div>
      </div>

      <div className="card">
        <div className="ring-wrap" style={{ justifyContent: "space-around", flexWrap: "wrap", gap: 28 }}>
          <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 8 }}>
            <ProgressRing value={totals.calories} max={calorieTarget} size={130} stroke={12} color="#fb923c" sublabel="CALORIES" />
            <span className="stat-meta">{Math.max(0, calorieTarget - totals.calories)} remaining</span>
          </div>
          <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 8 }}>
            <ProgressRing value={totals.protein} max={proteinTarget} size={130} stroke={12} color="#38bdf8" sublabel="PROTEIN" />
            <span className="stat-meta">{totals.protein}g of {proteinTarget}g</span>
          </div>
          <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 8 }}>
            <ProgressRing value={todayMetric?.water_oz ?? 0} max={waterTarget} size={130} stroke={12} color="#22d3ee" sublabel="WATER" />
            <span className="stat-meta">{todayMetric?.water_oz ?? 0} of {waterTarget} oz</span>
          </div>
        </div>
      </div>

      <div className="grid-4">
        <MacroCard icon={<Flame size={18} />} label="Calories" value={totals.calories} unit="kcal" color="#fb923c" target={calorieTarget} />
        <MacroCard icon={<Beef size={18} />} label="Protein" value={totals.protein} unit="g" color="#38bdf8" target={proteinTarget} />
        <MacroCard icon={<Utensils size={18} />} label="Carbs" value={totals.carbs} unit="g" color="#4ade80" target={Math.round(calorieTarget * 0.45 / 4)} />
        <MacroCard icon={<Droplets size={18} />} label="Fat" value={totals.fat} unit="g" color="#a78bfa" target={Math.round(calorieTarget * 0.28 / 9)} />
      </div>

      <div>
        <h3 style={{ fontSize: 16, fontWeight: 700, color: "#cbd5e1", margin: "0 0 12px" }}>Today's meals</h3>
        {todayMeals.length === 0 ? (
          <div className="card">
            <EmptyState
              icon={<Utensils size={28} color="#64748b" />}
              title="No meals logged today"
              message="Log a meal manually or scan a photo to see how your nutrition supports training."
              action={
                <div style={{ display: "flex", gap: 8, justifyContent: "center", flexWrap: "wrap" }}>
                  <button className="btn btn-sm" onClick={() => setScannerOpen(true)}>
                    <Camera size={14} /> Scan food
                  </button>
                  <button className="btn btn-secondary btn-sm" onClick={openManual}>
                    <Plus size={14} /> Manual entry
                  </button>
                </div>
              }
            />
          </div>
        ) : (
          <div className="flex-col" style={{ gap: 10 }}>
            {todayMeals.map((m) => {
              const items = itemsForMeal(m.id);
              const isOpen = expanded === m.id;
              return (
                <div className="card" key={m.id}>
                  <div style={{ display: "flex", alignItems: "center", gap: 14 }}>
                    <div className="workout-icon" style={{ background: "rgba(251,146,60,0.14)", color: "#fb923c" }}>
                      {mealIcon[m.meal_type as keyof typeof mealIcon] ?? <Utensils size={18} />}
                    </div>
                    <div className="workout-info">
                      <div className="workout-title">{m.name}</div>
                      <div className="workout-meta">
                        <span>{m.meal_type}</span>
                        <span>{m.calories} kcal</span>
                        <span>{m.protein_g}g protein</span>
                        <span>{m.carbs_g}g carbs</span>
                        <span>{m.fat_g}g fat</span>
                        {m.source === "scanner" && (
                          <span className="badge" style={{ background: "rgba(56,189,248,0.14)", color: "#38bdf8" }}>
                            <Camera size={11} /> AI scanned
                          </span>
                        )}
                      </div>
                    </div>
                    <div style={{ display: "flex", gap: 8 }}>
                      {items.length > 0 && (
                        <button className="btn btn-secondary btn-sm" onClick={() => setExpanded(isOpen ? null : m.id)}>
                          {isOpen ? "Hide" : `${items.length} item${items.length === 1 ? "" : "s"}`}
                        </button>
                      )}
                      <button className="btn btn-secondary btn-sm" onClick={() => deleteMeal(m.id)} aria-label="Delete meal">
                        <Trash2 size={14} color="#f87171" />
                      </button>
                    </div>
                  </div>

                  {isOpen && items.length > 0 && (
                    <div style={{ marginTop: 14, display: "flex", flexDirection: "column", gap: 8 }}>
                      {items.map((i) => (
                        <div key={i.id} className="template-row">
                          <span style={{ color: "#cbd5e1", fontSize: 13 }}>
                            {i.food_name} <span className="stat-meta">· {Math.round(i.grams)}g</span>
                          </span>
                          <span className="stat-meta">
                            {Math.round(i.calories)} kcal · {round(i.protein_g, 1)}g protein
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {recentMeals.length > 0 && (
        <div>
          <h3 style={{ fontSize: 16, fontWeight: 700, color: "#cbd5e1", margin: "0 0 4px" }}>Quick add</h3>
          <p style={{ fontSize: 13, color: "#94a3b8", margin: "0 0 12px" }}>Log a meal you have eaten before in one tap.</p>
          <div className="grid-3">
            {recentMeals.map((m) => (
              <button key={m.id} className="card" onClick={() => repeatMeal(m)} style={{ textAlign: "left", cursor: "pointer" }}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 6 }}>
                  <span style={{ fontSize: 14, fontWeight: 700, color: "#f0f6fc" }}>{m.name}</span>
                  <Plus size={15} color="#38bdf8" />
                </div>
                <span className="stat-meta">{m.calories} kcal · {m.protein_g}g protein · {formatDate(m.meal_date)}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      <div className="card">
        <h3 style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc", margin: "0 0 16px" }}>This week's nutrition</h3>
        <div className="grid-4">
          <div><div style={{ fontSize: 22, fontWeight: 800, color: "#f0f6fc" }}>{Math.round(weekTotals.calories / 7).toLocaleString()}</div><span className="stat-meta">avg calories per day</span></div>
          <div><div style={{ fontSize: 22, fontWeight: 800, color: "#f0f6fc" }}>{Math.round(weekTotals.protein / 7)}g</div><span className="stat-meta">avg protein per day</span></div>
          <div><div style={{ fontSize: 22, fontWeight: 800, color: "#f0f6fc" }}>{Math.round(weekTotals.carbs / 7)}g</div><span className="stat-meta">avg carbs per day</span></div>
          <div><div style={{ fontSize: 22, fontWeight: 800, color: "#f0f6fc" }}>{fiberTotal}g</div><span className="stat-meta">fiber today</span></div>
        </div>
      </div>

      {manualOpen && (
        <Modal title="Add a meal" onClose={() => setManualOpen(false)}>
          <div className="flex-col" style={{ gap: 16 }}>
            {error && <div className="form-error" role="alert"><span>{error}</span></div>}

            <div className="form-row">
              <div className="form-group">
                <label className="form-label">Meal</label>
                <select className="form-select" value={mealForm.meal_type} onChange={(e) => setMealForm({ ...mealForm, meal_type: e.target.value })}>
                  {MEAL_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
              </div>
              <div className="form-group">
                <label className="form-label">Meal name (optional)</label>
                <input className="form-input" placeholder="e.g. Chicken bowl" value={mealForm.name} onChange={(e) => setMealForm({ ...mealForm, name: e.target.value })} />
              </div>
            </div>

            <div>
              <div className="stat-label" style={{ marginBottom: 8 }}>Foods in this meal</div>
              {drafts.length === 0 && (
                <p style={{ fontSize: 13, color: "#64748b", margin: "0 0 10px" }}>
                  Search the nutrition database to add foods.
                </p>
              )}
              <div className="flex-col" style={{ gap: 8 }}>
                {drafts.map((d, idx) => {
                  const v = calculateNutrition(d.food, d.grams);
                  return (
                    <div key={d.key} className="scan-item">
                      <div className="scan-item-head">
                        <div>
                          <div className="scan-item-name">{d.food.name}</div>
                          <div className="scan-item-nutrition" style={{ marginTop: 4 }}>
                            <span>≈{Math.round(v.calories)} kcal</span>
                            <span>{round(v.protein_g, 1)}g protein</span>
                          </div>
                        </div>
                        <button className="icon-btn" onClick={() => setDrafts((prev) => prev.filter((_, i) => i !== idx))} aria-label="Remove item">
                          <X size={15} color="#f87171" />
                        </button>
                      </div>
                      <div className="scan-item-controls">
                        <div className="portion-control">
                          <button className="portion-btn" onClick={() => setGrams(d.key, d.grams - 10)} aria-label="Decrease">−</button>
                          <input className="form-input portion-input" type="number" min={1} value={Math.round(d.grams)} onChange={(e) => setGrams(d.key, Number(e.target.value))} aria-label="Grams" />
                          <span className="portion-unit">g</span>
                          <button className="portion-btn" onClick={() => setGrams(d.key, d.grams + 10)} aria-label="Increase">+</button>
                        </div>
                        <button className="btn btn-secondary btn-sm" onClick={() => { setPickerTarget(idx); setFoodPickerOpen(true); }}>
                          <Search size={13} /> Change
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
              <button className="btn btn-secondary btn-sm" style={{ marginTop: 10 }} onClick={() => { setPickerTarget(null); setFoodPickerOpen(true); }}>
                <Plus size={14} /> Add food
              </button>
            </div>

            {draftTotals && (
              <div className="scan-totals">
                <div><span className="stat-meta">Calories</span><strong>{Math.round(draftTotals.calories)}</strong></div>
                <div><span className="stat-meta">Protein</span><strong>{round(draftTotals.protein_g, 1)}g</strong></div>
                <div><span className="stat-meta">Carbs</span><strong>{round(draftTotals.carbs_g, 1)}g</strong></div>
                <div><span className="stat-meta">Fat</span><strong>{round(draftTotals.fat_g, 1)}g</strong></div>
              </div>
            )}

            <div style={{ display: "flex", gap: 10, justifyContent: "flex-end" }}>
              <button className="btn btn-secondary" onClick={() => setManualOpen(false)}>Cancel</button>
              <button className="btn" onClick={saveManualMeal} disabled={saving}>
                <Check size={16} /> {saving ? "Saving…" : "Save meal"}
              </button>
            </div>

            {foodPickerOpen && (
              <div className="picker-overlay">
                <div className="picker">
                  <div className="picker-head">
                    <span style={{ fontWeight: 700, color: "#f0f6fc" }}>{pickerTarget !== null ? "Change food" : "Add food"}</span>
                    <button className="icon-btn" onClick={() => { setFoodPickerOpen(false); setPickerTarget(null); }} aria-label="Close">
                      <X size={16} color="#94a3b8" />
                    </button>
                  </div>
                  <div style={{ position: "relative", marginBottom: 12 }}>
                    <Search size={16} color="#64748b" style={{ position: "absolute", left: 12, top: 12 }} />
                    <input className="form-input" style={{ paddingLeft: 36 }} placeholder="Search the nutrition database" value={foodSearch} onChange={(e) => setFoodSearch(e.target.value)} autoFocus />
                  </div>
                  <div className="picker-list">
                    {filteredFoods.map((f) => (
                      <button key={f.id} className="picker-item" onClick={() => addDraft(f)}>
                        <span style={{ fontWeight: 600, color: "#f0f6fc" }}>{f.name}</span>
                        <span className="stat-meta">{f.calories} kcal · {f.protein_g}g protein per 100g</span>
                      </button>
                    ))}
                    {filteredFoods.length === 0 && (
                      <p style={{ color: "#64748b", fontSize: 14, textAlign: "center", padding: 20 }}>No matching food found.</p>
                    )}
                  </div>
                </div>
              </div>
            )}
          </div>
        </Modal>
      )}

      {scannerOpen && (
        <FoodScannerModal foods={foods} onClose={() => setScannerOpen(false)} onSaved={onRefresh} />
      )}
    </div>
  );
}

function MacroCard({
  icon, label, value, unit, color, target,
}: {
  icon: React.ReactNode; label: string; value: number; unit: string; color: string; target: number;
}) {
  const pct = target > 0 ? Math.round((value / target) * 100) : 0;
  return (
    <div className="card">
      <div className="stat-tile-top" style={{ marginBottom: 10 }}>
        <span className="stat-label">{label}</span>
        <div className="stat-icon" style={{ background: `${color}22`, color }}>{icon}</div>
      </div>
      <div style={{ fontSize: 24, fontWeight: 800, color: "#f0f6fc", marginBottom: 8 }}>
        {Math.round(value).toLocaleString()} <span className="stat-unit">{unit}</span>
      </div>
      <div className="stat-bar" style={{ marginBottom: 6 }}>
        <div className="stat-bar-fill" style={{ width: `${Math.min(100, pct)}%`, background: color }} />
      </div>
      <span className="stat-meta">{pct}% of target</span>
    </div>
  );
}

function offsetDays(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().split("T")[0];
}



