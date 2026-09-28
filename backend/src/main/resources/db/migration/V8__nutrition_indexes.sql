-- Phase 8: nutrition indexes and a sargable food-name lookup.
--
-- Nutrition basis (V8 decision)
-- ---------------------------
-- The foods table stores calories and every macro PER 100 GRAMS. That is now the single basis the
-- whole system uses: the server multiplies by grams/100 through NutritionCalculator, and the
-- browser does the same. serving_size remains display metadata and is deliberately not a
-- denominator. Before this, the server divided by serving_size while the browser divided by 100,
-- so any food not catalogued at exactly 100 g stored one number and displayed another.
--
-- Food catalog contents
-- ---------------------
-- This migration deliberately seeds NO food rows. The product requires nutrition values to come
-- from an explicitly approved, attributed dataset, and no such dataset ships with this repository.
-- Inventing calorie and macro values would put fabricated nutrition in front of users, so the
-- catalog stays empty until that source is supplied and reviewed.
--
-- Indexes
-- -------
-- meal_items and food_scan_items are the two fastest-growing child tables and neither had any
-- index on its parent column, so every read of a meal's items or a scan's items was a sequential
-- scan of the whole table. The joins are parent-driven, so (parent_id) alone matches them.
CREATE INDEX IF NOT EXISTS idx_meal_items_meal_id ON meal_items(meal_id);
CREATE INDEX IF NOT EXISTS idx_food_scan_items_scan_id ON food_scan_items(scan_id);

-- The scanner resolved a detected food name with lower(trim(name)) = lower(trim(?)), which is a
-- function of the column and therefore not sargable: it cannot use an index and scans the catalog
-- once per detected item. A generated column holding the same expression is computed by
-- PostgreSQL, kept in step with the name automatically, and can be indexed.
ALTER TABLE foods ADD COLUMN IF NOT EXISTS name_normalized text
    GENERATED ALWAYS AS (lower(btrim(name))) STORED;

CREATE INDEX IF NOT EXISTS idx_foods_name_normalized ON foods(name_normalized);
