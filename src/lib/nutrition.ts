import type { Food } from "./types";
import { round } from "./utils";

export type NutritionValues = {
  calories: number;
  protein_g: number;
  carbs_g: number;
  fat_g: number;
  fiber_g: number;
  sugar_g: number;
  sodium_mg: number;
};

const EMPTY: NutritionValues = {
  calories: 0, protein_g: 0, carbs_g: 0, fat_g: 0, fiber_g: 0, sugar_g: 0, sodium_mg: 0,
};

/**
 * Nutrition for a portion of a food, derived from its per-100g values.
 * Deterministic by design: the food row is the source of truth and portion
 * size only scales it, so the same input always produces the same output.
 */
export function calculateNutrition(food: Food, grams: number): NutritionValues {
  if (!Number.isFinite(grams) || grams <= 0) return { ...EMPTY };
  const f = grams / 100;
  return {
    calories: round(food.calories * f, 1),
    protein_g: round(food.protein_g * f, 1),
    carbs_g: round(food.carbs_g * f, 1),
    fat_g: round(food.fat_g * f, 1),
    fiber_g: round(food.fiber_g * f, 1),
    sugar_g: round(food.sugar_g * f, 1),
    sodium_mg: round(food.sodium_mg * f, 1),
  };
}

export function sumNutrition(items: NutritionValues[]): NutritionValues {
  return items.reduce(
    (acc, n) => ({
      calories: round(acc.calories + n.calories, 1),
      protein_g: round(acc.protein_g + n.protein_g, 1),
      carbs_g: round(acc.carbs_g + n.carbs_g, 1),
      fat_g: round(acc.fat_g + n.fat_g, 1),
      fiber_g: round(acc.fiber_g + n.fiber_g, 1),
      sugar_g: round(acc.sugar_g + n.sugar_g, 1),
      sodium_mg: round(acc.sodium_mg + n.sodium_mg, 1),
    }),
    { ...EMPTY }
  );
}

/** Case-insensitive match of an identified food name against the database. */
export function matchFood(name: string, foods: Food[]): Food | null {
  const needle = name.trim().toLowerCase();
  if (!needle) return null;

  const exact = foods.find((f) => f.name.toLowerCase() === needle);
  if (exact) return exact;

  const partial = foods.find(
    (f) => f.name.toLowerCase().includes(needle) || needle.includes(f.name.toLowerCase())
  );
  if (partial) return partial;

  const words = needle.split(/\s+/).filter((w) => w.length > 3);
  let best: { food: Food; score: number } | null = null;
  for (const f of foods) {
    const fname = f.name.toLowerCase();
    const score = words.reduce((s, w) => (fname.includes(w) ? s + 1 : s), 0);
    if (score > 0 && (!best || score > best.score)) best = { food: f, score };
  }
  return best?.food ?? null;
}

export function isValidImageFile(file: File): { ok: boolean; reason?: string } {
  const allowed = ["image/jpeg", "image/png", "image/webp"];
  if (!allowed.includes(file.type)) return { ok: false, reason: "Please choose a JPEG, PNG or WebP image." };
  if (file.size > 8 * 1024 * 1024) return { ok: false, reason: "That image is larger than 8 MB. Please choose a smaller photo." };
  return { ok: true };
}

/** Downscales an image in the browser so uploads and AI calls stay cheap. */
export async function compressImage(file: File, maxDimension = 1024, quality = 0.82): Promise<Blob> {
  try {
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, maxDimension / Math.max(bitmap.width, bitmap.height));
    if (scale === 1 && file.size < 1_500_000) return file;

    const canvas = document.createElement("canvas");
    canvas.width = Math.round(bitmap.width * scale);
    canvas.height = Math.round(bitmap.height * scale);
    const ctx = canvas.getContext("2d");
    if (!ctx) return file;
    ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close?.();

    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob((b) => resolve(b), "image/jpeg", quality)
    );
    return blob ?? file;
  } catch {
    return file;
  }
}


