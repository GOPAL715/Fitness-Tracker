# FitTrack AI

A production-quality, multi-user fitness platform built with React, TypeScript, Vite and Supabase.

FitTrack tracks training down to the individual set, nutrition down to the individual food item, daily
health metrics, habits and goals — and turns all of it into explainable insights and AI-assisted coaching.

---

## 1. Project overview

The app is organised around seven primary views:

| View | Purpose |
| --- | --- |
| **Today** | Daily readiness, recommended action, vitals, nutrition and habit snapshot |
| **Workouts** | Weekly plan, set-level logging, templates, exercise library, session history |
| **Progress** | Readiness/sleep/step/HRV trends, training volume, muscle balance, PRs, body measurements, goal progress |
| **Nutrition** | Macro rings, manual meal composition, AI photo scanner, macro breakdown, weekly summary |
| **Habits** | Daily habit check-ins, streaks, weekly rates, configurable reminders |
| **Goals** | Goal tracking with progress, trend and projected completion dates |
| **History** | Month calendar with per-day dots and full day summaries, plus a filterable timeline |
| **Profile** | Goals and targets, device integration status, AI weekly coach, privacy information |

---

## 2. Architecture

```
                       FITTRACK
                          |
     +--------------------+--------------------+
     |                    |                    |
  WORKOUTS            NUTRITION             HEALTH
     |                    |                    |
     |            +-------+--------+           |
     |            |                |           |
     |          Manual        AI Scanner       |
     |                              |          |
     |                       Food Detection    |
     |                              |          |
     |                       Portion Estimate  |
     |                              |          |
     |                      Nutrition Database |
     |                              |          |
     +--------------+---------------+----------+
                    |
              USER PROFILE
                    |
              GOALS & TARGETS
                    |
                ANALYTICS
                    |
              AI COACH
```

React talks to Supabase Auth, the Postgres database, private Storage and Edge Functions. All AI
provider calls happen inside Edge Functions so the API key never reaches the browser.

---

## 3. Tech stack

- **Frontend:** React 18, TypeScript (strict), Vite 5, hand-written CSS design system, lucide-react icons
- **Backend:** Supabase — Postgres, Auth (email/password), Storage, Edge Functions (Deno)
- **AI:** OpenAI vision model for food photos, text model for the weekly coach, called only server-side

---

## 4. Folder structure

```
src/
  App.tsx                    composition root: screen selection, tab state, view wiring
  main.tsx                   entry point, wraps App in AuthProvider
  index.css                  full design system and responsive rules
  components/
    ui.tsx                   ProgressRing, MiniBar, BarChart, Modal, EmptyState, SectionHeader, StatTile
    AppShell.tsx             header, navigation, footer, loading and error screens
  features/
    appData/
      appData.ts             all reads for the signed-in user + pure hydration joins
      useAppData.ts          loading lifecycle, derived metrics, water mutation
    navigation/
      tabs.ts                tab ids, labels and icons
  lib/
    supabase.ts              client, core record types, shared constants
    types.ts                 types and constants for the upgraded feature tables
    auth.tsx                 AuthProvider + useAuth hook
    insights.ts              readiness, training load, streaks, insights, recommendations
    workoutMetrics.ts        volume, estimated 1RM, muscle distribution, habit streaks
    nutrition.ts             deterministic nutrition maths, image validation and compression
    foodScan.ts              AI response validation, confidence labelling
    dailySummary.ts          dashboard roll-ups for nutrition and habits
    utils.ts                 date and number helpers
    healthProviders.ts       device provider contract, normalisation and merging
  views/
    AuthScreen.tsx           sign in / sign up
    OnboardingScreen.tsx     first-run profile setup
    TodayView.tsx            dashboard
    WorkoutsView.tsx         workout hub (5 sub-tabs)
    SessionLogger.tsx        set/rep/weight logger
    ExerciseLibraryView.tsx  searchable exercise catalogue
    TemplatesView.tsx        workout template CRUD
    ProgressView.tsx         analytics
    NutritionView.tsx        nutrition logging
    FoodScannerModal.tsx     AI photo scanner with correction flow
    HabitsView.tsx           habits and reminders
    GoalsView.tsx            goal tracking
    CalendarView.tsx         month calendar, day summary and history timeline
    ProfileView.tsx          settings, device status, AI coach, privacy
public/
  manifest.webmanifest       PWA manifest
  sw.js                      service worker (app shell only, never caches private data)
tests/
  logic.test.ts              76 tests over the pure business logic
  appData.test.ts            22 tests over hydration, onboarding and tab config
docs/
  architecture.md            layering, data flow, key decisions
  food-scanner.md            scanner flow, validation, cost control
  database.md                tables, RLS patterns, indexes, migrations
supabase/
  config.toml                edge function declarations
  functions/
    analyze-food-photo/      vision analysis -> validated detections -> nutrition lookup
    weekly-coach/            week summary -> AI narrative -> stored review
  migrations/                applied database history
```

---

## 5. Local setup

```bash
npm install
npm run dev
```

---

## 6. Environment variables

Frontend (safe to expose, used by the browser) — see `.env.example`:

```
VITE_SUPABASE_URL=
VITE_SUPABASE_ANON_KEY=
```

Server-side only (Edge Function secrets, **never** prefixed with `VITE_`):

```
OPENAI_API_KEY=          # required for the food scanner and the AI weekly coach
AI_VISION_MODEL=         # optional, defaults to gpt-4o-mini
AI_TEXT_MODEL=           # optional, defaults to gpt-4o-mini
```

`SUPABASE_URL`, `SUPABASE_ANON_KEY` and `SUPABASE_SERVICE_ROLE_KEY` are injected into Edge Functions
automatically by the platform.

Without `OPENAI_API_KEY`, the app runs fully except for the two AI features, which return a clear
"not configured" message instead of failing silently.

---

## 7. Database migrations

Applied through the migration tool, in this order:

1. `create_fitness_tracker_core` — profile, daily metrics, workouts
2. `extend_fittrack_ai_schema` — meals, body metrics, PRs, plan, devices, notifications
3. `add_auth_and_user_scoped_rls` — `user_id` ownership columns, per-user uniqueness, owner-scoped RLS
4. `create_exercise_library_sets_and_templates` — exercises, sessions, sets, templates, goals
5. `create_food_database_habits_reminders_and_scanner` — foods, meal items, habits, reminders, scans, AI usage
6. `create_food_image_storage_bucket` — private bucket and per-user storage policies

---

## 8. Authentication

Supabase email/password. `AuthProvider` wraps the app, restores the session on load, and exposes
`useAuth()`. The composition root renders `AuthScreen` when signed out and the application when signed in.
Email confirmation is off, so a new account can sign in immediately. The `onAuthStateChange`
callback does not await Supabase calls, avoiding the documented deadlock.

---

## 9. RLS architecture

Every user-owned table has `user_id uuid DEFAULT auth.uid()` and four policies scoped to the
`authenticated` role — one each for SELECT, INSERT, UPDATE and DELETE — all using `auth.uid() = user_id`.

Child tables without their own `user_id` (`workout_exercises`, `exercise_sets`,
`workout_template_exercises`, `meal_items`, `food_scan_items`) are scoped through their parent row
with `EXISTS (... AND parent.user_id = auth.uid())`.

Shared reference tables (`exercises`, `foods`) are readable by any authenticated user but read-only.

`anon` holds no table privileges anywhere, so a signed-out request cannot read or write health data.

The client never sends `user_id`; the database default fills it and the policy enforces it.

---

## 10. Food scanner architecture

```
upload photo -> validate MIME + size -> compress client-side -> private Storage
  -> create food_scans row (pending)
  -> Edge Function analyze-food-photo
  -> vision model returns names + grams + confidence (JSON only)
  -> server validates the response shape strictly
  -> each name matched against the `foods` table
  -> nutrition computed deterministically from per-100g values
  -> results shown with confidence labels
  -> user edits food / portion / adds / deletes
  -> confirm -> meals + meal_items rows
  -> scan marked confirmed and linked to the meal
```

Key properties:

- Calories and macros always come from the nutrition database, never from the model.
- The original AI estimate is preserved; user corrections are stored in `confirmed_grams` and
  `user_edited`, so nothing is overwritten and the data stays auditable.
- Confidence is always surfaced, and low confidence triggers an explicit "may be uncertain" warning.
- Mixed and Indian dishes are supported because the prompt asks for component-level identification.

---

## 11. AI architecture

Both AI features are Edge Functions:

- **`analyze-food-photo`** — verifies the caller from their token, checks the scan belongs to them,
  signs a short-lived URL for the private image, calls the vision model, validates the JSON, resolves
  nutrition server-side, and records usage in `ai_usage`.
- **`weekly-coach`** — reads the caller's last 7 days, computes all statistics server-side, sends only
  that summary to the model, normalises the response, stores the review as a notification, and records
  usage. If there is not enough data it returns an explicit error rather than inventing a report.

Neither function trusts a user id from the request body, and neither exposes the API key.

---

## 12. Running locally

```bash
npm install
npm run dev      # development server
npm run build    # type check + production build
npm run test     # run the test suite once
npm run preview  # preview the production build
```

## 13. Testing

98 automated tests cover the pure business logic with Vitest:

- nutrition scaling, macro totals and food matching
- AI response validation, malformed input, confidence tiers
- image validation (type and size)
- volume, estimated 1RM, muscle distribution, weekly volume series
- readiness, training load, streaks, consistency
- goal progress including zero-span and decreasing goals
- habit streaks and gap handling
- the full scan-to-meal transformation, including a partially unmatched scan
- session and template hydration, including orphaned exercises and cross-session leakage
- onboarding detection and tab configuration

```bash
npm run test
```

Not covered by automated tests: database RLS behaviour (worth an integration suite against a
real Supabase instance), the live AI provider call, and any UI rendering.

## 14. Deployment

Build with `npm run build` and serve `dist/`. The app is a static SPA; ensure unknown routes fall
back to `index.html` (`dist/_redirects` is included for that).

## 15. Troubleshooting

- **"FitTrack could not load your data"** — the database or network is unreachable; use Try again.
- **"AI analysis is not configured"** — `OPENAI_API_KEY` is not set for the Edge Functions.
- **Empty screens after signing in** — expected for a brand new account; log data or use the
  seeded demo account.
- **Photo upload rejected** — only JPEG, PNG and WebP under 8 MB are accepted.

## 16. Security notes

- No secret is ever exposed to the browser; only `VITE_SUPABASE_URL` and `VITE_SUPABASE_ANON_KEY`.
- The service-role key exists only inside Edge Functions.
- Storage is a private bucket with per-user folder policies.
- RLS is enabled on every table with owner-scoped policies; `anon` is denied everywhere.
- The Supabase security advisor reports zero findings.

## 17. Known limitations

**Device integrations are not live.** Apple Health and Google Health Connect are native-only APIs with
no web access. Fitbit and Garmin need an OAuth application with server-side credentials. All four are
described honestly in the Profile screen along with exactly what each would require. The normalisation
layer in `healthProviders.ts` is real and tested in shape, but no provider is connected.

**The PWA shell works offline; workout logging offline does not.** The service worker caches the app
shell so FitTrack opens without a connection, and deliberately never caches private health data. The
IndexedDB write queue and sync-with-retry described in the original plan is not implemented.

**Reminders are stored, not delivered.** Full schedule, recurring days and quiet hours are persisted and
editable, but nothing fires a browser notification yet. The service worker is the natural place to add it.

**AI features need a server key.** Without `OPENAI_API_KEY` the scanner and weekly coach return a clear
not-configured message. Everything else works.

**Not implemented at all:** social features and challenges, the admin/SaaS layer, and role-based
authorization. I chose not to stub these rather than ship a facade.

**No visual verification.** I verified the build, types and business logic, but I have no browser tool in
this environment, so no screen has been clicked through. If something looks wrong, tell me and I will fix it.

**Views still own their writes.** Reads are centralised in `features/appData`, but mutations
(templates, meals, habits, goals, profile, sessions) still call Supabase from within the views.
Extracting those into feature-scoped mutation modules is the next structural step.

**Every mutation refetches everything.** `useAppData.reload()` re-reads all 20 queries after any change.
Simple and always correct, but more work than a targeted refresh.

**Bundle size.** The main JS chunk is about 572 kB before gzip. Route-level code splitting would help.
