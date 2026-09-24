# FitTrack AI

A multi-user fitness platform built with React, TypeScript, Vite, Spring Boot, and PostgreSQL.

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

The React application calls the Spring Boot REST API with JWT authentication. PostgreSQL is the system of record; private scan storage is server-managed.

---

## 3. Tech stack

- **Frontend:** React 18, TypeScript (strict), Vite 5, hand-written CSS design system, lucide-react icons
- **Backend:** Spring Boot 3.3, Java 21, PostgreSQL, Flyway
- **AI:** Optional OpenAI-compatible provider invoked only by the backend

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
    api/                    versioned Spring Boot REST client
    supabase.ts             historical type compatibility; not a runtime client
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

Frontend configuration (used by the browser) — see `.env.example`:

```
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

The frontend does not require Supabase URL or anon-key variables. Historical Supabase environment
variables may remain in migration material only and must not be used to configure the active app.

Backend configuration is supplied to Spring Boot through the environment or a secret manager. The
optional AI provider is configured on the backend, never through a `VITE_` variable:

```
OPENAI_API_KEY=
AI_VISION_MODEL=
AI_TEXT_MODEL=
```

Without `OPENAI_API_KEY`, the app runs fully except for the two AI features, which return a clear
"not configured" message instead of failing silently.

---

## 7. Database migrations

The active backend uses Flyway migrations in `backend/src/main/resources/db/migration`. The historical
`supabase/migrations` directory is retained for reference and is not applied by the Spring backend.


---

## 8. Authentication

Authentication uses the Spring Boot API: email/password registration and login return a short-lived JWT
access token and a rotating opaque refresh token. The refresh token is stored by the client in localStorage
for the current implementation; this is an acknowledged browser-security limitation. Password recovery,
account recovery, and email verification are not implemented.

---

## 9. Authorization architecture

Spring Boot derives ownership from the JWT subject. The client does not provide `user_id` for ownership.
Owned resources are scoped to the authenticated user in service queries; child resources prove ownership
through their parent chain. Shared exercise and food catalogs are read-only. There is no active Supabase
RLS layer in the Spring application.

The complete security/ownership acceptance matrix remains Phase 12 work.

---

## 10. Food scanner architecture

The active scanner is implemented by `ScannerController` and `ScannerService` in Spring Boot:

```
multipart file upload -> MIME/signature/size validation -> private owner-scoped storage
  -> persisted food_scan row
  -> backend AiProvider analysis and validation
  -> controlled food matching and server-side nutrition calculation
  -> user corrections through PUT /food-scans/{id}/items
  -> transactional confirmation creates meals and meal_items
  -> deletion removes the scan and stored object
```

The historical `supabase/functions/analyze-food-photo` implementation is retained as migration reference;
it is not called by the React runtime.

Key properties:

- Calories and macros always come from the nutrition database, never from the model.
- The original AI estimate is preserved; user corrections are stored in `confirmed_grams` and
  `user_edited`, so nothing is overwritten and the data stays auditable.
- Confidence is always surfaced, and low confidence triggers an explicit "may be uncertain" warning.
- Mixed and Indian dishes are supported because the prompt asks for component-level identification.

---

## 11. AI architecture

The active backend exposes `/api/v1/coach/analyze` and `/api/v1/coach/weekly-review` through an
`AiProvider` abstraction. Provider configuration and usage accounting are backend concerns; the browser
never receives an API key. Complete AI usage accounting, provider quotas, and production error controls
remain Phase 14 work.

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

Not covered by the frontend unit suite: live AI provider calls and UI rendering. Backend acceptance
coverage is documented in the backend test reports.

## 14. Deployment

Build with `npm run build` and serve `dist/`. The app is a static SPA; ensure unknown routes fall
back to `index.html` (`dist/_redirects` is included for that).

## 15. Troubleshooting

- **"FitTrack could not load your data"** — the database or network is unreachable; use Try again.
- **"AI analysis is not configured"** — the backend AI provider is not configured.
- **Empty screens after signing in** — expected for a brand new account; log data or use the
  seeded demo account.
- **Photo upload rejected** — only JPEG, PNG and WebP under 8 MB are accepted.

## 16. Security notes

- No secret is exposed through the frontend; backend secrets remain server-side.
- Scan objects are stored privately and scoped to the authenticated owner.
- Catalog writes are not exposed.
- CORS is restricted to the configured local frontend origins in `SecurityConfig`; production origins must
  be explicitly reviewed and configured.
- Current limitations include localStorage token storage, no password recovery, no explicit rate limiting
  or lockout, and no production abuse controls.

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

**Mutations use the Spring REST API.** Reads are centralized in `features/appData`; mutation modules call
versioned `/api/v1` resources. Some workflows still make multiple HTTP requests for parent/child writes;
composite transaction endpoints remain Phase 13 work.

**Every mutation refetches everything.** `useAppData.reload()` re-reads all 20 queries after any change.
Simple and always correct, but more work than a targeted refresh.

**Bundle size.** The main JS chunk is about 572 kB before gzip. Route-level code splitting would help.
